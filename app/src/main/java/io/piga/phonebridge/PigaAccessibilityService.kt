package io.piga.phonebridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.Locale

/**
 * Explicit, user-enabled execution edge for bounded UI work on the owner's device.
 * Signal/visibility is evidence, never authority: server admission and the local
 * Master-Autonomy/Emergency-Stop gates still run before this service is called.
 */
class PigaAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile private var active: PigaAccessibilityService? = null

        private val allowedPackages = setOf(
            "com.whatsapp",
            "com.linkedin.android",
            "com.android.chrome",
            "com.sec.android.app.sbrowser",
            "com.pigapocket.enterprise",
            "com.pigapocket.bridge",
        )
        private val formPackages = setOf(
            "com.android.chrome",
            "com.sec.android.app.sbrowser",
            "com.pigapocket.enterprise",
        )
        private val messagingPackages = setOf("com.whatsapp", "com.linkedin.android")

        fun isConnected(): Boolean = active != null

        fun performNavigation(payload: JSONObject): String =
            requireActive().performNavigationInternal(payload)

        fun fillForm(payload: JSONObject): String =
            requireActive().fillFormInternal(payload)

        fun confirmMessageSend(payload: JSONObject): String =
            requireActive().confirmMessageSendInternal(payload)

        fun confirmFormSubmit(payload: JSONObject): String =
            requireActive().confirmFormSubmitInternal(payload)

        fun performHeadsetHook(): Boolean {
            if (Build.VERSION.SDK_INT < 31) return false
            return active?.performGlobalAction(GLOBAL_ACTION_KEYCODE_HEADSETOOK) == true
        }

        private fun requireActive(): PigaAccessibilityService =
            active ?: throw IllegalStateException("PIGA Accessibility is not enabled.")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 60L
        }
        active = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (active === this) active = null
        super.onDestroy()
    }

    private fun performNavigationInternal(payload: JSONObject): String {
        require(payload.optString("effectClass") == "navigation_only") {
            "General UI actions are admitted only as navigation_only."
        }
        return when (val action = payload.optString("action").trim()) {
            "back" -> global(GLOBAL_ACTION_BACK, "Zurück")
            "home" -> global(GLOBAL_ACTION_HOME, "Startbildschirm")
            "recents" -> global(GLOBAL_ACTION_RECENTS, "Letzte Apps")
            "click_text" -> {
                val root = validatedRoot(payload, allowedPackages)
                val text = bounded(payload.optString("text"), 1, 120, "UI selector")
                require(!looksConsequential(text)) { "Consequential controls require a dedicated capability." }
                clickUniqueExact(root, text)
                "UI-Navigation '$text' ausgeführt."
            }
            "set_text" -> {
                val root = validatedRoot(payload, allowedPackages)
                val value = bounded(payload.optString("value"), 0, 4000, "UI text")
                val node = findUniqueEditable(root, payload)
                require(!node.isPassword && !looksSecretField(node)) { "Secret/password fields are never filled by the generic UI lane." }
                setNodeText(node, value)
                "Text lokal in das zugelassene Feld eingesetzt."
            }
            "scroll_forward", "scroll_backward" -> {
                val root = validatedRoot(payload, allowedPackages)
                val candidates = allNodes(root).filter { it.isVisibleToUser && it.isScrollable }
                require(candidates.size == 1) { "Scrollable target is ambiguous (${candidates.size})." }
                val ok = candidates.single().performAction(
                    if (action == "scroll_forward") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
                )
                require(ok) { "Scroll action was rejected by the active app." }
                "Scroll-Navigation ausgeführt."
            }
            else -> throw IllegalArgumentException("Unsupported navigation action.")
        }
    }

    private fun fillFormInternal(payload: JSONObject): String {
        val root = validatedRoot(payload, formPackages)
        val fields = payload.optJSONArray("fields") ?: throw IllegalArgumentException("Form fields missing.")
        require(fields.length() in 1..30) { "Form field count must be 1..30." }
        var filled = 0
        for (index in 0 until fields.length()) {
            val field = fields.optJSONObject(index) ?: throw IllegalArgumentException("Invalid form field at $index.")
            val value = bounded(field.optString("value"), 0, 8000, "Form value")
            val node = findUniqueEditable(root, field)
            require(!node.isPassword && !looksSecretField(node)) {
                "Password, PIN, OTP and card-security fields stay human-reserved."
            }
            setNodeText(node, value)
            filled += 1
        }
        return JSONObject().put("filled", filled).put("submitted", false).toString()
    }

    private fun confirmMessageSendInternal(payload: JSONObject): String {
        val root = validatedRoot(payload, messagingPackages)
        val recipient = bounded(payload.optString("expectedRecipient"), 1, 160, "Expected recipient")
        val body = bounded(payload.optString("expectedBody"), 1, 8000, "Expected message")
        require(hasVisibleExactOrContained(root, recipient)) { "Expected recipient is not visible; send blocked." }
        require(hasVisibleExactOrContained(root, body)) { "Expected message body is not visible; send blocked." }
        val button = payload.optString("sendButtonText", "Send").trim().ifBlank { "Send" }
        require(button.equals("send", true) || button.equals("senden", true)) { "Only an explicit Send/Senden control is admitted." }
        clickUniqueExact(root, button)
        return "Nachricht nach Empfänger- und Inhalts-Readback gesendet."
    }

    private fun confirmFormSubmitInternal(payload: JSONObject): String {
        val root = validatedRoot(payload, formPackages)
        val marker = bounded(payload.optString("expectedFormMarker"), 3, 240, "Form marker")
        require(hasVisibleExactOrContained(root, marker)) { "Expected form marker is not visible; submit blocked." }
        val button = bounded(payload.optString("submitButtonText"), 2, 120, "Submit control")
        val admitted = setOf("submit", "absenden", "antrag absenden", "send application", "send application now")
        require(button.lowercase(Locale.ROOT) in admitted) { "Submit control is outside the admitted vocabulary." }
        clickUniqueExact(root, button)
        return "Formular nach sichtbarem Formular-Readback abgesendet."
    }

    private fun validatedRoot(payload: JSONObject, allowed: Set<String>): AccessibilityNodeInfo {
        val expected = bounded(payload.optString("packageName"), 3, 200, "Package name")
        require(expected in allowed) { "Package is not on the local PIGA allowlist." }
        val root = rootInActiveWindow ?: throw IllegalStateException("No active accessibility window.")
        val actual = root.packageName?.toString().orEmpty()
        require(actual == expected) { "Active package mismatch: expected $expected, observed $actual." }
        return root
    }

    private fun global(action: Int, label: String): String {
        require(performGlobalAction(action)) { "$label action was rejected by Android." }
        return "$label-Navigation ausgeführt."
    }

    private fun findUniqueEditable(root: AccessibilityNodeInfo, selector: JSONObject): AccessibilityNodeInfo {
        val viewId = selector.optString("viewId").trim()
        val label = selector.optString("label").trim()
        val candidates = when {
            viewId.isNotBlank() -> root.findAccessibilityNodeInfosByViewId(viewId)
            label.isNotBlank() -> allNodes(root).filter { nodeMatches(it, label) }
            else -> throw IllegalArgumentException("A viewId or label is required for text entry.")
        }.filter { it.isVisibleToUser && it.isEnabled && it.isEditable }
        require(candidates.size == 1) { "Editable target is ambiguous (${candidates.size})." }
        return candidates.single()
    }

    private fun setNodeText(node: AccessibilityNodeInfo, value: String) {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        require(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            "Active app rejected text entry."
        }
    }

    private fun clickUniqueExact(root: AccessibilityNodeInfo, label: String) {
        val direct = allNodes(root).filter { it.isVisibleToUser && nodeMatches(it, label, exact = true) }
        val clickable = direct.mapNotNull { clickableAncestor(it) }.distinctBy { System.identityHashCode(it) }
        require(clickable.size == 1) { "Clickable target '$label' is ambiguous (${clickable.size})." }
        require(clickable.single().performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            "Active app rejected click."
        }
    }

    private fun clickableAncestor(start: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var node: AccessibilityNodeInfo? = start
        var depth = 0
        while (node != null && depth < 6) {
            if (node.isVisibleToUser && node.isEnabled && node.isClickable) return node
            node = node.parent
            depth += 1
        }
        return null
    }

    private fun hasVisibleExactOrContained(root: AccessibilityNodeInfo, expected: String): Boolean {
        val normalized = normalize(expected)
        return allNodes(root).any {
            if (!it.isVisibleToUser) false
            else listOf(it.text, it.contentDescription, it.hintText).any { value ->
                val observed = normalize(value?.toString().orEmpty())
                observed == normalized || (normalized.length >= 8 && observed.contains(normalized))
            }
        }
    }

    private fun nodeMatches(node: AccessibilityNodeInfo, expected: String, exact: Boolean = false): Boolean {
        val target = normalize(expected)
        return listOf(node.text, node.contentDescription, node.hintText).any { value ->
            val observed = normalize(value?.toString().orEmpty())
            if (exact) observed == target else observed == target || observed.contains(target)
        }
    }

    private fun allNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && out.size < 2500) {
            val node = queue.removeFirst()
            out.add(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return out
    }

    private fun looksSecretField(node: AccessibilityNodeInfo): Boolean {
        val material = listOf(node.viewIdResourceName, node.text, node.hintText, node.contentDescription)
            .joinToString(" ") { it?.toString().orEmpty() }
            .lowercase(Locale.ROOT)
        return listOf("password", "passwort", "pin", "otp", "one time", "cvv", "cvc", "security code", "sicherheitscode")
            .any(material::contains)
    }

    private fun looksConsequential(label: String): Boolean {
        val value = normalize(label)
        return listOf(
            "send", "senden", "submit", "absenden", "pay", "bezahlen", "purchase", "kaufen",
            "book", "buchen", "publish", "veröffentlichen", "post", "delete", "löschen",
            "confirm", "bestätigen", "call", "anrufen",
        ).any { value == it || value.startsWith("$it ") }
    }

    private fun normalize(value: String): String = value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    private fun bounded(value: String, min: Int, max: Int, name: String): String {
        val trimmed = value.trim()
        require(trimmed.length in min..max) { "$name length invalid." }
        return trimmed
    }
}
