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
    private val prefs by lazy { getSharedPreferences("piga_bridge", MODE_PRIVATE) }

    private lateinit var status: TextView
    private lateinit var baseUrl: EditText
    private lateinit var pairingCodeInput: EditText
    private lateinit var challengeButton: Button
    private lateinit var confirmButton: Button
    private var pairingId: String? = null
    private var challenge: String? = null

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
            hint = "Control Plane URL (auto-discovered if blank)"
            setText(prefs.getString("base_url", "") ?: "")
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
        pairingCodeInput = EditText(this).apply {
            hint = "6-digit owner approval code"
            setSingleLine(true)
        }
        challengeButton = Button(this).apply {
            text = "REQUEST CHALLENGE"
            setOnClickListener { requestChallenge() }
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
            addView(pairingCodeInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(challengeButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(confirmButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(backButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
    }

    private fun requestChallenge() {
        challengeButton.isEnabled = false
        confirmButton.isEnabled = false
        status.text = "Resolving canonical control plane…"
        Thread {
            try {
                val fallback = baseUrl.text.toString().trim().removeSuffix("/").takeIf { it.isNotBlank() }
                val root = ControlPlaneResolver.resolve(fallback)
                require(root.startsWith("https://")) { "Control Plane must use HTTPS." }
                require(prefs.edit().putString("base_url", root).commit()) { "Unable to persist canonical control plane." }
                val body = JSONObject()
                    .put("deviceId", ensureDeviceId())
                    .put("publicKey", getPublicKeyBase64())
                    .put("keyAlgorithm", "EC-P256-SHA256")
                val response = postJson("$root/api/bridge/pairing/challenge", body)
                val pid = response.getString("pairingId")
                val chal = response.getString("challenge")
                require(pid.isNotBlank() && chal.isNotBlank()) { "Invalid challenge response." }
                pairingId = pid
                challenge = chal
                prefs.edit().putBoolean("paired", false).remove("pairing_id").apply()
                runOnUiThread {
                    baseUrl.setText(root)
                    status.text = "Challenge created via canonical control plane. Approve pairing in Pocket Enterprise, enter the 6-digit code here, then tap CONFIRM PAIRING."
                    challengeButton.isEnabled = true
                    confirmButton.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Challenge request failed: ${e.message ?: e.javaClass.simpleName}"
                    challengeButton.isEnabled = true
                }
            }
        }.start()
    }

    private fun confirmPairing() {
        val pid = pairingId ?: run {
            status.text = "Pairing blocked: request a fresh challenge first."
            return
        }
        val chal = challenge ?: run {
            status.text = "Pairing blocked: challenge missing."
            return
        }
        val code = pairingCodeInput.text.toString().trim()
        if (!code.matches(Regex("^\\d{6}$"))) {
            status.text = "Pairing blocked: enter the 6-digit owner approval code."
            return
        }

        confirmButton.isEnabled = false
        status.text = "Signing and confirming pairing…"
        Thread {
            try {
                val fallback = baseUrl.text.toString().trim().removeSuffix("/").takeIf { it.isNotBlank() }
                val root = ControlPlaneResolver.resolve(fallback)
                val signingPayload = "PAIR_CONFIRM\n$pid\n$chal"
                val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                val entry = ks.getEntry(alias, null) as KeyStore.PrivateKeyEntry
                val signer = Signature.getInstance("SHA256withECDSA")
                signer.initSign(entry.privateKey)
                signer.update(signingPayload.toByteArray(Charsets.UTF_8))
                val signature = Base64.encodeToString(signer.sign(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                val body = JSONObject()
                    .put("deviceId", ensureDeviceId())
                    .put("pairingId", pid)
                    .put("pairingCode", code)
                    .put("signature", signature)
                val response = postJson("$root/api/bridge/pairing/confirm", body)
                val confirmedPairingId = response.optString("pairingId", pid)
                require(confirmedPairingId == pid) { "Pairing confirmation returned unexpected pairingId." }
                require(
                    prefs.edit()
                        .putBoolean("paired", true)
                        .putString("pairing_id", pid)
                        .putString("base_url", root)
                        .commit()
                ) { "Unable to persist paired state." }
                runOnUiThread {
                    baseUrl.setText(root)
                    pairingCodeInput.setText("")
                    status.text = "PAIRED. Starting governed bridge runtime…"
                    val service = Intent(this@PairingActivity, BridgeService::class.java)
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
                    confirmButton.isEnabled = false
                    challengeButton.isEnabled = true
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
