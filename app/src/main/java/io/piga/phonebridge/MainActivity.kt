package io.piga.phonebridge

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : Activity() {
    private val alias = "piga_phone_bridge_device_key"
    private val prefs by lazy { getSharedPreferences("piga_bridge", MODE_PRIVATE) }

    private lateinit var statusCard: TextView
    private lateinit var masterAutonomySwitch: Switch
    private lateinit var emergencyStopSwitch: Switch
    private var suppressSwitchCallbacks = false

    private val backgroundColor = Color.rgb(4, 8, 20)
    private val cardColor = Color.rgb(12, 21, 43)
    private val primaryColor = Color.rgb(71, 236, 255)
    private val secondaryColor = Color.rgb(119, 112, 255)
    private val dangerColor = Color.rgb(255, 76, 116)
    private val textColor = Color.rgb(238, 247, 255)
    private val mutedColor = Color.rgb(163, 181, 207)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureKey()
        ensureDeviceId()
        ensureNotificationChannels()
        buildUi()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        if (::statusCard.isInitialized) refreshStatus()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST) refreshStatus()
    }

    private fun buildUi() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(36, 56, 36, 72)
            setBackgroundColor(backgroundColor)
        }

        content.addView(TextView(this).apply {
            text = "PIGA POCKET ENTERPRISE"
            textSize = 25f
            setTextColor(textColor)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
        }, fullWidth())

        content.addView(TextView(this).apply {
            text = "Governed smartphone execution edge"
            textSize = 14f
            setTextColor(primaryColor)
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 28)
        }, fullWidth())

        statusCard = TextView(this).apply {
            textSize = 15f
            setTextColor(textColor)
            setLineSpacing(0f, 1.18f)
            setPadding(28, 26, 28, 26)
            background = roundedCard(primaryColor)
        }
        content.addView(statusCard, fullWidth())

        content.addView(sectionTitle("Sicherheitskontrollen"), fullWidth())

        masterAutonomySwitch = Switch(this).apply {
            text = "Master Autonomy"
            textSize = 17f
            setTextColor(textColor)
            buttonTintList = ColorStateList.valueOf(primaryColor)
            setPadding(16, 10, 16, 10)
            setOnCheckedChangeListener { _, checked ->
                if (!suppressSwitchCallbacks) handleMasterAutonomy(checked)
            }
        }
        content.addView(masterAutonomySwitch, fullWidth())

        emergencyStopSwitch = Switch(this).apply {
            text = "Emergency Stop"
            textSize = 17f
            setTextColor(textColor)
            buttonTintList = ColorStateList.valueOf(dangerColor)
            setPadding(16, 10, 16, 18)
            setOnCheckedChangeListener { _, checked ->
                if (!suppressSwitchCallbacks) handleEmergencyStop(checked)
            }
        }
        content.addView(emergencyStopSwitch, fullWidth())

        content.addView(sectionTitle("Gerät und Laufzeit"), fullWidth())
        content.addView(actionButton("Gerät koppeln / neu koppeln", primaryColor) {
            startActivity(Intent(this, PairingActivity::class.java))
        }, fullWidth())
        content.addView(actionButton("Governed Runtime starten", secondaryColor) {
            startRuntime()
        }, fullWidth())
        content.addView(actionButton("Lokale Fähigkeiten prüfen", secondaryColor) {
            startActivity(Intent(this, LocalDiagnosticsActivity::class.java))
        }, fullWidth())
        content.addView(actionButton("Benachrichtigungen erlauben", secondaryColor) {
            requestNotificationPermission()
        }, fullWidth())

        content.addView(sectionTitle("Identität und Support"), fullWidth())
        content.addView(actionButton("Geräte-ID kopieren", secondaryColor) {
            copyToClipboard("PIGA Android Device ID", ensureDeviceId())
        }, fullWidth())
        content.addView(actionButton("Öffentlichen Geräteschlüssel kopieren", secondaryColor) {
            copyToClipboard("PIGA Android Keystore Public Key", getPublicKeyBase64())
        }, fullWidth())
        content.addView(actionButton("pigapocket.com öffnen", secondaryColor) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(ControlPlaneResolver.CANONICAL_CONTROL_PLANE))
            startActivity(intent)
        }, fullWidth())

        content.addView(TextView(this).apply {
            text = "Gate 1 → task-scoped workers → material-change re-entry → Gate 2 → verified receipt"
            textSize = 12f
            setTextColor(mutedColor)
            gravity = Gravity.CENTER
            setPadding(12, 34, 12, 0)
        }, fullWidth())

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(backgroundColor)
            addView(content, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        })
    }

    private fun handleMasterAutonomy(enabled: Boolean) {
        val paired = prefs.getBoolean("paired", false)
        val emergencyStop = prefs.getBoolean("emergency_stop", false)

        if (enabled && !paired) {
            setSwitchSilently(masterAutonomySwitch, false)
            prefs.edit()
                .putBoolean("master_autonomy", false)
                .putString("autonomy_status", "BLOCKED_NOT_PAIRED")
                .apply()
            toast("Master Autonomy erfordert zuerst eine Gerätekopplung.")
            refreshStatus()
            return
        }
        if (enabled && emergencyStop) {
            setSwitchSilently(masterAutonomySwitch, false)
            prefs.edit()
                .putBoolean("master_autonomy", false)
                .putString("autonomy_status", "BLOCKED_EMERGENCY_STOP")
                .apply()
            toast("Emergency Stop ist aktiv.")
            refreshStatus()
            return
        }

        prefs.edit()
            .putBoolean("master_autonomy", enabled)
            .putString("autonomy_status", if (enabled) "ARMED_EXPLICIT" else "DISARMED_USER")
            .apply()

        if (enabled) {
            startRuntime()
        } else {
            stopService(Intent(this, BridgeService::class.java))
            prefs.edit().putString("runtime_status", "DISARMED_BY_USER").apply()
            refreshStatus()
        }
    }

    private fun handleEmergencyStop(enabled: Boolean) {
        val editor = prefs.edit().putBoolean("emergency_stop", enabled)
        if (enabled) {
            editor
                .putBoolean("master_autonomy", false)
                .putString("autonomy_status", "DISARMED_EMERGENCY_STOP")
                .putString("runtime_status", "STOPPED_EMERGENCY_STOP")
                .apply()
            setSwitchSilently(masterAutonomySwitch, false)
            stopService(Intent(this, BridgeService::class.java))
            toast("Emergency Stop aktiviert.")
        } else {
            editor
                .putString("autonomy_status", "DISARMED_REQUIRES_EXPLICIT_REARM")
                .apply()
            toast("Emergency Stop aufgehoben. Master Autonomy bleibt aus.")
        }
        refreshStatus()
    }

    private fun startRuntime() {
        val paired = prefs.getBoolean("paired", false)
        val masterAutonomy = prefs.getBoolean("master_autonomy", false)
        val emergencyStop = prefs.getBoolean("emergency_stop", false)

        when {
            !paired -> {
                prefs.edit().putString("runtime_status", "BLOCKED_NOT_PAIRED").apply()
                toast("Bitte zuerst das Gerät koppeln.")
            }
            !masterAutonomy -> {
                prefs.edit().putString("runtime_status", "BLOCKED_MASTER_AUTONOMY_OFF").apply()
                toast("Master Autonomy ist ausgeschaltet.")
            }
            emergencyStop -> {
                prefs.edit().putString("runtime_status", "BLOCKED_EMERGENCY_STOP").apply()
                toast("Emergency Stop blockiert die Laufzeit.")
            }
            else -> {
                try {
                    val canonical = ControlPlaneResolver.resolve(
                        prefs.getString("base_url", ControlPlaneResolver.CANONICAL_CONTROL_PLANE),
                    )
                    require(prefs.edit().putString("base_url", canonical).commit()) {
                        "Canonical control plane could not be persisted"
                    }
                    BridgeRecoveryScheduler.ensureScheduled(this)
                    val intent = Intent(this, BridgeService::class.java)
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
                    prefs.edit()
                        .putString("runtime_status", "START_REQUESTED")
                        .putLong("runtime_start_requested_ms", System.currentTimeMillis())
                        .apply()
                    toast("Governed Runtime gestartet.")
                } catch (e: Exception) {
                    prefs.edit().putString(
                        "runtime_status",
                        "BLOCKED ${e.message ?: e.javaClass.simpleName}",
                    ).apply()
                    toast("Start blockiert: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
        refreshStatus()
    }

    private fun refreshStatus() {
        val paired = prefs.getBoolean("paired", false)
        val master = prefs.getBoolean("master_autonomy", false)
        val emergencyStop = prefs.getBoolean("emergency_stop", false)
        val runtime = prefs.getString("runtime_status", "NOT_STARTED") ?: "NOT_STARTED"
        val recovery = prefs.getString("recovery_status", "NOT_RUN") ?: "NOT_RUN"
        val baseUrl = prefs.getString("base_url", ControlPlaneResolver.CANONICAL_CONTROL_PLANE)
            ?.trim()?.removeSuffix("/")
        val trustedOrigin = baseUrl == ControlPlaneResolver.CANONICAL_CONTROL_PLANE
        val notificationPermission = hasNotificationPermission()

        setSwitchSilently(masterAutonomySwitch, master)
        setSwitchSilently(emergencyStopSwitch, emergencyStop)

        statusCard.text = buildString {
            append("SYSTEMSTATUS\n")
            append("\nKopplung: ").append(if (paired) "PAIRED" else "NOT PAIRED")
            append("\nRuntime: ").append(runtime)
            append("\nRecovery: ").append(recovery)
            append("\nMaster Autonomy: ").append(if (master) "ON" else "OFF")
            append("\nEmergency Stop: ").append(if (emergencyStop) "ON" else "OFF")
            append("\nBenachrichtigungen: ").append(if (notificationPermission) "GRANTED" else "NOT GRANTED")
            append("\nControl Plane: ").append(if (trustedOrigin) "TRUSTED" else "BLOCKED")
            append("\nVersion: ").append(BuildConfig.VERSION_NAME)
                .append(" (").append(BuildConfig.VERSION_CODE).append(")")
            append("\nLetzter Poll: ").append(formatTimestamp(prefs.getLong("last_poll_ms", 0L)))
            append("\nGerät: ").append(ensureDeviceId())
        }
    }

    private fun requestNotificationPermission() {
        ensureNotificationChannels()
        if (Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission()) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
        } else {
            toast("Benachrichtigungen sind bereits erlaubt.")
        }
    }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun ensureNotificationChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    "piga_bridge",
                    "PIGA Pocket Enterprise",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
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
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(alias)) return
        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            "AndroidKeyStore",
        )
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()
        generator.initialize(spec)
        generator.generateKeyPair()
    }

    private fun getPublicKeyBase64(): String {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return Base64.encodeToString(
            keyStore.getCertificate(alias).publicKey.encoded,
            Base64.NO_WRAP,
        )
    }

    private fun copyToClipboard(label: String, value: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        toast("In die Zwischenablage kopiert.")
    }

    private fun formatTimestamp(value: Long): String {
        if (value <= 0L) return "pending"
        return SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date(value))
    }

    private fun setSwitchSilently(target: Switch, value: Boolean) {
        suppressSwitchCallbacks = true
        target.isChecked = value
        suppressSwitchCallbacks = false
    }

    private fun sectionTitle(value: String) = TextView(this).apply {
        text = value.uppercase(Locale.getDefault())
        textSize = 13f
        setTextColor(mutedColor)
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        letterSpacing = 0.08f
        setPadding(6, 34, 6, 12)
    }

    private fun actionButton(label: String, tint: Int, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 15f
        isAllCaps = false
        setTextColor(backgroundColor)
        backgroundTintList = ColorStateList.valueOf(tint)
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setPadding(12, 8, 12, 8)
        setOnClickListener { action() }
    }

    private fun roundedCard(strokeColor: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 28f
        setColor(cardColor)
        setStroke(2, strokeColor)
    }

    private fun fullWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        bottomMargin = 10
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val NOTIFICATION_PERMISSION_REQUEST = 1001
    }
}
