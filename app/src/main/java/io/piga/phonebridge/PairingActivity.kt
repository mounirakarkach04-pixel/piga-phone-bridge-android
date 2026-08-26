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
import java.time.Instant
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
    private var challengeExpiresAt: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureKey()
        restorePendingPairing()

        val deviceId = ensureDeviceId()
        val publicKey = getPublicKeyBase64()

        status = TextView(this).apply {
            textSize = 18f
            gravity = Gravity.CENTER_HORIZONTAL
            text = when {
                prefs.getBoolean("paired", false) -> "PIGA Pairing\nStatus: PAIRED"
                pairingId != null -> "PIGA Pairing\nStatus: APPROVAL PENDING"
                else -> "PIGA Pairing\nStatus: NOT PAIRED"
            }
            setPadding(16, 16, 16, 24)
        }
        baseUrl = EditText(this).apply {
            hint = "Secure control plane"
            setText(prefs.getString("base_url", ControlPlaneResolver.CANONICAL_CONTROL_PLANE) ?: ControlPlaneResolver.CANONICAL_CONTROL_PLANE)
            isFocusable = false
            isCursorVisible = false
            setTextIsSelectable(true)
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
            hint = "6-digit approval code (fallback)"
            setSingleLine(true)
        }
        challengeButton = Button(this).apply {
            text = if (pairingId == null) "PAIR THIS PHONE" else "REQUEST FRESH PAIRING"
            setOnClickListener { requestChallenge() }
        }
        confirmButton = Button(this).apply {
            text = "CONFIRM WITH CODE"
            isEnabled = pairingId != null
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

        handlePairingIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePairingIntent(intent)
    }

    private fun requestChallenge() {
        challengeButton.isEnabled = false
        confirmButton.isEnabled = false
        status.text = "Connecting securely to PIGA…"
        Thread {
            try {
                val root = ControlPlaneResolver.resolve(ControlPlaneResolver.CANONICAL_CONTROL_PLANE)
                require(prefs.edit().putString("base_url", root).commit()) { "Unable to persist canonical control plane." }
                val body = JSONObject()
                    .put("deviceId", ensureDeviceId())
                    .put("publicKey", getPublicKeyBase64())
                    .put("keyAlgorithm", "EC-P256-SHA256")
                val response = postJson("$root/api/bridge/pairing/challenge", body)
                val pid = response.getString("pairingId")
                val chal = response.getString("challenge")
                val expiresAt = response.getString("expiresAt")
                require(pid.isNotBlank() && chal.isNotBlank()) { "Invalid challenge response." }
                pairingId = pid
                challenge = chal
                challengeExpiresAt = expiresAt
                require(
                    prefs.edit()
                        .putBoolean("paired", false)
                        .remove("pairing_id")
                        .putString("pending_pairing_id", pid)
                        .putString("pending_pairing_challenge", chal)
                        .putString("pending_pairing_expires_at", expiresAt)
                        .commit()
                ) { "Unable to persist pairing challenge." }
                runOnUiThread {
                    baseUrl.setText(root)
                    challengeButton.text = "REQUEST FRESH PAIRING"
                    status.text = "Pairing request ready. Approve it in PIGA Pocket. If you open the PIGA pairing link on this phone, confirmation happens automatically."
                    challengeButton.isEnabled = true
                    confirmButton.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Pairing request failed: ${e.message ?: e.javaClass.simpleName}"
                    challengeButton.isEnabled = true
                    confirmButton.isEnabled = pairingId != null
                }
            }
        }.start()
    }

    private fun handlePairingIntent(incoming: Intent?) {
        val uri = incoming?.data ?: return
        if (!uri.scheme.equals("piga", ignoreCase = true) || !uri.host.equals("pair", ignoreCase = true)) return

        val origin = uri.getQueryParameter("origin")?.trim().orEmpty()
        val linkedPairingId = uri.getQueryParameter("pairingId")?.trim().orEmpty()
        val code = uri.getQueryParameter("code")?.trim().orEmpty()
        val expiresAt = uri.getQueryParameter("expiresAt")?.trim().orEmpty()

        try {
            val canonicalOrigin = ControlPlaneResolver.validateEndpoint(origin)
            require(linkedPairingId.isNotBlank()) { "Pairing link is missing its pairing identifier." }
            require(code.matches(Regex("^\\d{6}$"))) { "Pairing link approval code is invalid." }
            require(expiresAt.isNotBlank()) { "Pairing link expiry is missing." }
            require(Instant.parse(expiresAt).isAfter(Instant.now())) { "Pairing link has expired." }

            restorePendingPairing()
            require(pairingId == linkedPairingId) {
                "Pairing link does not match the pending request on this phone."
            }
            require(!challenge.isNullOrBlank()) { "Pending pairing challenge is unavailable." }
            val localExpiry = challengeExpiresAt
            if (!localExpiry.isNullOrBlank()) {
                require(Instant.parse(localExpiry).isAfter(Instant.now())) { "Pending pairing request has expired." }
            }

            baseUrl.setText(canonicalOrigin)
            pairingCodeInput.setText(code)
            status.text = "Secure approval received. Confirming this phone…"
            confirmPairing()
        } catch (e: Exception) {
            status.text = "Pairing link blocked: ${e.message ?: e.javaClass.simpleName}"
            confirmButton.isEnabled = pairingId != null
        }
    }

    private fun confirmPairing() {
        restorePendingPairing()
        val pid = pairingId ?: run {
            status.text = "Pairing blocked: request a fresh pairing first."
            return
        }
        val chal = challenge ?: run {
            status.text = "Pairing blocked: challenge missing."
            return
        }
        val localExpiry = challengeExpiresAt
        if (!localExpiry.isNullOrBlank()) {
            try {
                if (!Instant.parse(localExpiry).isAfter(Instant.now())) {
                    clearPendingPairing()
                    status.text = "Pairing request expired. Start a fresh pairing."
                    return
                }
            } catch (_: Exception) {
                clearPendingPairing()
                status.text = "Pairing request expiry is invalid. Start a fresh pairing."
                return
            }
        }
        val code = pairingCodeInput.text.toString().trim()
        if (!code.matches(Regex("^\\d{6}$"))) {
            status.text = "Pairing blocked: enter the 6-digit owner approval code."
            return
        }

        confirmButton.isEnabled = false
        challengeButton.isEnabled = false
        status.text = "Signing and confirming pairing…"
        Thread {
            try {
                val root = ControlPlaneResolver.resolve(prefs.getString("base_url", null))
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
                        .remove("pending_pairing_id")
                        .remove("pending_pairing_challenge")
                        .remove("pending_pairing_expires_at")
                        .commit()
                ) { "Unable to persist paired state." }
                pairingId = null
                challenge = null
                challengeExpiresAt = null
                runOnUiThread {
                    baseUrl.setText(root)
                    pairingCodeInput.setText("")
                    status.text = "PAIRED. Secure phone connection is active."
                    val service = Intent(this@PairingActivity, BridgeService::class.java)
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
                    confirmButton.isEnabled = false
                    challengeButton.text = "REQUEST FRESH PAIRING"
                    challengeButton.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Pairing confirmation failed: ${e.message ?: e.javaClass.simpleName}"
                    confirmButton.isEnabled = pairingId != null
                    challengeButton.isEnabled = true
                }
            }
        }.start()
    }

    private fun restorePendingPairing() {
        val storedId = prefs.getString("pending_pairing_id", null)?.trim()
        val storedChallenge = prefs.getString("pending_pairing_challenge", null)?.trim()
        val storedExpiry = prefs.getString("pending_pairing_expires_at", null)?.trim()
        if (!storedId.isNullOrBlank() && !storedChallenge.isNullOrBlank()) {
            pairingId = storedId
            challenge = storedChallenge
            challengeExpiresAt = storedExpiry
        }
    }

    private fun clearPendingPairing() {
        pairingId = null
        challenge = null
        challengeExpiresAt = null
        prefs.edit()
            .remove("pending_pairing_id")
            .remove("pending_pairing_challenge")
            .remove("pending_pairing_expires_at")
            .apply()
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
