package io.piga.phonebridge

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class AccessibilityAccessActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("piga_bridge", MODE_PRIVATE) }
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = TextView(this).apply {
            textSize = 18f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24, 24, 24, 36)
        }

        val openSettings = Button(this).apply {
            text = "OPEN ANDROID ACCESSIBILITY SETTINGS"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val back = Button(this).apply {
            text = "BACK TO PIGA BRIDGE"
            setOnClickListener {
                startActivity(Intent(this@AccessibilityAccessActivity, MainActivity::class.java))
                finish()
            }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(36, 60, 36, 60)
            addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(openSettings, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(back, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(layout)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) refresh()
    }

    private fun refresh() {
        val connected = prefs.getBoolean("accessibility_connected", false)
        val lastPackage = prefs.getString("accessibility_last_package", null)
        val lastHash = prefs.getString("accessibility_last_hash", null)
        val lastTime = prefs.getString("accessibility_last_time", null)
        status.text = buildString {
            append("PIGA UI Evidence Sensor\n\n")
            append("Android service: ${if (connected) "CONNECTED" else "NOT CONNECTED"}\n")
            append("Mode: READ-ONLY / FAIL-CLOSED\n")
            append("Default scope: PIGA app only\n")
            append("Clicks / typing / gestures: DISABLED\n")
            append("Screenshots: DISABLED\n")
            if (!lastPackage.isNullOrBlank()) append("Last package: $lastPackage\n")
            if (!lastTime.isNullOrBlank()) append("Last evidence: $lastTime\n")
            if (!lastHash.isNullOrBlank()) append("Evidence hash: ${lastHash.take(20)}…\n")
            append("\nAndroid requires a one-time owner enablement for accessibility services.")
        }
    }
}
