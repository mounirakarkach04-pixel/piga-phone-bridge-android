package io.piga.phonebridge

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

/**
 * PIGA-gated notification event sensor.
 *
 * Privacy/safety design:
 * - Disabled unless the device is paired and Master Autonomy is enabled.
 * - Emergency Stop blocks capture.
 * - Stores a bounded local event queue only; no network transmission occurs here.
 * - Captures package, event type, timestamp and short title/text excerpts only.
 * - The bridge/control plane must separately admit any downstream use/action.
 */
class PigaNotificationListener : NotificationListenerService() {
    private val prefs by lazy { getSharedPreferences("piga_bridge", MODE_PRIVATE) }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (!captureAdmitted()) return
        val extras = sbn.notification.extras
        enqueue(
            eventType = "posted",
            packageName = sbn.packageName,
            key = sbn.key,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        if (!captureAdmitted()) return
        enqueue(
            eventType = "removed",
            packageName = sbn.packageName,
            key = sbn.key,
            title = null,
            text = null
        )
    }

    private fun captureAdmitted(): Boolean =
        prefs.getBoolean("paired", false) &&
            prefs.getBoolean("master_autonomy", false) &&
            !prefs.getBoolean("emergency_stop", false)

    private fun enqueue(
        eventType: String,
        packageName: String,
        key: String,
        title: String?,
        text: String?
    ) {
        val queue = runCatching {
            JSONArray(prefs.getString("notification_event_queue", "[]") ?: "[]")
        }.getOrElse { JSONArray() }

        val next = JSONArray()
        val keepFrom = (queue.length() - 49).coerceAtLeast(0)
        for (i in keepFrom until queue.length()) next.put(queue.get(i))

        next.put(
            JSONObject()
                .put("eventType", eventType)
                .put("packageName", packageName.take(200))
                .put("notificationKey", key.take(300))
                .put("observedAtMs", System.currentTimeMillis())
                .put("title", title?.take(240) ?: JSONObject.NULL)
                .put("text", text?.take(500) ?: JSONObject.NULL)
        )

        prefs.edit()
            .putString("notification_event_queue", next.toString())
            .putLong("last_notification_event_ms", System.currentTimeMillis())
            .apply()
    }
}
