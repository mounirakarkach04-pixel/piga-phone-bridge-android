package io.piga.phonebridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.Signature
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class BridgeService : Service() {
    private val running = AtomicBoolean(false)
    private val alias = "piga_phone_bridge_device_key"
    private val prefs by lazy { getSharedPreferences("piga_bridge", MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
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
                val canonicalPath = "/api/bridge/devices/$deviceId/commands"
                val response = signedRuntimeGet("$root$canonicalPath", canonicalPath, pairingId)
                val count = response.optJSONArray("commands")?.length() ?: 0
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

    private fun signedRuntimeGet(url: String, canonicalPath: String, pairingId: String): JSONObject {
        val timestamp = (System.currentTimeMillis() / 1000L).toString()
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val cursor = ""
        val limit = "20"
        val canonical = listOf(
            "PIGA_PHONE_BRIDGE_RUNTIME_V1",
            "GET",
            canonicalPath,
            pairingId,
            timestamp,
            nonce,
            cursor,
            limit
        ).joinToString("\n")

        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val entry = ks.getEntry(alias, null) as KeyStore.PrivateKeyEntry
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(entry.privateKey)
        signer.update(canonical.toByteArray(Charsets.UTF_8))
        val signature = Base64.encodeToString(
            signer.sign(),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )

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
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        connection.disconnect()
        if (code !in 200..299) throw IllegalStateException("HTTP $code ${text.take(180)}")
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel("piga_runtime", "PIGA Bridge Runtime", NotificationManager.IMPORTANCE_LOW)
            )
        }
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
