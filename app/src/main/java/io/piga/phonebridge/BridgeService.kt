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
        if (running.compareAndSet(false, true)) {
            Thread { pollLoop() }.start()
        }
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
                val path = "/bridge/devices/$deviceId/commands"
                val response = signedRequest("GET", "$root/api$path", path, "")
                val count = response.optJSONArray("commands")?.length() ?: 0
                prefs.edit()
                    .putLong("last_poll_ms", System.currentTimeMillis())
                    .putString("runtime_status", "ONLINE commands=$count")
                    .apply()
                updateNotification("PIGA Bridge online • pending $count")
                // Commands remain fail-closed until the signed governance snapshot
                // endpoint is available and locally revalidated.
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

    private fun signedRequest(method: String, url: String, canonicalPath: String, body: String): JSONObject {
        val timestamp = System.currentTimeMillis().toString()
        val counter = synchronized(prefs) {
            val next = prefs.getLong("request_counter", 0L) + 1L
            prefs.edit().putLong("request_counter", next).commit()
            next
        }.toString()
        val requestId = UUID.randomUUID().toString()
        val canonical = "$method\n$canonicalPath\n$timestamp\n$counter\n$requestId\n$body"

        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val entry = ks.getEntry(alias, null) as KeyStore.PrivateKeyEntry
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(entry.privateKey)
        signer.update(canonical.toByteArray(Charsets.UTF_8))
        val signature = Base64.encodeToString(signer.sign(), Base64.NO_WRAP)
        val deviceId = prefs.getString("device_id", "") ?: ""

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("x-piga-device-id", deviceId)
            setRequestProperty("x-piga-timestamp", timestamp)
            setRequestProperty("x-piga-counter", counter)
            setRequestProperty("x-piga-request-id", requestId)
            setRequestProperty("x-piga-signature", signature)
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

    private fun statusNotification(text: String): Notification {
        return if (Build.VERSION.SDK_INT >= 26) {
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
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(2001, statusNotification(text))
    }
}
