package io.piga.phonebridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant

/**
 * Read-only PIGA accessibility evidence sensor.
 *
 * This service deliberately does not perform clicks, gestures, text entry,
 * global actions, or screenshots. It only records a bounded, minimized view
 * of the active UI when local PIGA gates are satisfied and the foreground
 * package is explicitly allowlisted.
 */
class PigaAccessibilityService : AccessibilityService() {
    private val prefs by lazy { getSharedPreferences("piga_bridge", MODE_PRIVATE) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_FOCUSED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 250L
            packageNames = allowedPackages().toTypedArray()
        }
        prefs.edit()
            .putBoolean("accessibility_connected", true)
            .putString("accessibility_connected_at", Instant.now().toString())
            .apply()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !gateOpen()) return
        val packageName = event.packageName?.toString()?.trim().orEmpty()
        if (packageName.isBlank() || packageName !in allowedPackages()) return

        val root = rootInActiveWindow ?: return
        val snapshot = snapshot(packageName, event, root)
        prefs.edit()
            .putString("accessibility_last_snapshot", snapshot.toString())
            .putString("accessibility_last_hash", sha256(snapshot.toString()))
            .putString("accessibility_last_package", packageName)
            .putString("accessibility_last_time", Instant.now().toString())
            .apply()
    }

    override fun onInterrupt() {
        prefs.edit().putString("accessibility_status", "INTERRUPTED").apply()
    }

    override fun onDestroy() {
        prefs.edit().putBoolean("accessibility_connected", false).apply()
        super.onDestroy()
    }

    private fun gateOpen(): Boolean =
        prefs.getBoolean("paired", false) &&
            prefs.getBoolean("master_autonomy", false) &&
            !prefs.getBoolean("emergency_stop", false)

    private fun allowedPackages(): Set<String> {
        val configured = prefs.getString("accessibility_allow_packages", null)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.matches(Regex("^[A-Za-z0-9_.]{3,200}$")) }
            ?.toSet()
            .orEmpty()
        // Fail closed: until a governed allowlist is explicitly provisioned,
        // the service can only inspect the PIGA app itself.
        return if (configured.isEmpty()) setOf(packageName) else configured + packageName
    }

    private fun snapshot(
        packageName: String,
        event: AccessibilityEvent,
        root: AccessibilityNodeInfo
    ): JSONObject {
        val nodes = JSONArray()
        collect(root, nodes, depth = 0, budget = intArrayOf(80))
        return JSONObject()
            .put("schema", "piga.ui.snapshot.v1")
            .put("capturedAt", Instant.now().toString())
            .put("packageName", packageName)
            .put("eventType", event.eventType)
            .put("className", event.className?.toString().orEmpty().take(160))
            .put("nodeCount", nodes.length())
            .put("nodes", nodes)
    }

    private fun collect(
        node: AccessibilityNodeInfo,
        out: JSONArray,
        depth: Int,
        budget: IntArray
    ) {
        if (budget[0] <= 0 || depth > 8) return
        budget[0]--

        val item = JSONObject()
            .put("depth", depth)
            .put("class", node.className?.toString().orEmpty().take(120))
            .put("viewId", node.viewIdResourceName?.take(180).orEmpty())
            .put("text", minimize(node.text?.toString()))
            .put("description", minimize(node.contentDescription?.toString()))
            .put("clickable", node.isClickable)
            .put("enabled", node.isEnabled)
            .put("editable", node.isEditable)
        out.put(item)

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collect(child, out, depth + 1, budget)
        }
    }

    private fun minimize(value: String?): String {
        val text = value?.trim().orEmpty()
        if (text.isBlank()) return ""
        // Evidence minimization: bounded UI text, never an unbounded screen dump.
        return text.replace(Regex("\\s+"), " ").take(240)
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
