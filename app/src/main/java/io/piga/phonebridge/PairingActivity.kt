package io.piga.phonebridge

import android.app.Activity
import android.content.Intent
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
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID

class PairingActivity : Activity() {
    private val alias = "piga_phone_bridge_device_key"
    private val defaultBaseUrl = "https://d62aa607-3fcc-4f10-b437-8dd3326c4f3f-00-1iesyu3mfpkl2.janeway.replit.dev"
    private val prefs by lazy { getSharedPreferences("piga_bridge", MODE_PRIVATE) }

    private lateinit var status: TextView
    private lateinit var baseUrl: EditText
    private lateinit var bootstrapCode: EditText
    private lateinit var pairButton: Button
    private lateinit var confirmButton: Button
    private var challengeId: String? = null
    private var signingPayload: String? = null
    private var pairingCode: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureKey()

        val deviceId = ensureDeviceId()
        val publicKey = getPublicKeyBase64()

        status = TextView(this).apply {
            textSize = 18f
            gravity = Gravity.CENTER_HORIZONTAL
            text = "PIGA Pairing\nStatus: ${if (prefs.getBoolean("paired", false)) "PAIRED" else "NOT PAIRED"}"
            setPadding(16, 16, 16, 24)
        }
        baseUrl = EditText(this).apply {
            hint = "Control Plane URL"
            setText(prefs.getString("base_url", defaultBaseUrl) ?: defaultBaseUrl)
            setSingleLine(true)
        }
        val device = EditText(this).apply {
            hint = "Device ID"
            setText(deviceId)
            isFocusable = false
            isCursorVisible = false
            setTextIsSelectable(true)
        }
        val key = EditText(this).apply {
            hint = "Android Keystore Public Key"
            setText(publicKey)
            isFocusable = false
            isCursorVisible = false
            setTextIsSelectable(true)
            minLines = 3
        }
        bootstrapCode = EditText(this).apply {
            hint = "One-time bootstrap code"
            setSingleLine(true)
        }
        pairButton = Button(this).apply {
            text = "PAIR DEVICE"
            setOnClickListener { startPairing() }
        }
        confirmButton = Button(this).apply {
            text = "CONFIRM PAIRING"
            isEnabled = false
            setOnClickListener { confirmPairing() }
        }
        val backButton = Button(this).apply {
            text = "BACK TO BRIDGE"
            setOnClickListener { startActivity(Intent(this@PairingActivity, MainActivity::class.java)); finish() }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(36, 48, 36, 72)
            addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(baseUrl, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(device, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(key, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(bootstrapCode, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(pairButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(confirmButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(backButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
    }

    private fun startPairing() {
        val code = bootstrapCode.text.toString().trim()
        if (code.isBlank()) {
            status.text = "Pairing blocked: enter a fresh Owner Bootstrap code."
            return
        }
        pairButton.isEnabled = false
        confirmButton.isEnabled = false
        status.text = "Requesting signed pairing challenge…"
        Thread {
            try {
                val root = baseUrl.text.toString().trim().removeSuffix("/")
                prefs.edit().putString("base_url", root).apply()
                val scopes = JSONArray()
                    .put("pocket.notification")
                    .put("pocket.clipboard.write")
                    .put("pocket.intent.url")
                    .put("pocket.tts")
                    .put("pocket.app.launch")
                    .put("pocket.share.text")
                    .put("pocket.orchestration.verify")
                val body = JSONObject()
                    .put("deviceId", ensureDeviceId())
                    .put("publicKey", getPublicKeyBase64())
                    .put("capabilities", JSONObject().put("scopes", scopes))
                    .put("bootstrapCode", code)
                val response = postJson("$root/api/bridge/pairing/challenge", body)
                challengeId = response.getString("challengeId")
                signingPayload = response.getString("signingPayload")
                pairingCode = response.getString("pairingCode")
                runOnUiThread {
                    status.text = "Challenge received. Pairing code: ${pairingCode ?: ""}\nTap CONFIRM PAIRING before expiry."
                    pairButton.isEnabled = true
                    confirmButton.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Pairing request failed: ${e.message ?: e.javaClass.simpleName}"
                    pairButton.isEnabled = true
                }
            }
        }.start()
    }

    private fun confirmPairing() {
        val cid = challengeId ?: return
        val payload = signingPayload ?: return
        val code = pairingCode ?: return
        confirmButton.isEnabled = false
        status.text = "Signing and confirming pairing…"
        Thread {
            try {
                val root = baseUrl.text.toString().trim().removeSuffix("/")
                val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                val entry = ks.getEntry(alias, null) as KeyStore.PrivateKeyEntry
                val signer = Signature.getInstance("SHA256withECDSA")
                signer.initSign(entry.privateKey)
                signer.update(payload.toByteArray(Charsets.UTF_8))
                val signature = Base64.encodeToString(signer.sign(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                val body = JSONObject()
                    .put("challengeId", cid)
                    .put("deviceId", ensureDeviceId())
                    .put("publicKey", getPublicKeyBase64())
                    .put("pairingCode", code)
                    .put("signature", signature)
                val response = postJson("$root/api/bridge/pairing/confirm", body)
                val pairingId = response.optString("pairingId")
                require(pairingId.isNotBlank()) { "Missing pairingId in confirmation response." }
                prefs.edit().putBoolean("paired", true).putString("pairing_id", pairingId).apply()
                runOnUiThread {
                    bootstrapCode.setText("")
                    status.text = "PAIRED. Starting governed bridge runtime…"
                    val service = Intent(this@PairingActivity, BridgeService::class.java)
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
                    confirmButton.isEnabled = false
                    pairButton.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Pairing confirmation failed: ${e.message ?: e.javaClass.simpleName}"
                    confirmButton.isEnabled = true
                }
            }
        }.start()
    }

    private fun ensureDeviceId(): String {
        val stored = prefs.getString("device_id", null)?.trim()
        if (!stored.isNullOrBlank()) return stored
        val created = UUID.randomUUID().toString()
        prefs.edit().putString("device_id", created).apply()
        return created
    }

    private fun ensureKey() {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(alias)) return
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        generator.initialize(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
        )
        generator.generateKeyPair()
    }

    private fun getPublicKeyBase64(): String {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return Base64.encodeToString(ks.getCertificate(alias).publicKey.encoded, Base64.NO_WRAP)
    }

    private fun postJson(url: String, body: JSONObject): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 15000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        connection.disconnect()
        if (code !in 200..299) throw IllegalStateException("HTTP $code ${text.take(240)}")
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }
}
