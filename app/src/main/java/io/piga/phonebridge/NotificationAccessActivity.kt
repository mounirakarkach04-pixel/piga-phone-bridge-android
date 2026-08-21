package io.piga.phonebridge

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** One-time owner-controlled bootstrap for Android notification-listener access. */
class NotificationAccessActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            textSize = 18f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24, 24, 24, 36)
        }
        val open = Button(this).apply {
            text = "OPEN NOTIFICATION ACCESS"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
        val back = Button(this).apply {
            text = "BACK TO PIGA BRIDGE"
            setOnClickListener {
                startActivity(Intent(this@NotificationAccessActivity, MainActivity::class.java))
                finish()
            }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(36, 48, 36, 72)
            addView(status, fullWidth())
            addView(open, fullWidth())
            addView(back, fullWidth())
        }
        setContentView(content)
    }

    override fun onResume() {
        super.onResume()
        val component = ComponentName(this, PigaNotificationListener::class.java)
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.split(":")
            ?.any { ComponentName.unflattenFromString(it) == component } == true
        status.text = if (enabled) {
            "PIGA Notification Intake: ENABLED\nEvents remain locally bounded and PIGA-gated."
        } else {
            "PIGA Notification Intake: DISABLED\nAndroid requires a one-time owner grant."
        }
    }

    private fun fullWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )
}
