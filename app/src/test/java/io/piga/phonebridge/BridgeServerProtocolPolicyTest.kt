package io.piga.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Test

class BridgeServerProtocolPolicyTest {
    @Test
    fun everyDeliveredCommandRequiresAuthoritativeAdmission() {
        val types = listOf(
            "local_notification",
            "clipboard_write",
            "url_intent",
            "text_to_speech",
            "supported_app_launch",
            "share_text",
            "orchestration_plan_verify"
        )
        types.forEach { type ->
            assertEquals(type, true, BridgeServerProtocolPolicy.requiresAdmission(type))
        }
        assertEquals(false, BridgeServerProtocolPolicy.requiresAdmission("unknown"))
    }

    @Test
    fun uncertainMapsToCanonicalServerQuarantineStatus() {
        assertEquals("unknown_requires_review", BridgeServerProtocolPolicy.wireResultStatus("uncertain"))
        assertEquals("effect_state_uncertain_after_runtime_loss", BridgeServerProtocolPolicy.resultCode("uncertain"))
    }
}
