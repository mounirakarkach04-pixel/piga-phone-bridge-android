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
import android.view.View
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
    private val recoveryEndpoint = "https://qjvopzschqukitvudgfz.supabase.co/functions/v1/piga-pairing-recovery-v1"

    private lateinit var status: TextView
    private lateinit var pairingCodeInput: EditText
    private lateinit var challengeButton: Button
    private lateinit var recoveryButton: Button
    private lateinit var ownerButton: Button
    private lateinit var confirmButton: Button
    private var pairingId: String? = null
    private var challenge: String? = null
    private var ownerClaim: OwnerClaim? = null

    private data class OwnerClaim(
        val claimId: String,
        val pairingId: String,
        val deviceId: String,
        val challenge: String,
        val code: String,
        val callback: String,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureKey()
        ensureDeviceId()
        restorePendingPairing()

        val alreadyPaired = prefs.getBoolean("paired", false)

        val title = TextView(this).apply {
            text = "PIGA POCKET BRIDGE"
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
            text = when {
                alreadyPaired -> "Dieses Handy ist bereits sicher mit PIGA gekoppelt."
                pairingId != null && challenge != null -> "2 von 3 · Gerätefreigabe wartet auf Besitzerbestätigung."
                else -> "PIGA prüft zuerst automatisch, ob die frühere sichere Gerätekopplung wiederhergestellt werden kann."
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
            isEnabled = pairingId != null && challenge != null
        }

        recoveryButton = Button(this).apply {
            text = "Bestehende Kopplung wiederherstellen"
            isAllCaps = false
            setOnClickListener { recoverExistingPairing() }
        }

        challengeButton = Button(this).apply {
            text = if (alreadyPaired) "Neu koppeln" else "Neue Kopplung starten"
            isAllCaps = false
            setOnClickListener { requestChallenge() }
        }

        ownerButton = Button(this).apply {
            text = "Dieses Handy als ersten Besitzer bestätigen"
            isAllCaps = false
            visibility = View.GONE
            setOnClickListener { confirmFirstOwnerClaim() }
        }

        val pocketButton = Button(this).apply {
            text = "PIGA Pocket im Browser öffnen"
            isAllCaps = false
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("${ControlPlaneResolver.CANONICAL_CONTROL_PLANE}/device-pairing")))
            }
        }

        confirmButton = Button(this).apply {
            text = "Sicher koppeln"
            isAllCaps = false
            isEnabled = pairingId != null && challenge != null
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
            addView(recoveryButton, fullWidth())
            addView(pairingCodeInput, fullWidth())
            addView(challengeButton, fullWidth())
            addView(ownerButton, fullWidth())
            addView(pocketButton, fullWidth())
            addView(confirmButton, fullWidth())
            addView(backButton, fullWidth())
        }

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })

        handleIncomingIntent(intent)
        if (!alreadyPaired && savedInstanceState == null) recoverExistingPairing()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun recoverExistingPairing() {
        recoveryButton.isEnabled = false
        challengeButton.isEnabled = false
        pairingCodeInput.isEnabled = false
        confirmButton.isEnabled = false
        status.text = "Frühere sichere Gerätekopplung wird über den Android Keystore geprüft …"

        Thread {
            try {
                val publicKey = getPublicKeyBase64()
                val nonce = UUID.randomUUID().toString()
                val canonical = "PAIR_RECOVER\n$nonce\n$publicKey"
                val body = JSONObject()
                    .put("publicKey", publicKey)
                    .put("nonce", nonce)
                    .put("signature", sign(canonical))
                    .put("keyAlgorithm", "EC-P256-SHA256")
                val response = postJson(recoveryEndpoint, body)
                require(response.optString("status") == "RECOVER") { "Recovery not admitted." }
                val recoveredDeviceId = response.getString("deviceId").trim()
                val recoveredPairingId = response.getString("pairingId").trim()
                val registryState = response.optString("registryState")
                val emergencyStop = response.optBoolean("emergencyStop", true)
                require(recoveredDeviceId.isNotBlank() && recoveredPairingId.isNotBlank()) { "Recovery binding incomplete." }
                require(registryState == "active" && !emergencyStop) { "Recovery binding is not active." }

                val root = ControlPlaneResolver.resolve(ControlPlaneResolver.CANONICAL_CONTROL_PLANE)
                val autonomyEnabled = response.optBoolean("autonomyEnabled", false)
                require(prefs.edit()
                    .putString("device_id", recoveredDeviceId)
                    .putString("pairing_id", recoveredPairingId)
                    .putBoolean("paired", true)
                    .putString("base_url", root)
                    .putBoolean("emergency_stop", false)
                    .putBoolean("master_autonomy", autonomyEnabled)
                    .putString("autonomy_status", if (autonomyEnabled) "RECOVERED_ARMED" else "RECOVERED_DISARMED")
                    .putString("runtime_status", "RECOVERED_PENDING_START")
                    .remove("pending_pairing_id")
                    .remove("pending_challenge")
                    .commit()) { "Unable to persist recovered pairing." }

                runOnUiThread {
                    pairingId = recoveredPairingId
                    challenge = null
                    pairingCodeInput.setText("")
                    pairingCodeInput.isEnabled = false
                    confirmButton.isEnabled = false
                    recoveryButton.isEnabled = true
                    challengeButton.text = "Neu koppeln"
                    challengeButton.isEnabled = true
                    status.text = "Bestehende Kopplung sicher wiederhergestellt. Geräte-ID und Pairing-ID wurden aus dem Keystore-Nachweis übernommen."
                    val service = Intent(this@PairingActivity, BridgeService::class.java)
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = when {
                        e.message.orEmpty().contains("HTTP 404", ignoreCase = true) -> "Für diesen Android-Keystore wurde keine eindeutige frühere Kopplung gefunden. Neue Kopplung bleibt verfügbar."
                        else -> "Die bestehende Kopplung konnte noch nicht verifiziert werden. Neue Kopplung bleibt verfügbar."
                    }
                    recoveryButton.isEnabled = true
                    challengeButton.isEnabled = true
                }
            }
        }.start()
    }

    private fun handleIncomingIntent(incoming: Intent?) {
        val uri = incoming?.data ?: return
        if (!uri.scheme.equals("pigapocketbridge", ignoreCase = true)) return
        when (uri.host) {
            "owner-claim" -> handleOwnerClaim(uri)
            "pair-confirm" -> handlePairConfirm(uri)
            "device-pairing" -> restorePendingPairing()
        }
    }

    private fun handleOwnerClaim(uri: Uri) {
        try {
            val claimId = requiredQuery(uri, "claimId")
            val pid = requiredQuery(uri, "pairingId")
            val deviceId = requiredQuery(uri, "deviceId")
            val chal = requiredQuery(uri, "challenge")
            val code = requiredQuery(uri, "code")
            val callback = requiredQuery(uri, "callback")
            require(deviceId == ensureDeviceId()) { "Owner claim device mismatch." }
            require(code.matches(Regex("^\\d{6}$"))) { "Owner claim code invalid." }
            require(callback == "${ControlPlaneResolver.CANONICAL_CONTROL_PLANE}/device-pairing") { "Untrusted owner callback." }
            val pendingPid = pairingId ?: prefs.getString("pending_pairing_id", null)
            val pendingChallenge = challenge ?: prefs.getString("pending_challenge", null)
            require(pendingPid == pid && pendingChallenge == chal) { "Owner claim is not bound to the active pairing challenge." }

            ownerClaim = OwnerClaim(claimId, pid, deviceId, chal, code, callback)
            pairingId = pid
            challenge = chal
            status.text = "Besitzer-Freigabe\n\nPrüfe die Anfrage und tippe bewusst auf „Dieses Handy als ersten Besitzer bestätigen“. Danach wird nur ein kryptografischer Nachweis an PIGA zurückgegeben."
            ownerButton.visibility = View.VISIBLE
            ownerButton.isEnabled = true
            challengeButton.isEnabled = false
            confirmButton.isEnabled = false
        } catch (error: Exception) {
            ownerClaim = null
            ownerButton.visibility = View.GONE
            status.text = "Die Besitzer-Freigabe ist ungültig oder gehört nicht zu dieser Geräte-Challenge."
        }
    }

    private fun confirmFirstOwnerClaim() {
        val claim = ownerClaim ?: return
        ownerButton.isEnabled = false
        status.text = "Besitzer-Freigabe wird im Android Keystore signiert …"
        try {
            val payload = listOf(
                "FIRST_OWNER_CLAIM",
                claim.claimId,
                claim.pairingId,
                claim.deviceId,
                claim.challenge,
                claim.code,
            ).joinToString("\n")
            val signature = sign(payload)
            val callbackUri = Uri.parse(claim.callback).buildUpon()
                .appendQueryParameter("ownerClaim", "1")
                .appendQueryParameter("claimId", claim.claimId)
                .appendQueryParameter("pairingId", claim.pairingId)
                .appendQueryParameter("deviceId", claim.deviceId)
                .appendQueryParameter("code", claim.code)
                .appendQueryParameter("signature", signature)
                .build()
            ownerClaim = null
            ownerButton.visibility = View.GONE
            status.text = "Signatur erstellt. PIGA bestätigt jetzt die Besitzerrolle im angemeldeten Browser."
            startActivity(Intent(Intent.ACTION_VIEW, callbackUri))
        } catch (_: Exception) {
            ownerButton.isEnabled = true
            status.text = "Die Besitzer-Signatur konnte nicht erstellt werden. Die Freigabe bleibt blockiert."
        }
    }

    private fun handlePairConfirm(uri: Uri) {
        val pid = uri.getQueryParameter("pairingId")?.trim().orEmpty()
        val code = uri.getQueryParameter("code")?.trim().orEmpty()
        restorePendingPairing()
        if (pid.isBlank() || pid != pairingId || !code.matches(Regex("^\\d{6}$"))) {
            status.text = "Der Freigabecode gehört nicht zur aktuellen Geräte-Challenge."
            return
        }
        pairingCodeInput.setText(code)
        pairingCodeInput.isEnabled = true
        confirmButton.isEnabled = true
        challengeButton.isEnabled = true
        status.text = "3 von 3 · Besitzerfreigabe erhalten\n\nTippe jetzt auf „Sicher koppeln“, damit dieses Handy die Bindung im Android Keystore bestätigt."
    }

    private fun requiredQuery(uri: Uri, name: String): String {
        val value = uri.getQueryParameter(name)?.trim().orEmpty()
        require(value.isNotBlank()) { "$name missing" }
        return value
    }

    private fun restorePendingPairing() {
        pairingId = prefs.getString("pending_pairing_id", null)?.trim()?.takeIf { it.isNotBlank() }
        challenge = prefs.getString("pending_challenge", null)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun requestChallenge() {
        challengeButton.isEnabled = false
        pairingCodeInput.isEnabled = false
        confirmButton.isEnabled = false
        ownerButton.visibility = View.GONE
        ownerClaim = null
        pairingId = null
        challenge = null
        prefs.edit().remove("pending_pairing_id").remove("pending_challenge").apply()
        status.text = "1 von 3 · Sichere Verbindung wird vorbereitet …"

        Thread {
            try {
                val root = ControlPlaneResolver.resolve(ControlPlaneResolver.CANONICAL_CONTROL_PLANE)
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
                require(prefs.edit()
                    .putBoolean("paired", false)
                    .remove("pairing_id")
                    .putString("pending_pairing_id", pid)
                    .putString("pending_challenge", chal)
                    .commit()) { "Unable to persist pending pairing." }

                runOnUiThread {
                    status.text = "2 von 3 · Freigabe bestätigen\n\nÖffne PIGA Pocket im Browser. Dort erscheint die Besitzer-Freigabe. PIGA führt dich anschließend automatisch zu dieser Bridge zurück."
                    pairingCodeInput.isEnabled = true
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
        restorePendingPairing()
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
                val signature = sign("PAIR_CONFIRM\n$pid\n$chal")
                val body = JSONObject()
                    .put("deviceId", ensureDeviceId())
                    .put("pairingId", pid)
                    .put("pairingCode", code)
                    .put("signature", signature)
                val response = postJson("$root/api/bridge/pairing/confirm", body)
                val confirmedPairingId = response.optString("pairingId", pid)
                require(confirmedPairingId == pid) { "Pairing confirmation returned unexpected pairingId." }
                require(prefs.edit()
                    .putBoolean("paired", true)
                    .putString("pairing_id", pid)
                    .putString("base_url", root)
                    .remove("pending_pairing_id")
                    .remove("pending_challenge")
                    .commit()) { "Unable to persist paired state." }

                runOnUiThread {
                    pairingCodeInput.setText("")
                    pairingCodeInput.isEnabled = false
                    status.text = "Sicher gekoppelt. PIGA startet jetzt den geschützten Gerätekanal."
                    val service = Intent(this@PairingActivity, BridgeService::class.java)
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
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

    private fun sign(payload: String): String {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val entry = ks.getEntry(alias, null) as KeyStore.PrivateKeyEntry
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(entry.privateKey)
        signer.update(payload.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(signer.sign(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun friendlyError(error: Exception): String {
        val raw = error.message.orEmpty()
        return when {
            raw.contains("API_ORIGIN_NOT_ADMITTED", ignoreCase = true) -> "PIGA ist erreichbar, aber der sichere API-Dienst ist momentan noch nicht freigegeben."
            raw.contains("HTTP 401", ignoreCase = true) || raw.contains("HTTP 403", ignoreCase = true) -> "Die Gerätefreigabe wurde nicht autorisiert. Öffne PIGA Pocket im Browser, melde dich an und bestätige die Freigabe."
            Regex("HTTP 5\\d\\d", RegexOption.IGNORE_CASE).containsMatchIn(raw) -> "Der sichere PIGA-Dienst ist momentan nicht verfügbar. Bitte erneut versuchen."
            raw.contains("timed out", ignoreCase = true) || raw.contains("timeout", ignoreCase = true) -> "Die Verbindung hat zu lange gedauert. Bitte Internetverbindung prüfen und erneut versuchen."
            else -> "Die sichere Verbindung konnte nicht abgeschlossen werden. Bitte erneut versuchen."
        }
    }

    private fun ensureDeviceId(): String {
        val stored = prefs.getString("device_id", null)?.trim()
        if (!stored.isNullOrBlank()) return stored
        val created = UUID.randomUUID().toString()
        require(prefs.edit().putString("device_id", created).commit()) { "Unable to persist device identity" }
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
                .build(),
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
            connectTimeout = 15_000
            readTimeout = 15_000
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

    private fun fullWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        bottomMargin = 12
    }
}
