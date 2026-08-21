package io.piga.phonebridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * PIGA accessibility evidence sensor with one deliberately narrow write path:
 * governed click by exact resource view-id.
 *
 * No coordinate clicks, gestures, text entry, global actions or screenshots.
 * Every click requires an allowlisted package, exact expected UI evidence hash,
 * exact target view-id, a clickable/enabled node and an optional postcondition.
 */
class PigaAccessibilityService : AccessibilityService() {
    private val prefs by lazy { getSharedPreferences("piga_bridge", MODE_PRIVATE) }
    private val mainHandler = Handler(Looper.getMainLooper())

    data class UiActionResult(val ok: Boolean, val detail: String)

    companion object {
        @Volatile private var activeInstance: PigaAccessibilityService? = null

        fun governedClick(payload: JSONObject): UiActionResult {
            val instance = activeInstance
                ?: return UiActionResult(false, "Accessibility service unavailable.")
            return instance.executeGovernedClick(payload)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
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
        persistSnapshot(packageName, event, root)
    }

    override fun onInterrupt() {
        prefs.edit().putString("accessibility_status", "INTERRUPTED").apply()
    }

    override fun onDestroy() {
        activeInstance = null
        prefs.edit().putBoolean("accessibility_connected", false).apply()
        super.onDestroy()
    }

    private fun executeGovernedClick(payload: JSONObject): UiActionResult {
        if (!gateOpen()) return UiActionResult(false, "Local PIGA safety gate blocked UI action.")

        val targetPackage = payload.optString("packageName").trim()
        val targetViewId = payload.optString("viewId").trim()
        val expectedStateHash = payload.optString("expectedStateHash").trim().lowercase()
        val postconditionViewId = payload.optString("postconditionViewId").trim()

        if (!targetPackage.matches(Regex("^[A-Za-z0-9_.]{3,200}$"))) {
            return UiActionResult(false, "Invalid target package.")
        }
        if (targetPackage !in allowedPackages()) {
            return UiActionResult(false, "Target package is not PIGA-allowlisted.")
        }
        if (targetViewId.isBlank() || targetViewId.length > 220) {
            return UiActionResult(false, "Invalid target view-id.")
        }
        if (!expectedStateHash.matches(Regex("^[a-f0-9]{64}$"))) {
            return UiActionResult(false, "Expected UI state hash is required.")
        }

        val lastPackage = prefs.getString("accessibility_last_package", "").orEmpty()
        val lastHash = prefs.getString("accessibility_last_hash", "").orEmpty().lowercase()
        if (lastPackage != targetPackage || lastHash != expectedStateHash) {
            return UiActionResult(false, "Material UI state change detected; re-entry required.")
        }

        val latch = CountDownLatch(1)
        var result = UiActionResult(false, "UI action did not execute.")
        mainHandler.post {
            try {
                val root = rootInActiveWindow
                    ?: throw IllegalStateException("No active accessibility window.")
                val activePackage = root.packageName?.toString().orEmpty()
                require(activePackage == targetPackage) { "Foreground package changed; re-entry required." }

                val before = snapshotWithoutEvent(targetPackage, root)
                val beforeHash = sha256(before.toString())
                require(beforeHash == expectedStateHash) { "UI evidence hash changed; re-entry required." }

                val matches = root.findAccessibilityNodeInfosByViewId(targetViewId)
                require(matches.size == 1) { "Target view-id must resolve to exactly one node." }
                val node = matches.first()
                require(node.isEnabled && node.isClickable) { "Target node is not enabled/clickable." }

                val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                require(clicked) { "Android rejected accessibility click." }

                mainHandler.postDelayed({
                    try {
                        val afterRoot = rootInActiveWindow
                            ?: throw IllegalStateException("No active window after click.")
                        val afterPackage = afterRoot.packageName?.toString().orEmpty()
                        require(afterPackage == targetPackage) { "Postcondition package mismatch." }

                        if (postconditionViewId.isNotBlank()) {
                            val postMatches = afterRoot.findAccessibilityNodeInfosByViewId(postconditionViewId)
                            require(postMatches.isNotEmpty()) { "Postcondition view-id not observed." }
                        }

                        val after = snapshotWithoutEvent(targetPackage, afterRoot)
                        val afterHash = sha256(after.toString())
                        prefs.edit()
                            .putString("accessibility_last_snapshot", after.toString())
                            .putString("accessibility_last_hash", afterHash)
                            .putString("accessibility_last_package", targetPackage)
                            .putString("accessibility_last_time", Instant.now().toString())
                            .putString("accessibility_last_action", "click:$targetViewId")
                            .putString("accessibility_last_action_result", "succeeded")
                            .apply()
                        result = UiActionResult(true, "Governed UI click succeeded; postcondition verified; stateHash=$afterHash")
                    } catch (e: Exception) {
                        prefs.edit().putString("accessibility_last_action_result", "failed:${e.message}").apply()
                        result = UiActionResult(false, e.message ?: "Postcondition verification failed.")
                    } finally {
                        latch.countDown()
                    }
                }, 450L)
            } catch (e: Exception) {
                prefs.edit().putString("accessibility_last_action_result", "failed:${e.message}").apply()
                result = UiActionResult(false, e.message ?: "Governed UI click failed.")
                latch.countDown()
            }
        }

        if (!latch.await(3, TimeUnit.SECONDS)) {
            return UiActionResult(false, "Timed out waiting for UI postcondition.")
        }
        return result
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
        // the service can only inspect/click inside the PIGA app itself.
        return if (configured.isEmpty()) setOf(packageName) else configured + packageName
    }

    private fun persistSnapshot(packageName: String, event: AccessibilityEvent, root: AccessibilityNodeInfo) {
        val snapshot = snapshot(packageName, event, root)
        prefs.edit()
            .putString("accessibility_last_snapshot", snapshot.toString())
            .putString("accessibility_last_hash", sha256(snapshot.toString()))
            .putString("accessibility_last_package", packageName)
            .putString("accessibility_last_time", Instant.now().toString())
            .apply()
    }

    private fun snapshot(
        packageName: String,
        event: AccessibilityEvent,
        root: AccessibilityNodeInfo
    ): JSONObject {
        val base = snapshotWithoutEvent(packageName, root)
        base.put("eventType", event.eventType)
        base.put("className", event.className?.toString().orEmpty().take(160))
        return base
    }

    private fun snapshotWithoutEvent(packageName: String, root: AccessibilityNodeInfo): JSONObject {
        val nodes = JSONArray()
        collect(root, nodes, depth = 0, budget = intArrayOf(80))
        return JSONObject()
            .put("schema", "piga.ui.snapshot.v1")
            .put("capturedAt", Instant.now().toString())
            .put("packageName", packageName)
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
        return text.replace(Regex("\\s+"), " ").take(240)
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
