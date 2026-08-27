package io.piga.phonebridge

/**
 * Pure protocol boundary between the Android executor and the authoritative
 * PIGA control plane. Every command delivered by the bridge server must pass
 * server-side admission/commit before the local executor performs work or
 * reports a terminal result.
 */
internal object BridgeServerProtocolPolicy {
    private val admittedCommandTypes = setOf(
        "local_notification",
        "clipboard_write",
        "url_intent",
        "text_to_speech",
        "supported_app_launch",
        "share_text",
        "orchestration_plan_verify"
    )

    fun requiresAdmission(type: String): Boolean = type in admittedCommandTypes

    fun wireResultStatus(localStatus: String): String = when (localStatus) {
        "succeeded" -> "succeeded"
        "failed" -> "failed"
        "uncertain", "unknown_requires_review" -> "unknown_requires_review"
        else -> throw IllegalArgumentException("Unsupported local bridge result status: $localStatus")
    }

    fun resultCode(localStatus: String): String = when (localStatus) {
        "succeeded" -> "bridge_effect_succeeded"
        "failed" -> "bridge_effect_failed"
        "uncertain", "unknown_requires_review" -> "effect_state_uncertain_after_runtime_loss"
        else -> throw IllegalArgumentException("Unsupported local bridge result status: $localStatus")
    }
}
