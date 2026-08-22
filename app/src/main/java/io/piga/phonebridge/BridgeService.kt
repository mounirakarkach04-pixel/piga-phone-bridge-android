package io.piga.phonebridge

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
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
    private val ttsLock = Object()
    @Volatile private var ttsEngine: TextToSpeech? = null
    @Volatile private var ttsInitialized = false
    @Volatile private var ttsInitFailed = false

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
        synchronized(ttsLock) {
            ttsEngine?.stop()
            ttsEngine?.shutdown()
            ttsEngine = null
            ttsInitialized = false
            ttsInitFailed = false
        }
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

                val canonicalPath = "/api/bridge/devices/$deviceId/commands"
                val response = signedRuntimeGet("$root$canonicalPath", canonicalPath, pairingId)
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
        val emergencyStop = prefs.getBoolean("emergency_stop", false)
        val notificationPermission = hasNotificationPermission()
        val body = "{\"masterAutonomy\":$masterAutonomy,\"notificationPermission\":$notificationPermission,\"emergencyStop\":$emergencyStop}"
        val path = "/api/bridge/devices/$deviceId/safety"
        signedRuntimePost("$root$path", path, pairingId, body)
        prefs.edit()
            .putBoolean("notification_permission", notificationPermission)
            .putLong("last_safety_sync_ms", System.currentTimeMillis())
            .apply()
    }

    private fun processCommand(root: String, deviceId: String, pairingId: String, command: JSONObject) {
        val commandId = command.optString("commandId")
        val type = command.optString("type")
        val scope = command.optString("capabilityScope")
        val commandNonce = command.optString("nonce")
        val expiresAt = command.optString("expiresAt")
        val leaseUntil = command.optString("leaseUntil")
        val payload = command.optJSONObject("payload")

        val allowed = mapOf(
            "local_notification" to "pocket.notification",
            "clipboard_write" to "pocket.clipboard.write",
            "url_intent" to "pocket.intent.url",
            "text_to_speech" to "pocket.tts",
            "supported_app_launch" to "pocket.app.launch",
            "share_text" to "pocket.share.text",
            "orchestration_plan_verify" to "pocket.orchestration.verify"
        )

        if (commandId.isBlank() || commandNonce.isBlank() || expiresAt.isBlank() || leaseUntil.isBlank() || payload == null || allowed[type] != scope) {
            if (commandId.isNotBlank()) sendResult(root, deviceId, pairingId, commandId, "rejected", "Command schema or allowlist rejected.")
            return
        }

        if (prefs.getBoolean("command_nonce_$commandNonce", false)) {
            sendResult(root, deviceId, pairingId, commandId, "rejected", "Command nonce replay rejected.")
            return
        }

        val now = Instant.now()
        try {
            if (now.isAfter(Instant.parse(expiresAt))) {
                sendResult(root, deviceId, pairingId, commandId, "expired", "Command expired before execution.")
                return
            }
            if (now.isAfter(Instant.parse(leaseUntil))) {
                sendResult(root, deviceId, pairingId, commandId, "expired", "Command lease expired before execution.")
                return
            }
        } catch (_: Exception) {
            sendResult(root, deviceId, pairingId, commandId, "rejected", "Invalid expiry or lease timestamp.")
            return
        }

        val masterAutonomy = prefs.getBoolean("master_autonomy", false)
        val emergencyStop = prefs.getBoolean("emergency_stop", false)
        if (!masterAutonomy || emergencyStop) {
            sendResult(root, deviceId, pairingId, commandId, "rejected", "Local safety gate blocked execution.")
            return
        }
        if (type == "local_notification" && !hasNotificationPermission()) {
            sendResult(root, deviceId, pairingId, commandId, "rejected", "Notification permission missing.")
            return
        }

        sendAck(root, deviceId, pairingId, commandId, "accepted")
        prefs.edit().putBoolean("command_nonce_$commandNonce", true).apply()

        try {
            val detail = when (type) {
                "local_notification" -> executeNotification(commandId, payload)
                "clipboard_write" -> executeClipboard(payload)
                "url_intent" -> executeUrlIntent(payload)
                "text_to_speech" -> executeTextToSpeech(payload)
                "supported_app_launch" -> executeAppLaunch(payload)
                "share_text" -> executeShareText(payload)
                "orchestration_plan_verify" -> executeOrchestrationPlanVerify(payload)
                else -> throw IllegalStateException("Unsupported command type")
            }
            sendResult(root, deviceId, pairingId, commandId, "succeeded", detail)
        } catch (e: Exception) {
            sendResult(root, deviceId, pairingId, commandId, "failed", e.message ?: "Local capability failed.")
        }
    }

    private fun executeNotification(commandId: String, payload: JSONObject): String {
        val title = payload.optString("title").trim()
        val body = payload.optString("body").trim()
        require(title.isNotBlank() && body.isNotBlank()) { "Notification payload invalid." }
        showLocalNotification(commandId, title, body)
        return "Benachrichtigung lokal ausgeführt."
    }

    private fun executeClipboard(payload: JSONObject): String {
        val text = payload.optString("text")
        require(text.isNotBlank() && text.length <= 10000) { "Clipboard payload invalid." }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("PIGA Pocket Enterprise", text))
        return "Clipboard lokal aktualisiert."
    }

    private fun executeUrlIntent(payload: JSONObject): String {
        val raw = payload.optString("url").trim()
        require(raw.length in 1..2048) { "URL payload invalid." }
        val uri = Uri.parse(raw)
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        require(scheme == "https" || scheme == "http") { "Only http/https URL intents are admitted." }
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        require(intent.resolveActivity(packageManager) != null) { "No handler available for URL." }
        startActivity(intent)
        return "URL/Deep-Link Intent geöffnet."
    }

    private fun executeTextToSpeech(payload: JSONObject): String {
        val text = payload.optString("text").trim()
        require(text.isNotBlank() && text.length <= 4000) { "TTS payload invalid." }

        val engine = synchronized(ttsLock) {
            if (ttsEngine == null) {
                ttsInitialized = false
                ttsInitFailed = false
                ttsEngine = TextToSpeech(applicationContext) { status ->
                    synchronized(ttsLock) {
                        ttsInitialized = status == TextToSpeech.SUCCESS
                        ttsInitFailed = status != TextToSpeech.SUCCESS
                        ttsLock.notifyAll()
                    }
                }
            }

            val deadline = System.currentTimeMillis() + 5000L
            while (!ttsInitialized && !ttsInitFailed) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0L) break
                ttsLock.wait(remaining)
            }

            if (!ttsInitialized) {
                ttsEngine?.shutdown()
                ttsEngine = null
                ttsInitFailed = false
                throw IllegalStateException("Text-to-speech engine unavailable.")
            }
            ttsEngine ?: throw IllegalStateException("Text-to-speech engine unavailable.")
        }

        engine.language = Locale.getDefault()
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "piga-${UUID.randomUUID()}")
        require(result != TextToSpeech.ERROR) { "Text-to-speech failed." }
        return "Text-to-Speech lokal angestoßen."
    }

    private fun executeAppLaunch(payload: JSONObject): String {
        val packageName = payload.optString("packageName").trim()
        require(packageName.matches(Regex("^[A-Za-z0-9_.]{3,200}$"))) { "Package name invalid." }
        val launch = packageManager.getLaunchIntentForPackage(packageName)
            ?: throw IllegalStateException("App not installed or not launchable.")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launch)
        return "Unterstützte App gestartet."
    }

    private fun executeShareText(payload: JSONObject): String {
        val text = payload.optString("text").trim()
        require(text.isNotBlank() && text.length <= 10000) { "Share payload invalid." }
        val targetPackage = payload.optString("packageName").trim()
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            if (targetPackage.isNotBlank()) setPackage(targetPackage)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        require(send.resolveActivity(packageManager) != null) { "No admitted share target available." }
        startActivity(send)
        return if (targetPackage.isBlank()) "Share-Intent geöffnet." else "Share-Intent an Ziel-App geöffnet."
    }

    private fun executeOrchestrationPlanVerify(payload: JSONObject): String {
        val plan = payload.optJSONObject("plan")
            ?: throw IllegalArgumentException("Orchestration plan missing.")
        val expectedSha256 = payload.optString("planSha256").trim().lowercase(Locale.ROOT)
        require(expectedSha256.matches(Regex("^[0-9a-f]{64}$"))) { "Expected plan SHA-256 missing or invalid." }

        val result = OrchestrationPlanVerifier.verify(plan)
        require(result.planSha256 == expectedSha256) { "Orchestration plan SHA-256 mismatch." }
        require(result.admitted) { "Orchestration plan blocked: ${result.reason}" }

        val receipt = OrchestrationPlanVerifier.receiptJson(result).apply {
            put("expectedPlanSha256", expectedSha256)
            put("hashMatched", true)
            put("capabilityScope", "pocket.orchestration.verify")
        }
        return receipt.toString()
    }

    private fun sendAck(root: String, deviceId: String, pairingId: String, commandId: String, status: String) {
        val body = "{\"status\":\"$status\"}"
        val canonicalPath = "/api/bridge/devices/$deviceId/commands/$commandId"
        val url = "$root/api/bridge/devices/$deviceId/commands/$commandId/ack"
        signedRuntimePost(url, canonicalPath, pairingId, body)
    }

    private fun sendResult(root: String, deviceId: String, pairingId: String, commandId: String, status: String, detail: String) {
        val safeDetail = JSONObject.quote(detail)
        val body = "{\"status\":\"$status\",\"detail\":$safeDetail}"
        val canonicalPath = "/api/bridge/devices/$deviceId/commands/$commandId"
        val url = "$root/api/bridge/devices/$deviceId/commands/$commandId/result"
        signedRuntimePost(url, canonicalPath, pairingId, body)
    }

    private fun signedRuntimeGet(url: String, canonicalPath: String, pairingId: String): JSONObject {
        val timestamp = (System.currentTimeMillis() / 1000L).toString()
        val nonce = freshNonce()
        val cursor = ""
        val limit = "20"
        val canonical = listOf(
            "PIGA_PHONE_BRIDGE_RUNTIME_V1", "GET", canonicalPath, pairingId,
            timestamp, nonce, cursor, limit
        ).joinToString("\n")
        val signature = sign(canonical)

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-PIGA-Bridge-Pairing-Id", pairingId)
            setRequestProperty("X-PIGA-Bridge-Timestamp", timestamp)
            setRequestProperty("X-PIGA-Bridge-Nonce", nonce)
            setRequestProperty("X-PIGA-Bridge-Signature", signature)
        }
        return readJsonResponse(connection)
    }

    private fun signedRuntimePost(url: String, canonicalPath: String, pairingId: String, body: String): JSONObject {
        val timestamp = (System.currentTimeMillis() / 1000L).toString()
        val nonce = freshNonce()
        val canonical = listOf(
            "PIGA_PHONE_BRIDGE_RUNTIME_V1", "POST", canonicalPath, pairingId,
            timestamp, nonce, body
        ).joinToString("\n")
        val signature = sign(canonical)

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 15000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-PIGA-Bridge-Pairing-Id", pairingId)
            setRequestProperty("X-PIGA-Bridge-Timestamp", timestamp)
            setRequestProperty("X-PIGA-Bridge-Nonce", nonce)
            setRequestProperty("X-PIGA-Bridge-Signature", signature)
        }
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return readJsonResponse(connection)
    }

    private fun readJsonResponse(connection: HttpURLConnection): JSONObject {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        connection.disconnect()
        if (code !in 200..299) throw IllegalStateException("HTTP $code ${text.take(180)}")
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun sign(canonical: String): String {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val entry = ks.getEntry(alias, null) as KeyStore.PrivateKeyEntry
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(entry.privateKey)
        signer.update(canonical.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(signer.sign(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun freshNonce(): String = UUID.randomUUID().toString().replace("-", "")

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
