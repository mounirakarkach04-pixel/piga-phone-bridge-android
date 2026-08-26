package io.piga.phonebridge

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.Signature
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class BridgeService : Service() {
    private val running = AtomicBoolean(false)
    private val alias = "piga_phone_bridge_device_key"
    private val prefs by lazy { getSharedPreferences("piga_bridge", MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        ensureChannels()
        startForeground(2001, statusNotification("PIGA Bridge connected"))
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        if (running.compareAndSet(false, true)) Thread { pollLoop() }.start()
        return START_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        super.onDestroy()
    }

    override fun onBind(intent: android.content.Intent?): IBinder? = null

    private fun pollLoop() {
        while (running.get()) {
            prefs.edit().putLong("runtime_heartbeat_ms", System.currentTimeMillis()).apply()
            try {
                if (!prefs.getBoolean("paired", false)) {
                    Thread.sleep(5000)
                    continue
                }
                val root = prefs.getString("base_url", null)?.trim()?.removeSuffix("/")
                    ?: throw IllegalStateException("Missing bridge base URL")
                val deviceId = prefs.getString("device_id", null)?.trim()
                    ?: throw IllegalStateException("Missing device id")

                require(deviceId.isNotBlank()) { "Missing device id" }
                controlPlaneOrigin(root)

                syncEmergencySafetyIfRequired(root, deviceId)
                reconcileExecutionJournals()
                retryPendingResults(root, deviceId)

                val path = "/api/bridge/devices/$deviceId/commands"
                val response = signedRuntimeGet(root, deviceId, path)
                verifyOrPinServerIdentity(response)
                val commands = response.optJSONArray("commands")
                val count = commands?.length() ?: 0
                if (commands != null) {
                    for (i in 0 until commands.length()) {
                        processCommand(root, deviceId, commands.getJSONObject(i))
                    }
                }

                prefs.edit()
                    .putLong("last_poll_ms", System.currentTimeMillis())
                    .putString("runtime_status", "ONLINE commands=$count")
                    .apply()
                updateNotification("PIGA Bridge online • pending $count")
            } catch (e: Exception) {
                prefs.edit()
                    .putString("runtime_status", "ERROR ${e.message ?: e.javaClass.simpleName}")
                    .apply()
                updateNotification("PIGA Bridge reconnecting")
            }

            try {
                Thread.sleep(15000)
            } catch (_: InterruptedException) {
                running.set(false)
            }
        }
    }

    /**
     * The signed safety endpoint is deliberately one-way/fail-closed: a phone may
     * tighten the posture, but must never release Emergency Stop. Normal governed
     * autonomy state is controlled by the Enterprise control plane.
     */
    private fun syncEmergencySafetyIfRequired(root: String, deviceId: String) {
        if (!prefs.getBoolean("emergency_stop", false)) return

        val notificationsGranted = hasNotificationPermission()
        val body = JSONObject()
            .put("masterAutonomy", false)
            .put("notificationsGranted", notificationsGranted)
            .put("emergencyStop", true)
            .toString()
        val path = "/api/bridge/devices/$deviceId/safety"
        val response = signedRuntimePost(root, deviceId, path, body)
        verifyOrPinServerIdentity(response)
        prefs.edit()
            .putBoolean("notification_permission", notificationsGranted)
            .putLong("last_safety_sync_ms", System.currentTimeMillis())
            .apply()
    }

    private fun processCommand(root: String, deviceId: String, command: JSONObject) {
        val commandId = command.optString("commandId").trim()
        val commandDeviceId = command.optString("deviceId").trim()
        val type = command.optString("type").trim()
        val scope = command.optString("capabilityScope").trim()
        val commandNonce = command.optString("nonce").trim()
        val expiresAt = command.optString("expiresAt").trim()
        val leaseUntil = command.optString("leaseUntil").trim()
        val payload = command.optJSONObject("payload")
        val factoryEvidenceOnly = command.optBoolean("factoryEvidenceOnly", false)

        if (commandId.isBlank()) return

        // Current Enterprise registry intentionally admits only notifications.
        // Everything else remains branch-local blocked until server governance
        // registers the capability explicitly.
        if (
            commandDeviceId != deviceId
            || commandNonce.isBlank()
            || expiresAt.isBlank()
            || leaseUntil.isBlank()
            || payload == null
            || type != "local_notification"
            || scope != "pocket.notification"
            || factoryEvidenceOnly
        ) {
            rejectDeliveredCommand(root, deviceId, commandId, "Command schema, device binding, capability scope, or effect mode rejected.")
            return
        }

        val factoryCorrelation = try {
            CommandReceiptContract.parseFactoryCorrelation(commandId, command.optJSONObject("factoryContext"))
        } catch (e: IllegalArgumentException) {
            rejectDeliveredCommand(root, deviceId, commandId, e.message ?: "Factory command correlation rejected.")
            return
        }

        val pendingKey = CommandReceiptContract.outboxKey(commandId)
        val journalKey = CommandExecutionJournal.key(commandId)
        when (
            CommandExecutionPolicy.decide(
                hasPendingResult = prefs.contains(pendingKey),
                hasExecutionJournal = prefs.contains(journalKey),
                nonceReplay = prefs.getBoolean("command_nonce_$commandNonce", false),
            )
        ) {
            CommandExecutionPolicy.Decision.REDELIVER_PENDING_RESULT -> return
            CommandExecutionPolicy.Decision.REPORT_UNCERTAIN -> {
                reconcileExecutionJournal(journalKey)
                retryPendingResults(root, deviceId)
                return
            }
            CommandExecutionPolicy.Decision.REJECT_NONCE_REPLAY -> {
                rejectDeliveredCommand(root, deviceId, commandId, "Command nonce replay rejected.")
                return
            }
            CommandExecutionPolicy.Decision.EXECUTE -> Unit
        }

        val now = Instant.now()
        try {
            require(now.isBefore(Instant.parse(expiresAt))) { "Command expired before execution." }
            require(now.isBefore(Instant.parse(leaseUntil))) { "Command lease expired before execution." }
        } catch (e: Exception) {
            rejectDeliveredCommand(root, deviceId, commandId, e.message ?: "Invalid expiry or lease timestamp.")
            return
        }

        if (!prefs.getBoolean("master_autonomy", false) || prefs.getBoolean("emergency_stop", false)) {
            rejectDeliveredCommand(root, deviceId, commandId, "Local safety gate blocked execution.")
            return
        }
        if (!hasNotificationPermission()) {
            rejectDeliveredCommand(root, deviceId, commandId, "Notification permission missing.")
            return
        }

        sendAck(root, deviceId, commandId, "accepted", "Android worker accepted the governed command lease.")
        sendAck(root, deviceId, commandId, "running", "Android worker entered pre-effect running state.")

        val admission = signedRuntimePost(
            root,
            deviceId,
            CommandReceiptContract.admissionPath(deviceId, commandId),
            "",
        )
        verifyOrPinServerIdentity(admission)
        val effectId = admission.optString("effectId").trim()
        val effectNonce = admission.optString("effectNonce").trim()
        require(effectId.isNotBlank() && effectNonce.isNotBlank()) { "Authoritative effect identity missing." }
        require(admission.optString("commandId") == commandId) { "Admission command binding mismatch." }
        require(admission.optString("deviceId") == deviceId) { "Admission device binding mismatch." }
        require(admission.optString("capabilityScope") == scope) { "Admission capability binding mismatch." }

        val commitBody = JSONObject()
            .put("effectId", effectId)
            .put("effectNonce", effectNonce)
            .toString()
        val commit = signedRuntimePost(
            root,
            deviceId,
            CommandReceiptContract.admissionCommitPath(deviceId, commandId),
            commitBody,
        )
        verifyOrPinServerIdentity(commit)
        require(commit.optString("effectId") == effectId) { "Effect commit id mismatch." }
        require(commit.optString("effectNonce") == effectNonce) { "Effect commit nonce mismatch." }
        require(commit.optString("commandId") == commandId) { "Effect commit command mismatch." }

        val journal = CommandExecutionJournal.Entry(
            commandId = commandId,
            commandNonce = commandNonce,
            startedAtMs = System.currentTimeMillis(),
            jobId = factoryCorrelation?.jobId,
            subjobId = factoryCorrelation?.subjobId,
            verifiedPlanHash = factoryCorrelation?.verifiedPlanHash,
            effectId = effectId,
            effectNonce = effectNonce,
        )
        require(
            prefs.edit()
                .putBoolean("command_nonce_$commandNonce", true)
                .putString(journalKey, CommandExecutionJournal.encode(journal))
                .commit()
        ) { "Unable to persist committed effect journal before execution." }

        val outcome = try {
            val detail = executeNotification(commandId, payload)
            CommandReceiptContract.PendingResult(
                commandId = commandId,
                commandNonce = commandNonce,
                status = "succeeded",
                detail = detail,
                createdAtMs = System.currentTimeMillis(),
                jobId = factoryCorrelation?.jobId,
                subjobId = factoryCorrelation?.subjobId,
                verifiedPlanHash = factoryCorrelation?.verifiedPlanHash,
                effectId = effectId,
                effectNonce = effectNonce,
            )
        } catch (e: Exception) {
            // Once authoritative commit has crossed the effect boundary, an
            // exception cannot prove that no user-visible effect occurred.
            CommandReceiptContract.PendingResult(
                commandId = commandId,
                commandNonce = commandNonce,
                status = "uncertain",
                detail = e.message ?: "Local notification effect state is uncertain after commit.",
                createdAtMs = System.currentTimeMillis(),
                jobId = factoryCorrelation?.jobId,
                subjobId = factoryCorrelation?.subjobId,
                verifiedPlanHash = factoryCorrelation?.verifiedPlanHash,
                effectId = effectId,
                effectNonce = effectNonce,
            )
        }

        persistPendingResult(outcome)
        require(prefs.edit().remove(journalKey).commit()) {
            "Terminal result persisted but effect journal cleanup failed."
        }
        deliverPendingResult(root, deviceId, outcome)
    }

    private fun rejectDeliveredCommand(root: String, deviceId: String, commandId: String, detail: String) {
        sendAck(root, deviceId, commandId, "rejected", detail)
    }

    private fun persistPendingResult(result: CommandReceiptContract.PendingResult) {
        val key = CommandReceiptContract.outboxKey(result.commandId)
        require(prefs.edit().putString(key, CommandReceiptContract.encodePendingResult(result)).commit()) {
            "Unable to persist terminal command result."
        }
    }

    private fun reconcileExecutionJournals() {
        prefs.all.keys
            .filter { it.startsWith(CommandExecutionJournal.KEY_PREFIX) }
            .sorted()
            .forEach(::reconcileExecutionJournal)
    }

    private fun reconcileExecutionJournal(key: String) {
        val raw = prefs.all[key] as? String
        if (raw == null) {
            quarantineLocalEntry(key, null, "effect_journal_type_invalid")
            return
        }
        val entry = CommandExecutionJournal.decode(raw)
        if (entry == null) {
            quarantineLocalEntry(key, raw, "effect_journal_decode_failed")
            return
        }
        if (entry.effectId.isNullOrBlank() || entry.effectNonce.isNullOrBlank()) {
            quarantineLocalEntry(key, raw, "legacy_effect_journal_missing_authoritative_tokens")
            return
        }

        val pendingKey = CommandReceiptContract.outboxKey(entry.commandId)
        if (prefs.contains(pendingKey)) {
            require(prefs.edit().remove(key).commit()) {
                "Unable to clear redundant effect journal after terminal result persistence."
            }
            return
        }

        persistPendingResult(CommandExecutionJournal.uncertainResult(entry))
        require(prefs.edit().remove(key).commit()) {
            "Unable to clear effect journal after UNCERTAIN result persistence."
        }
        prefs.edit()
            .putLong("uncertain_effect_count", prefs.getLong("uncertain_effect_count", 0L) + 1L)
            .putString("last_uncertain_command_id", entry.commandId)
            .putLong("last_uncertain_effect_ms", System.currentTimeMillis())
            .apply()
    }

    private fun retryPendingResults(root: String, deviceId: String) {
        val pending = prefs.all.entries
            .filter { it.key.startsWith("pending_command_result_") }
            .sortedBy { it.key }

        for ((key, raw) in pending) {
            val encoded = raw as? String
            if (encoded == null) {
                quarantineLocalEntry(key, null, "pending_result_type_invalid")
                continue
            }
            val result = CommandReceiptContract.decodePendingResult(encoded)
            if (result == null) {
                quarantineLocalEntry(key, encoded, "pending_result_decode_failed")
                continue
            }
            if (result.effectId.isNullOrBlank() || result.effectNonce.isNullOrBlank()) {
                quarantineLocalEntry(key, encoded, "pending_result_missing_authoritative_effect_tokens")
                continue
            }
            deliverPendingResult(root, deviceId, result)
        }
    }

    private fun quarantineLocalEntry(key: String, raw: String?, reason: String) {
        val now = System.currentTimeMillis()
        val quarantineKey = "quarantined_bridge_entry_${now}_${key.hashCode()}"
        val evidence = JSONObject()
            .put("sourceKey", key)
            .put("reason", reason)
            .put("quarantinedAtMs", now)
            .put("raw", raw ?: JSONObject.NULL)
            .toString()
        require(
            prefs.edit()
                .putString(quarantineKey, evidence)
                .remove(key)
                .putLong("bridge_quarantine_count", prefs.getLong("bridge_quarantine_count", 0L) + 1L)
                .putString("last_bridge_quarantine_reason", reason)
                .commit()
        ) { "Unable to quarantine malformed bridge entry." }
    }

    private fun deliverPendingResult(
        root: String,
        deviceId: String,
        result: CommandReceiptContract.PendingResult,
    ) {
        val effectId = requireNotNull(result.effectId) { "Result effectId missing." }
        val effectNonce = requireNotNull(result.effectNonce) { "Result effectNonce missing." }
        val wireStatus = BridgeServerProtocolPolicy.wireResultStatus(result.status)
        val body = JSONObject()
            .put("status", wireStatus)
            .put("resultCode", BridgeServerProtocolPolicy.resultCode(result.status))
            .put("detail", result.detail)
            .put("effectBlocked", false)
            .put("effectId", effectId)
            .put("effectNonce", effectNonce)
            .toString()
        val response = signedRuntimePost(
            root,
            deviceId,
            CommandReceiptContract.resultPath(deviceId, result.commandId),
            body,
        )
        verifyOrPinServerIdentity(response)
        val key = CommandReceiptContract.outboxKey(result.commandId)
        require(prefs.edit().remove(key).commit()) {
            "Result delivered but local outbox cleanup failed."
        }
    }

    private fun executeNotification(commandId: String, payload: JSONObject): String {
        val title = payload.optString("title").trim()
        val body = payload.optString("body").trim()
        require(title.isNotBlank() && body.isNotBlank()) { "Notification payload invalid." }
        showLocalNotification(commandId, title, body)
        return "Benachrichtigung lokal ausgeführt."
    }

    private fun sendAck(
        root: String,
        deviceId: String,
        commandId: String,
        status: String,
        detail: String,
    ) {
        val body = JSONObject()
            .put("status", status)
            .put("detail", detail)
            .toString()
        val response = signedRuntimePost(
            root,
            deviceId,
            CommandReceiptContract.ackPath(deviceId, commandId),
            body,
        )
        verifyOrPinServerIdentity(response)
    }

    private fun signedRuntimeGet(root: String, deviceId: String, apiPath: String): JSONObject =
        signedRuntimeRequest(root, deviceId, "GET", apiPath, "")

    private fun signedRuntimePost(root: String, deviceId: String, apiPath: String, body: String): JSONObject =
        signedRuntimeRequest(root, deviceId, "POST", apiPath, body)

    private fun signedRuntimeRequest(
        root: String,
        deviceId: String,
        method: String,
        apiPath: String,
        body: String,
    ): JSONObject {
        val origin = controlPlaneOrigin(root)
        val timestamp = System.currentTimeMillis().toString()
        val counter = nextRuntimeCounter().toString()
        val requestId = UUID.randomUUID().toString()
        val canonical = BridgeRuntimeRequestContract.canonicalMessage(
            method = method,
            apiPath = apiPath,
            timestampMs = timestamp,
            counter = counter,
            requestId = requestId,
            body = body,
            controlPlaneOrigin = origin,
        )
        val signature = sign(canonical)

        val connection = (URL("$root$apiPath").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-PIGA-Device-Id", deviceId)
            setRequestProperty("X-PIGA-Timestamp", timestamp)
            setRequestProperty("X-PIGA-Counter", counter)
            setRequestProperty("X-PIGA-Request-Id", requestId)
            setRequestProperty("X-PIGA-Signature", signature)
            setRequestProperty("X-PIGA-Bridge-Version", BridgeRuntimeRequestContract.BRIDGE_VERSION)
            setRequestProperty("X-PIGA-Bridge-Version-Code", BridgeRuntimeRequestContract.BRIDGE_VERSION_CODE.toString())
            setRequestProperty("X-PIGA-Control-Plane-Origin", origin)
            if (method == "POST") {
                doOutput = body.isNotEmpty()
                setRequestProperty("Content-Type", "application/json")
            }
        }
        if (method == "POST" && body.isNotEmpty()) {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        return readJsonResponse(connection)
    }

    @Synchronized
    private fun nextRuntimeCounter(): Long {
        val current = prefs.getLong("bridge_runtime_counter", 0L)
        val next = BridgeRuntimeRequestContract.nextCounter(current)
        require(prefs.edit().putLong("bridge_runtime_counter", next).commit()) {
            "Unable to persist monotonic bridge request counter."
        }
        return next
    }

    private fun controlPlaneOrigin(root: String): String {
        val url = URL(root)
        require(url.protocol.equals("https", ignoreCase = true)) { "Control Plane must use HTTPS." }
        require(url.path.isEmpty() || url.path == "/") { "Control Plane URL must be an origin without a path." }
        require(url.query == null && url.ref == null && url.userInfo == null) { "Control Plane origin must be canonical." }
        val port = if (url.port == -1 || url.port == url.defaultPort) "" else ":${url.port}"
        return "https://${url.host.lowercase()}$port"
    }

    private fun readJsonResponse(connection: HttpURLConnection): JSONObject {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        connection.disconnect()
        if (code !in 200..299) throw IllegalStateException("HTTP $code ${text.take(240)}")
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun sign(canonical: String): String {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val entry = ks.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
            ?: throw IllegalStateException("Device signing key missing.")
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(entry.privateKey)
        signer.update(canonical.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(signer.sign(), Base64.NO_WRAP)
    }

    private fun verifyOrPinServerIdentity(response: JSONObject) {
        val publicKey = response.optString("serverPublicKey").trim()
        val keyId = response.optString("serverKeyId").trim()
        if (publicKey.isBlank() || keyId.isBlank()) return

        val pinnedKey = prefs.getString("server_public_key", null)?.trim()
        val pinnedKeyId = prefs.getString("server_key_id", null)?.trim()
        if (!pinnedKey.isNullOrBlank() || !pinnedKeyId.isNullOrBlank()) {
            require(publicKey == pinnedKey && keyId == pinnedKeyId) { "Server bridge identity changed unexpectedly." }
            return
        }
        require(
            prefs.edit()
                .putString("server_public_key", publicKey)
                .putString("server_key_id", keyId)
                .commit()
        ) { "Unable to pin server bridge identity." }
    }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel("piga_runtime", "PIGA Bridge Runtime", NotificationManager.IMPORTANCE_LOW),
            )
            manager.createNotificationChannel(
                NotificationChannel("piga_commands", "PIGA Local Commands", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }

    private fun showLocalNotification(commandId: String, title: String, body: String) {
        val notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, "piga_commands")
                .setContentTitle(title)
                .setContentText(body)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setAutoCancel(true)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle(title)
                .setContentText(body)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setAutoCancel(true)
                .build()
        }
        getSystemService(NotificationManager::class.java).notify(commandId.hashCode(), notification)
    }

    private fun statusNotification(text: String): Notification = if (Build.VERSION.SDK_INT >= 26) {
        Notification.Builder(this, "piga_runtime")
            .setContentTitle("PIGA Phone Bridge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    } else {
        Notification.Builder(this)
            .setContentTitle("PIGA Phone Bridge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(2001, statusNotification(text))
    }
}
