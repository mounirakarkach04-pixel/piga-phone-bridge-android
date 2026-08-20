package io.piga.phonebridge

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID

class MainActivity : Activity() {
    private val alias = "piga_phone_bridge_device_key"
    private val currentBaseUrl = "https://d62aa607-3fcc-4f10-b437-8dd3326c4f3f-00-1iesyu3mfpkl2.janeway.replit.dev"
    private val legacyBaseHost = "ee08874a-6e9f-4d86-9942-9371a86f6c3e-00-3myurbngr26bi.janeway.replit.dev"
    private val prefs by lazy { getSharedPreferences("piga_bridge", MODE_PRIVATE) }
    private lateinit var status: TextView
    private lateinit var baseUrl: EditText
    private lateinit var pairButton: Button
    private lateinit var confirmButton: Button

    private var pairingId: String? = null
    private var challenge: String? = null
    private var pairingCode: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureKey()
        ensureNotifications()

        status = TextView(this).apply {
            text = if (prefs.getBoolean("paired", false)) {
                "PIGA Phone Bridge\n\nStatus: PAIRED\nRuntime starting…"
            } else {
                "PIGA Phone Bridge\n\nDevice identity ready in Android Keystore.\nStatus: NOT PAIRED"
            }
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 24)
        }

        baseUrl = EditText(this).apply {
            hint = "Bridge base URL"
            setText(resolveBaseUrl())
            setSingleLine(true)
        }

        pairButton = Button(this).apply {
            text = "Pair device"
            setOnClickListener { startPairing() }
        }

        confirmButton = Button(this).apply {
            text = "Confirm pairing"
            isEnabled = false
            setOnClickListener { confirmPairing() }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(36, 80, 36, 36)
            addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(baseUrl, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(pairButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(confirmButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(layout)

        if (prefs.getBoolean("paired", false)) {
            startBridgeRuntime()
            Thread {
                Thread.sleep(1200)
                runOnUiThread { refreshRuntimeStatus() }
            }.start()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized && prefs.getBoolean("paired", false)) refreshRuntimeStatus()
    }

    private fun resolveBaseUrl(): String {
        val stored = prefs.getString("base_url", null)?.trim()?.removeSuffix("/")
        if (stored.isNullOrBlank() || stored.contains(legacyBaseHost)) {
            prefs.edit().putString("base_url", currentBaseUrl).apply()
            return currentBaseUrl
        }
        return stored
    }

    private fun refreshRuntimeStatus() {
        val deviceId = prefs.getString("device_id", "") ?: ""
        val runtime = prefs.getString("runtime_status", "STARTING") ?: "STARTING"
        val lastPoll = prefs.getLong("last_poll_ms", 0L)
        status.text = "PIGA Phone Bridge\n\nStatus: PAIRED\nRuntime: $runtime\nLast poll: ${if (lastPoll > 0) lastPoll else "pending"}\nDevice: $deviceId"
    }

    private fun startPairing() {
        pairButton.isEnabled = false
        confirmButton.isEnabled = false
        status.text = "Requesting pairing challenge…"
        Thread {
            try {
                val root = baseUrl.text.toString().trim().removeSuffix("/")
                prefs.edit().putString("base_url", root).apply()
                val deviceId = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
                    prefs.edit().putString("device_id", it).apply()
                }
                val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                val publicKey = ks.getCertificate(alias).publicKey.encoded
                val body = JSONObject()
                    .put("deviceId", deviceId)
                    .put("publicKey", Base64.encodeToString(publicKey, Base64.NO_WRAP))
                    .put("keyAlgorithm", "EC-P256-SHA256")

                val response = postJson("$root/api/bridge/pairing/challenge", body)
                pairingId = response.getString("pairingId")
                challenge = response.getString("challenge")
                pairingCode = response.optString("pairingCode")
                val serverKey = response.optString("serverPublicKey")
                if (serverKey.isNotBlank()) prefs.edit().putString("server_public_key", serverKey).apply()
                runOnUiThread {
                    status.text = "Pairing challenge received.\nCode: ${pairingCode ?: ""}\nConfirm within 10 minutes."
                    pairButton.isEnabled = true
                    confirmButton.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Pairing request failed:\n${e.message ?: e.javaClass.simpleName}"
                    pairButton.isEnabled = true
                }
            }
        }.start()
    }

    private fun confirmPairing() {
        val pid = pairingId ?: return
        val ch = challenge ?: return
        val code = pairingCode ?: return
        confirmButton.isEnabled = false
        status.text = "Signing and confirming pairing…"
        Thread {
            try {
                val root = baseUrl.text.toString().trim().removeSuffix("/")
                val deviceId = prefs.getString("device_id", "") ?: ""
                val message = "PAIR_CONFIRM\n$pid\n$ch"
                val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                val entry = ks.getEntry(alias, null) as KeyStore.PrivateKeyEntry
                val signer = Signature.getInstance("SHA256withECDSA")
                signer.initSign(entry.privateKey)
                signer.update(message.toByteArray(Charsets.UTF_8))
                val sig = Base64.encodeToString(signer.sign(), Base64.NO_WRAP)

                val body = JSONObject()
                    .put("deviceId", deviceId)
                    .put("pairingId", pid)
                    .put("pairingCode", code)
                    .put("signature", sig)
                val response = postJson("$root/api/bridge/pairing/confirm", body)
                val serverKey = response.optString("serverPublicKey")
                prefs.edit()
                    .putBoolean("paired", true)
                    .putLong("request_counter", 0L)
                    .apply()
                if (serverKey.isNotBlank()) prefs.edit().putString("server_public_key", serverKey).apply()
                runOnUiThread {
                    status.text = "PIGA Phone Bridge\n\nPAIRED\nStarting signed runtime…\nDevice: $deviceId"
                    pairButton.isEnabled = true
                    confirmButton.isEnabled = false
                    startBridgeRuntime()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Pairing confirmation failed:\n${e.message ?: e.javaClass.simpleName}"
                    confirmButton.isEnabled = true
                }
            }
        }.start()
    }

    private fun startBridgeRuntime() {
        val intent = Intent(this, BridgeService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        Thread {
            Thread.sleep(1600)
            runOnUiThread { refreshRuntimeStatus() }
        }.start()
    }

    private fun postJson(url: String, body: JSONObject): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 15000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        connection.disconnect()
        if (code !in 200..299) throw IllegalStateException("HTTP $code ${text.take(240)}")
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun ensureKey() {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(alias)) return
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()
        generator.initialize(spec)
        generator.generateKeyPair()
    }

    private fun ensureNotifications() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("piga_bridge", "PIGA Phone Bridge", NotificationManager.IMPORTANCE_DEFAULT))
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }
}
