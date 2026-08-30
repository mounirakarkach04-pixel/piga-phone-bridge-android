package io.piga.phonebridge

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.text.InputType
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
    private lateinit var pairingCodeInput: EditText
    private lateinit var challengeButton: Button
    private lateinit var confirmButton: Button
    private var pairingId: String? = null
    private var challenge: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureKey()
        ensureDeviceId()

        val alreadyPaired = prefs.getBoolean("paired", false)

        val title = TextView(this).apply {
            text = "PIGA POCKET"
            textSize = 26f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(16, 8, 16, 8)
        }

        val subtitle = TextView(this).apply {
            text = "Dieses Handy sicher verbinden"
            textSize = 17f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(16, 0, 16, 28)
        }

        status = TextView(this).apply {
            textSize = 18f
            gravity = Gravity.CENTER_HORIZONTAL
            text = if (alreadyPaired) {
                "Dieses Handy ist bereits sicher mit PIGA gekoppelt."
            } else {
                "1 von 3 · Gerät vorbereiten\n\nTippe auf „Dieses Handy verbinden“."
            }
            setPadding(16, 16, 16, 24)
        }

        val securityNote = TextView(this).apply {
            text = "Dein privater Geräteschlüssel bleibt geschützt im Android Keystore und verlässt dieses Handy nicht."
            textSize = 14f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(16, 0, 16, 24)
        }

        pairingCodeInput = EditText(this).apply {
            hint = "6-stelliger Freigabecode"
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            isEnabled = false
        }

        challengeButton = Button(this).apply {
            text = if (alreadyPaired) "Neu koppeln" else "Dieses Handy verbinden"
            isAllCaps = false
            setOnClickListener { requestChallenge() }
        }

        val pocketButton = Button(this).apply {
            text = "PIGA Pocket öffnen"
            isAllCaps = false
            setOnClickListener {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(ControlPlaneResolver.CANONICAL_CONTROL_PLANE),
                    ),
                )
            }
        }

        confirmButton = Button(this).apply {
            text = "Sicher koppeln"
            isAllCaps = false
            isEnabled = false
            setOnClickListener { confirmPairing() }
        }

        val backButton = Button(this).apply {
            text = "Zurück zu PIGA"
            isAllCaps = false
            setOnClickListener { finish() }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(36, 48, 36, 72)
            addView(title, fullWidth())
            addView(subtitle, fullWidth())
            addView(status, fullWidth())
            addView(securityNote, fullWidth())
            addView(pairingCodeInput, fullWidth())
            addView(challengeButton, fullWidth())
            addView(pocketButton, fullWidth())
            addView(confirmButton, fullWidth())
            addView(backButton, fullWidth())
        }

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        })
    }

    private fun requestChallenge() {
        challengeButton.isEnabled = false
        pairingCodeInput.isEnabled = false
        confirmButton.isEnabled = false
        pairingId = null
        challenge = null
        status.text = "1 von 3 · Sichere Verbindung wird vorbereitet …"

        Thread {
            try {
                val root = ControlPlaneResolver.resolve(ControlPlaneResolver.CANONICAL_CONTROL_PLANE)
                require(root.startsWith("https://")) { "Control Plane must use HTTPS." }
                require(prefs.edit().putString("base_url", root).commit()) {
                    "Unable to persist canonical control plane."
                }

                val body = JSONObject()
                    .put("deviceId", ensureDeviceId())
                    .put("publicKey", getPublicKeyBase64())
                    .put("keyAlgorithm", "EC-P256-SHA256")
                    .put("capabilities", org.json.JSONArray(listOf(
                        "pocket.notification",
                        "pocket.clipboard.write",
                        "pocket.intent.url",
                        "pocket.tts",
                        "pocket.app.launch",
                        "pocket.share.text",
                        "pocket.orchestration.verify",
                    )))
                val response = postJson("$root/api/bridge/pairing/challenge", body)
                val pid = response.getString("pairingId")
                val chal = response.getString("challenge")
                require(pid.isNotBlank() && chal.isNotBlank()) { "Invalid challenge response." }

                pairingId = pid
                challenge = chal
                prefs.edit().putBoolean("paired", false).remove("pairing_id").apply()

                runOnUiThread {
                    status.text =
                        "2 von 3 · Freigabe bestätigen\n\nÖffne PIGA Pocket, bestätige die Gerätefreigabe und gib anschließend den 6-stelligen Code hier ein."
                    pairingCodeInput.isEnabled = true
                    pairingCodeInput.requestFocus()
                    challengeButton.text = "Neue Kopplungsanfrage"
                    challengeButton.isEnabled = true
                    confirmButton.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = friendlyError(e)
                    challengeButton.text = "Erneut versuchen"
                    challengeButton.isEnabled = true
                    pairingCodeInput.isEnabled = false
                    confirmButton.isEnabled = false
                }
            }
        }.start()
    }

    private fun confirmPairing() {
        val pid = pairingId ?: run {
            status.text = "Bitte zuerst eine neue Gerätefreigabe anfordern."
            return
        }
        val chal = challenge ?: run {
            status.text = "Die Gerätefreigabe ist nicht mehr gültig. Bitte erneut verbinden."
            return
        }
        val code = pairingCodeInput.text.toString().trim()
        if (!code.matches(Regex("^\\d{6}$"))) {
            status.text = "Bitte den 6-stelligen Freigabecode eingeben."
            return
        }

        confirmButton.isEnabled = false
        challengeButton.isEnabled = false
        status.text = "3 von 3 · Sichere Kopplung wird bestätigt …"

        Thread {
            try {
                val root = ControlPlaneResolver.resolve(ControlPlaneResolver.CANONICAL_CONTROL_PLANE)
                val signingPayload = "PAIR_CONFIRM\n$pid\n$chal"
                val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                val entry = ks.getEntry(alias, null) as KeyStore.PrivateKeyEntry
                val signer = Signature.getInstance("SHA256withECDSA")
                signer.initSign(entry.privateKey)
                signer.update(signingPayload.toByteArray(Charsets.UTF_8))
                val signature = Base64.encodeToString(
                    signer.sign(),
                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
                )

                val body = JSONObject()
                    .put("deviceId", ensureDeviceId())
                    .put("pairingId", pid)
                    .put("pairingCode", code)
                    .put("signature", signature)
                val response = postJson("$root/api/bridge/pairing/confirm", body)
                val confirmedPairingId = response.optString("pairingId", pid)
                require(confirmedPairingId == pid) {
                    "Pairing confirmation returned unexpected pairingId."
                }
                require(
                    prefs.edit()
                        .putBoolean("paired", true)
                        .putString("pairing_id", pid)
                        .putString("base_url", root)
                        .commit(),
                ) { "Unable to persist paired state." }

                runOnUiThread {
                    pairingCodeInput.setText("")
                    pairingCodeInput.isEnabled = false
                    status.text =
                        "Sicher gekoppelt. PIGA startet jetzt den geschützten Gerätekanal."
                    val service = Intent(this@PairingActivity, BridgeService::class.java)
                    if (Build.VERSION.SDK_INT >= 26) {
                        startForegroundService(service)
                    } else {
                        startService(service)
                    }
                    confirmButton.isEnabled = false
                    challengeButton.text = "Neu koppeln"
                    challengeButton.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = friendlyError(e)
                    challengeButton.isEnabled = true
                    confirmButton.isEnabled = true
                }
            }
        }.start()
    }

    private fun friendlyError(error: Exception): String {
        val raw = error.message.orEmpty()
        return when {
            raw.contains("API_ORIGIN_NOT_ADMITTED", ignoreCase = true) ->
                "PIGA ist erreichbar, aber der sichere API-Dienst ist momentan noch nicht freigegeben. Die App selbst ist erreichbar; die Serverfreigabe wird benötigt."
            raw.contains("HTTP 401", ignoreCase = true) ||
                raw.contains("HTTP 403", ignoreCase = true) ->
                "Die Gerätefreigabe wurde nicht autorisiert. Bitte PIGA Pocket öffnen, anmelden und die Freigabe erneut bestätigen."
            Regex("HTTP 5\\d\\d", RegexOption.IGNORE_CASE).containsMatchIn(raw) ->
                "Der sichere PIGA-Dienst ist momentan nicht verfügbar. Bitte erneut versuchen."
            raw.contains("timed out", ignoreCase = true) ||
                raw.contains("timeout", ignoreCase = true) ->
                "Die Verbindung hat zu lange gedauert. Bitte Internetverbindung prüfen und erneut versuchen."
            else ->
                "Die sichere Verbindung konnte nicht abgeschlossen werden. Bitte erneut versuchen."
        }
    }

    private fun ensureDeviceId(): String {
        val stored = prefs.getString("device_id", null)?.trim()
        if (!stored.isNullOrBlank()) return stored
        val created = UUID.randomUUID().toString()
        require(prefs.edit().putString("device_id", created).commit()) {
            "Unable to persist device identity"
        }
        return created
    }

    private fun ensureKey() {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(alias)) return
        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            "AndroidKeyStore",
        )
        generator.initialize(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build(),
        )
        generator.generateKeyPair()
    }

    private fun getPublicKeyBase64(): String {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return Base64.encodeToString(
            ks.getCertificate(alias).publicKey.encoded,
            Base64.NO_WRAP,
        )
    }

    private fun postJson(url: String, body: JSONObject): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        connection.outputStream.use {
            it.write(body.toString().toByteArray(Charsets.UTF_8))
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        connection.disconnect()
        if (code !in 200..299) {
            throw IllegalStateException("HTTP $code ${text.take(240)}")
        }
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun fullWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        bottomMargin = 12
    }
}
