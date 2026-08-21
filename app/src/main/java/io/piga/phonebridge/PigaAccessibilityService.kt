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
        val lastStateHash = prefs.getString("accessibility_last_state_hash", "").orEmpty().lowercase()
        if (lastPackage != targetPackage || lastStateHash != expectedStateHash) {
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

                val beforeState = stableState(targetPackage, root)
                val beforeStateHash = sha256(beforeState.toString())
                require(beforeStateHash == expectedStateHash) { "UI state changed; re-entry required." }

                val matches = root.findAccessibilityNodeInfosByViewId(targetViewId)
                require(matches.size == 1) { "Target view-id must resolve to exactly one node." }
                val node = matches.first()
                require(node.isEnabled && node.isClickable) { "Target node is not enabled/clickable." }

                require(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    "Android rejected accessibility click."
                }

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

                        val afterState = stableState(targetPackage, afterRoot)
                        val afterStateHash = sha256(afterState.toString())
                        val receipt = auditSnapshot(targetPackage, null, afterRoot, afterStateHash)
                        val evidenceHash = sha256(receipt.toString())

                        prefs.edit()
                            .putString("accessibility_last_snapshot", receipt.toString())
                            .putString("accessibility_last_hash", evidenceHash)
                            .putString("accessibility_last_state_hash", afterStateHash)
                            .putString("accessibility_last_package", targetPackage)
                            .putString("accessibility_last_time", Instant.now().toString())
                            .putString("accessibility_last_action", "click:$targetViewId")
                            .putString("accessibility_last_action_result", "succeeded")
                            .apply()
                        result = UiActionResult(
                            true,
                            "Governed UI click succeeded; postcondition verified; stateHash=$afterStateHash; evidenceHash=$evidenceHash"
                        )
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
        return if (configured.isEmpty()) setOf(packageName) else configured + packageName
    }

    private fun persistSnapshot(packageName: String, event: AccessibilityEvent, root: AccessibilityNodeInfo) {
        val state = stableState(packageName, root)
        val stateHash = sha256(state.toString())
        val receipt = auditSnapshot(packageName, event, root, stateHash)
        val evidenceHash = sha256(receipt.toString())
        prefs.edit()
            .putString("accessibility_last_snapshot", receipt.toString())
            .putString("accessibility_last_hash", evidenceHash)
            .putString("accessibility_last_state_hash", stateHash)
            .putString("accessibility_last_package", packageName)
            .putString("accessibility_last_time", Instant.now().toString())
            .apply()
    }

    private fun stableState(packageName: String, root: AccessibilityNodeInfo): JSONObject {
        val nodes = JSONArray()
        collect(root, nodes, depth = 0, budget = intArrayOf(80))
        return JSONObject()
            .put("schema", "piga.ui.state.v1")
            .put("packageName", packageName)
            .put("nodeCount", nodes.length())
            .put("nodes", nodes)
    }

    private fun auditSnapshot(
        packageName: String,
        event: AccessibilityEvent?,
        root: AccessibilityNodeInfo,
        stateHash: String
    ): JSONObject {
        val state = stableState(packageName, root)
        return JSONObject()
            .put("schema", "piga.ui.evidence.v1")
            .put("capturedAt", Instant.now().toString())
            .put("packageName", packageName)
            .put("stateHash", stateHash)
            .put("eventType", event?.eventType ?: 0)
            .put("className", event?.className?.toString().orEmpty().take(160))
            .put("nodeCount", state.getInt("nodeCount"))
            .put("nodes", state.getJSONArray("nodes"))
    }

    private fun collect(
        node: AccessibilityNodeInfo,
        out: JSONArray,
        depth: Int,
        budget: IntArray
    ) {
        if (budget[0] <= 0 || depth > 8) return
        budget[0]--
        out.put(
            JSONObject()
                .put("depth", depth)
                .put("class", node.className?.toString().orEmpty().take(120))
                .put("viewId", node.viewIdResourceName?.take(180).orEmpty())
                .put("text", minimize(node.text?.toString()))
                .put("description", minimize(node.contentDescription?.toString()))
                .put("clickable", node.isClickable)
                .put("enabled", node.isEnabled)
                .put("editable", node.isEditable)
        )
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
