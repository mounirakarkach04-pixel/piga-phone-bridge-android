package io.piga.phonebridge

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
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
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class BridgeService : Service() {
    private val running = AtomicBoolean(false)
    private val alias = "piga_phone_bridge_device_key"
    private val prefs by lazy { getSharedPreferences("piga_bridge", MODE_PRIVATE) }
    private val bridgeCounterLock = Object()
    private val bridgeContractVersion = "0.2.0"
    private val bridgeContractVersionCode = 19

    override fun onCreate() {
        super.onCreate()
        ensureChannels()
        startForeground(2001, statusNotification("PIGA Bridge connected"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running.compareAndSet(false, true)) Thread { pollLoop() }.start()
        return START_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun pollLoop() {
        while (running.get()) {
            try {
                if (!prefs.getBoolean("paired", false)) {
                    Thread.sleep(5000)
                    continue
                }
                val root = prefs.getString("base_url", null)?.trim()?.removeSuffix("/")
                    ?: throw IllegalStateException("Missing bridge base URL")
                val deviceId = prefs.getString("device_id", null)
                    ?: throw IllegalStateException("Missing device id")
                val pairingId = prefs.getString("pairing_id", null)
                    ?: throw IllegalStateException("Missing pairing id")

                syncSafety(root, deviceId, pairingId)
                reconcileUncertainEffects()
                retryPendingResults(root, deviceId, pairingId)

                val canonicalPath = "/api/bridge/devices/$deviceId/commands"
                val response = signedRuntimeGet("$root$canonicalPath", canonicalPath, pairingId)
                requirePinnedServerIdentity(response)
                val commands = response.optJSONArray("commands")
                val count = commands?.length() ?: 0
                if (commands != null) {
                    for (i in 0 until commands.length()) {
                        processCommand(root, deviceId, pairingId, commands.getJSONObject(i))
                    }
                }

                prefs.edit()
                    .putLong("last_poll_ms", System.currentTimeMillis())
                    .putString("runtime_status", "ONLINE commands=$count")
                    .apply()
                updateNotification("PIGA Bridge online • pending $count")
            } catch (e: Exception) {
                prefs.edit().putString("runtime_status", "ERROR ${e.message ?: e.javaClass.simpleName}").apply()
                updateNotification("PIGA Bridge reconnecting")
            }
            try {
                Thread.sleep(15000)
            } catch (_: InterruptedException) {
                running.set(false)
            }
        }
    }

    private fun syncSafety(root: String, deviceId: String, pairingId: String) {
        val masterAutonomy = prefs.getBoolean("master_autonomy", false)
        val emergencyStop = prefs.getBoolean("emergency_stop", true)
        val notificationPermission = hasNotificationPermission()

        if (!emergencyStop) {
            val path = "/api/bridge/devices/$deviceId/status"
            val status = signedRuntimeGet("$root$path", path, pairingId)
            requirePinnedServerIdentity(status)
            if (status.optBoolean("emergencyStop", true)) {
                require(
                    prefs.edit()
                        .putBoolean("emergency_stop", true)
                        .putBoolean("master_autonomy", false)
                        .putString("autonomy_status", "BLOCKED_REMOTE_EMERGENCY_STOP")
                        .commit()
                ) { "Unable to persist remote Emergency Stop." }
                throw IllegalStateException("Governance Emergency Stop remains active")
            }
            prefs.edit()
                .putBoolean("notification_permission", notificationPermission)
                .putLong("last_safety_sync_ms", System.currentTimeMillis())
                .apply()
            return
        }

        val body = JSONObject()
            .put("masterAutonomy", masterAutonomy)
            .put("notificationsGranted", notificationPermission)
            .put("emergencyStop", true)
            .toString()
        val path = "/api/bridge/devices/$deviceId/safety"
        val response = signedRuntimePost("$root$path", path, pairingId, body)
        requirePinnedServerIdentity(response)
        prefs.edit()
            .putBoolean("notification_permission", notificationPermission)
            .putLong("last_safety_sync_ms", System.currentTimeMillis())
            .apply()
    }

    private fun processCommand(root: String, deviceId: String, pairingId: String, command: JSONObject) {
        require(verifyCommandEnvelope(command, deviceId)) { "Signed command envelope verification failed." }

        val commandId = command.optString("commandId").trim()
        val type = command.optString("type").trim()
        val scope = command.optString("capabilityScope").trim()
        val commandNonce = command.optString("nonce").trim()
        val expiresAt = command.optString("expiresAt").trim()
        val leaseUntil = command.optString("leaseUntil").trim()
        val payload = command.optJSONObject("payload")
        val factoryEvidenceOnly = command.optBoolean("factoryEvidenceOnly", false)

        if (
            commandId.isBlank()
            || commandNonce.isBlank()
            || expiresAt.isBlank()
            || leaseUntil.isBlank()
            || payload == null
            || type != "local_notification"
            || scope != "pocket.notification"
        ) {
            if (commandId.isNotBlank()) {
                rejectDeliveredCommand(root, deviceId, pairingId, commandId, "Command schema or server allowlist rejected.")
            }
            return
        }

        if (factoryEvidenceOnly) {
            rejectDeliveredCommand(root, deviceId, pairingId, commandId, "Evidence-only command cannot execute a local effect.")
            return
        }

        val pendingResultKey = CommandReceiptContract.outboxKey(commandId)
        if (prefs.contains(pendingResultKey)) return

        val effectKey = CommandReceiptContract.effectIntentKey(commandId)
        if (prefs.contains(effectKey)) {
            // Any process restart after authoritative commit is ambiguous. Never replay.
            persistUnknownResultFromStoredIntent(commandId, commandNonce, effectKey)
            return
        }

        if (prefs.getBoolean("command_nonce_$commandNonce", false)) {
            rejectDeliveredCommand(root, deviceId, pairingId, commandId, "Command nonce replay rejected.")
            return
        }

        val now = Instant.now()
        try {
            if (!Instant.parse(expiresAt).isAfter(now) || !Instant.parse(leaseUntil).isAfter(now)) {
                rejectDeliveredCommand(root, deviceId, pairingId, commandId, "Command or execution lease expired before acceptance.")
                return
            }
        } catch (_: Exception) {
            rejectDeliveredCommand(root, deviceId, pairingId, commandId, "Invalid command expiry or lease timestamp.")
            return
        }

        val masterAutonomy = prefs.getBoolean("master_autonomy", false)
        val emergencyStop = prefs.getBoolean("emergency_stop", true)
        if (!masterAutonomy || emergencyStop || !hasNotificationPermission()) {
            rejectDeliveredCommand(root, deviceId, pairingId, commandId, "Local safety posture or notification permission blocked execution.")
            return
        }

        sendAck(
            root,
            deviceId,
            pairingId,
            commandId,
            "accepted",
            "Local scope, notification permission, device posture, expiry and replay guards revalidated."
        )
        sendAck(
            root,
            deviceId,
            pairingId,
            commandId,
            "running",
            "Local execution entered running state under the current server lease."
        )

        val admission = requestAdmission(root, deviceId, pairingId, commandId, commandNonce, scope)
        val intent = CommandReceiptContract.EffectIntent(
            commandId = commandId,
            commandNonce = commandNonce,
            effectId = admission.effectId,
            effectNonce = admission.effectNonce,
            phase = "intent_written",
            createdAtMs = System.currentTimeMillis(),
            factoryEvidenceOnly = false
        )
        persistEffectIntent(intent)

        try {
            revalidateBeforeCommit(admission, expiresAt, leaseUntil)
            commitEffect(root, deviceId, pairingId, intent, scope)
            val committed = intent.copy(phase = "execution_started", createdAtMs = System.currentTimeMillis())
            persistEffectIntent(committed)

            // Persist the command nonce before the local effect. A crash can therefore never replay it.
            require(prefs.edit().putBoolean("command_nonce_$commandNonce", true).commit()) {
                "Unable to persist command nonce before execution."
            }

            val detail = executeNotification(commandId, payload)
            val result = CommandReceiptContract.PendingResult(
                commandId = commandId,
                commandNonce = commandNonce,
                status = "succeeded",
                detail = detail,
                createdAtMs = System.currentTimeMillis(),
                resultCode = "local_allowlist_success",
                effectBlocked = false,
                effectId = intent.effectId,
                effectNonce = intent.effectNonce
            )
            persistPendingResult(result)
            clearEffectIntent(commandId)
            deliverPendingResult(root, deviceId, pairingId, result)
        } catch (e: Exception) {
            val stored = loadEffectIntent(commandId)
            val afterCommit = stored?.phase == "execution_started"
            val status = if (afterCommit) "unknown_requires_review" else "failed"
            val resultCode = if (afterCommit) "effect_result_ambiguous" else "local_effect_blocked_before_commit"
            val detail = if (afterCommit) {
                "Authoritative commit occurred and the local effect may have started; replay is blocked and review is required. ${e.message ?: "unknown failure"}"
            } else {
                "Local effect failed closed before authoritative commit completed. ${e.message ?: "unknown failure"}"
            }
            if (stored != null) {
                val result = CommandReceiptContract.PendingResult(
                    commandId = commandId,
                    commandNonce = commandNonce,
                    status = status,
                    detail = detail,
                    createdAtMs = System.currentTimeMillis(),
                    resultCode = resultCode,
                    effectBlocked = false,
                    effectId = stored.effectId,
                    effectNonce = stored.effectNonce
                )
                persistPendingResult(result)
                if (!afterCommit) clearEffectIntent(commandId)
                deliverPendingResult(root, deviceId, pairingId, result)
                if (afterCommit) clearEffectIntent(commandId)
            } else {
                throw e
            }
        }
    }

    private data class Admission(
        val effectId: String,
        val effectNonce: String,
        val leaseUntil: String,
        val expiresAt: String,
    )

    private fun requestAdmission(
        root: String,
        deviceId: String,
        pairingId: String,
        commandId: String,
        commandNonce: String,
        capabilityScope: String,
    ): Admission {
        val path = CommandReceiptContract.admissionPath(deviceId, commandId)
        val response = signedRuntimePost("$root$path", path, pairingId, "{}")
        requirePinnedServerIdentity(response)
        val signature = response.optString("admissionSignature")
        val admission = JSONObject(response.toString()).apply {
            remove("admissionSignature")
            remove("serverPublicKey")
            remove("serverKeyId")
        }
        require(verifyPinnedServerSignature("ADMISSION\n$admission", signature)) {
            "Admission signature verification failed."
        }
        require(admission.optString("commandId") == commandId) { "Admission command mismatch." }
        require(admission.optString("deviceId") == deviceId) { "Admission device mismatch." }
        require(admission.optString("commandNonce") == commandNonce) { "Admission nonce mismatch." }
        require(admission.optString("capabilityScope") == capabilityScope) { "Admission scope mismatch." }
        val effectId = admission.optString("effectId").trim()
        val effectNonce = admission.optString("effectNonce").trim()
        val admissionLease = admission.optString("leaseUntil").trim()
        val admissionExpiry = admission.optString("expiresAt").trim()
        require(effectId.isNotBlank() && effectNonce.isNotBlank()) { "Admission effect identity missing." }
        require(Instant.parse(admissionLease).isAfter(Instant.now())) { "Admission lease expired." }
        require(Instant.parse(admissionExpiry).isAfter(Instant.now())) { "Admission expired." }
        return Admission(effectId, effectNonce, admissionLease, admissionExpiry)
    }

    private fun commitEffect(
        root: String,
        deviceId: String,
        pairingId: String,
        intent: CommandReceiptContract.EffectIntent,
        capabilityScope: String,
    ) {
        val path = CommandReceiptContract.commitPath(deviceId, intent.commandId)
        val body = JSONObject()
            .put("effectId", intent.effectId)
            .put("effectNonce", intent.effectNonce)
            .toString()
        val response = signedRuntimePost("$root$path", path, pairingId, body)
        requirePinnedServerIdentity(response)
        val signature = response.optString("commitSignature")
        val commit = JSONObject(response.toString()).apply {
            remove("commitSignature")
            remove("serverPublicKey")
            remove("serverKeyId")
        }
        require(verifyPinnedServerSignature("COMMIT\n$commit", signature)) {
            "Commit signature verification failed."
        }
        require(commit.optString("effectId") == intent.effectId) { "Commit effect mismatch." }
        require(commit.optString("effectNonce") == intent.effectNonce) { "Commit nonce mismatch." }
        require(commit.optString("commandId") == intent.commandId) { "Commit command mismatch." }
        require(commit.optString("deviceId") == deviceId) { "Commit device mismatch." }
        require(commit.optString("capabilityScope") == capabilityScope) { "Commit scope mismatch." }
        require(Instant.parse(commit.optString("leaseUntil")).isAfter(Instant.now())) { "Commit lease expired." }
        require(Instant.parse(commit.optString("expiresAt")).isAfter(Instant.now())) { "Commit expired." }
    }

    private fun revalidateBeforeCommit(admission: Admission, commandExpiresAt: String, commandLeaseUntil: String) {
        require(!prefs.getBoolean("emergency_stop", true)) { "Emergency Stop became active before commit." }
        require(prefs.getBoolean("master_autonomy", false)) { "Master Autonomy was disabled before commit." }
        require(hasNotificationPermission()) { "Notification permission was revoked before commit." }
        val now = Instant.now()
        require(Instant.parse(commandExpiresAt).isAfter(now)) { "Command expired before commit." }
        require(Instant.parse(commandLeaseUntil).isAfter(now)) { "Command lease expired before commit." }
        require(Instant.parse(admission.expiresAt).isAfter(now)) { "Admission expired before commit." }
        require(Instant.parse(admission.leaseUntil).isAfter(now)) { "Admission lease expired before commit." }
    }

    private fun rejectDeliveredCommand(
        root: String,
        deviceId: String,
        pairingId: String,
        commandId: String,
        detail: String,
    ) {
        try {
            sendAck(root, deviceId, pairingId, commandId, "rejected", detail)
        } catch (_: Exception) {
            // Server remains authoritative. A failed rejection receipt never authorizes execution.
        }
    }

    private fun sendAck(
        root: String,
        deviceId: String,
        pairingId: String,
        commandId: String,
        status: String,
        detail: String,
    ) {
        val body = JSONObject().put("status", status).put("detail", detail).toString()
        val path = CommandReceiptContract.ackPath(deviceId, commandId)
        val response = signedRuntimePost("$root$path", path, pairingId, body)
        requirePinnedServerIdentity(response)
    }

    private fun persistEffectIntent(intent: CommandReceiptContract.EffectIntent) {
        require(
            prefs.edit().putString(
                CommandReceiptContract.effectIntentKey(intent.commandId),
                CommandReceiptContract.encodeEffectIntent(intent)
            ).commit()
        ) { "Unable to persist authoritative effect intent." }
    }

    private fun loadEffectIntent(commandId: String): CommandReceiptContract.EffectIntent? {
        val raw = prefs.getString(CommandReceiptContract.effectIntentKey(commandId), null) ?: return null
        return CommandReceiptContract.decodeEffectIntent(raw)
    }

    private fun clearEffectIntent(commandId: String) {
        require(prefs.edit().remove(CommandReceiptContract.effectIntentKey(commandId)).commit()) {
            "Unable to clear completed effect intent."
        }
    }

    private fun reconcileUncertainEffects() {
        val entries = prefs.all.entries.filter { it.key.startsWith("pending_effect_intent_") }
        for ((key, rawValue) in entries) {
            val raw = rawValue as? String ?: continue
            val intent = CommandReceiptContract.decodeEffectIntent(raw) ?: continue
            if (intent.phase != "execution_started") continue
            if (prefs.contains(CommandReceiptContract.outboxKey(intent.commandId))) continue
            val result = CommandReceiptContract.PendingResult(
                commandId = intent.commandId,
                commandNonce = intent.commandNonce,
                status = "unknown_requires_review",
                detail = "Bridge restarted after authoritative effect commit. Replay is blocked because the local effect outcome cannot be proven.",
                createdAtMs = System.currentTimeMillis(),
                resultCode = "effect_result_ambiguous_after_restart",
                effectBlocked = false,
                effectId = intent.effectId,
                effectNonce = intent.effectNonce
            )
            persistPendingResult(result)
            prefs.edit().putBoolean("command_nonce_${intent.commandNonce}", true).commit()
            // Keep intent until the terminal result is successfully delivered.
            if (key.isBlank()) throw IllegalStateException("Invalid effect intent key")
        }
    }

    private fun persistUnknownResultFromStoredIntent(commandId: String, commandNonce: String, effectKey: String) {
        val raw = prefs.getString(effectKey, null) ?: return
        val intent = CommandReceiptContract.decodeEffectIntent(raw) ?: return
        if (intent.phase != "execution_started") return
        if (prefs.contains(CommandReceiptContract.outboxKey(commandId))) return
        persistPendingResult(
            CommandReceiptContract.PendingResult(
                commandId = commandId,
                commandNonce = commandNonce,
                status = "unknown_requires_review",
                detail = "A committed effect already exists locally. Replay was denied and operator review is required.",
                createdAtMs = System.currentTimeMillis(),
                resultCode = "effect_replay_blocked",
                effectBlocked = false,
                effectId = intent.effectId,
                effectNonce = intent.effectNonce
            )
        )
        prefs.edit().putBoolean("command_nonce_$commandNonce", true).commit()
    }

    private fun persistPendingResult(result: CommandReceiptContract.PendingResult) {
        require(result.effectId != null && result.effectNonce != null && result.resultCode != null) {
            "Authoritative effect result tokens are required."
        }
        val key = CommandReceiptContract.outboxKey(result.commandId)
        val encoded = CommandReceiptContract.encodePendingResult(result)
        require(prefs.edit().putString(key, encoded).commit()) {
            "Unable to persist terminal command result."
        }
    }

    private fun retryPendingResults(root: String, deviceId: String, pairingId: String) {
        val pending = prefs.all.entries
            .filter { it.key.startsWith("pending_command_result_") }
            .sortedBy { it.key }

        for ((key, raw) in pending) {
            val encoded = raw as? String
                ?: throw IllegalStateException("Pending result outbox entry invalid: $key")
            val result = CommandReceiptContract.decodePendingResult(encoded)
                ?: throw IllegalStateException("Pending result outbox decode failed: $key")
            // Legacy result records have no authoritative effect token and cannot be replayed safely.
            if (result.effectId == null || result.effectNonce == null || result.resultCode == null) continue
            deliverPendingResult(root, deviceId, pairingId, result)
        }
    }

    private fun deliverPendingResult(
        root: String,
        deviceId: String,
        pairingId: String,
        result: CommandReceiptContract.PendingResult,
    ) {
        val effectId = requireNotNull(result.effectId)
        val effectNonce = requireNotNull(result.effectNonce)
        val resultCode = requireNotNull(result.resultCode)
        val body = JSONObject()
            .put("status", result.status)
            .put("resultCode", resultCode)
            .put("detail", result.detail)
            .put("effectBlocked", result.effectBlocked)
            .put("effectId", effectId)
            .put("effectNonce", effectNonce)
            .toString()
        val path = CommandReceiptContract.resultPath(deviceId, result.commandId)
        val response = signedRuntimePost("$root$path", path, pairingId, body)
        requirePinnedServerIdentity(response)
        require(prefs.edit().remove(CommandReceiptContract.outboxKey(result.commandId)).commit()) {
            "Result delivered but local outbox cleanup failed."
        }
        val intentKey = CommandReceiptContract.effectIntentKey(result.commandId)
        if (prefs.contains(intentKey)) {
            require(prefs.edit().remove(intentKey).commit()) { "Unable to clear delivered effect intent." }
        }
    }

    private fun executeNotification(commandId: String, payload: JSONObject): String {
        val title = payload.optString("title").trim()
        val body = payload.optString("body").trim()
        require(title.isNotBlank() && body.isNotBlank()) { "Notification payload invalid." }
        showLocalNotification(commandId, title, body)
        return "Benachrichtigung lokal ausgeführt."
    }

    private fun verifyCommandEnvelope(command: JSONObject, expectedDeviceId: String): Boolean {
        return try {
            requirePinnedServerIdentity(command)
            if (command.optString("deviceId") != expectedDeviceId) return false
            val signedKeys = listOf(
                "commandId",
                "deviceId",
                "workItemId",
                "type",
                "payload",
                "capabilityScope",
                "requiredGate",
                "factoryEvidenceOnly",
                "factoryContext",
                "expiresAt",
                "nonce",
                "sequence",
                "cursor",
                "leaseUntil",
            )
            if (signedKeys.any { !command.has(it) }) return false
            val envelope = JSONObject()
            for (key in signedKeys) envelope.put(key, command.get(key))
            val signature = command.optString("envelopeSignature")
            signature.isNotBlank() && verifyPinnedServerSignature("COMMAND\n$envelope", signature)
        } catch (_: Exception) {
            false
        }
    }

    private fun requirePinnedServerIdentity(response: JSONObject) {
        val pinnedKey = prefs.getString("server_public_key", null)?.trim().orEmpty()
        require(pinnedKey.isNotBlank()) { "Pinned server public key is unavailable." }
        require(response.optString("serverPublicKey") == pinnedKey) { "Server public key changed; re-pairing is required." }
        val pinnedId = prefs.getString("server_key_id", null)?.trim().orEmpty()
        if (pinnedId.isNotBlank()) {
            require(response.optString("serverKeyId") == pinnedId) { "Server key id changed; re-pairing is required." }
        }
    }

    private fun verifyPinnedServerSignature(message: String, signature: String): Boolean {
        val publicKey = prefs.getString("server_public_key", null)?.trim().orEmpty()
        if (publicKey.isBlank() || signature.isBlank()) return false
        return try {
            val key = java.security.KeyFactory.getInstance("Ed25519").generatePublic(
                java.security.spec.X509EncodedKeySpec(Base64.decode(publicKey, Base64.DEFAULT))
            )
            val verifier = Signature.getInstance("Ed25519")
            verifier.initVerify(key)
            verifier.update(message.toByteArray(Charsets.UTF_8))
            verifier.verify(Base64.decode(signature, Base64.DEFAULT))
        } catch (_: Exception) {
            false
        }
    }

    private fun signedRuntimeGet(url: String, canonicalPath: String, pairingId: String): JSONObject =
        signedRuntimeRequest("GET", url, canonicalPath, pairingId, "")

    private fun signedRuntimePost(url: String, canonicalPath: String, pairingId: String, body: String): JSONObject =
        signedRuntimeRequest("POST", url, canonicalPath, pairingId, body)

    private fun signedRuntimeRequest(
        method: String,
        url: String,
        canonicalPath: String,
        pairingId: String,
        body: String,
    ): JSONObject {
        require(prefs.getString("pairing_id", null) == pairingId) { "Runtime pairing state changed." }
        val deviceId = prefs.getString("device_id", null)
            ?: throw IllegalStateException("Missing device id")
        val origin = ControlPlaneResolver.resolve(prefs.getString("base_url", null))
        val path = canonicalPath.removePrefix("/api").substringBefore('?')
        val timestamp = System.currentTimeMillis().toString()
        val counter = reserveBridgeCounter().toString()
        val requestId = UUID.randomUUID().toString()
        val canonical = listOf(
            method.uppercase(Locale.ROOT),
            path,
            timestamp,
            counter,
            requestId,
            body,
            origin,
        ).joinToString("\n")
        val signature = sign(canonical)

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 15000
            doOutput = method == "POST"
            setRequestProperty("Accept", "application/json")
            if (method == "POST") setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-PIGA-Device-Id", deviceId)
            setRequestProperty("X-PIGA-Timestamp", timestamp)
            setRequestProperty("X-PIGA-Counter", counter)
            setRequestProperty("X-PIGA-Request-Id", requestId)
            setRequestProperty("X-PIGA-Signature", signature)
            setRequestProperty("X-PIGA-Bridge-Version", bridgeContractVersion)
            setRequestProperty("X-PIGA-Bridge-Version-Code", bridgeContractVersionCode.toString())
            setRequestProperty("X-PIGA-Control-Plane-Origin", origin)
        }
        if (method == "POST") {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        return readJsonResponse(connection)
    }

    private fun reserveBridgeCounter(): Long = synchronized(bridgeCounterLock) {
        val current = prefs.getLong("bridge_counter", 0L)
        val next = current + 1L
        require(next > current && prefs.edit().putLong("bridge_counter", next).commit()) {
            "Unable to persist bridge replay counter."
        }
        next
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
        val entry = ks.getEntry(alias, null) as KeyStore.PrivateKeyEntry
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(entry.privateKey)
        signer.update(canonical.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(signer.sign(), Base64.NO_WRAP)
    }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel("piga_runtime", "PIGA Bridge Runtime", NotificationManager.IMPORTANCE_LOW)
            )
            manager.createNotificationChannel(
                NotificationChannel("piga_commands", "PIGA Local Commands", NotificationManager.IMPORTANCE_DEFAULT)
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
