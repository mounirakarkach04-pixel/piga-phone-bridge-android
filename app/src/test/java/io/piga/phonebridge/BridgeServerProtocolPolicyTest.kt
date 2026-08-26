package io.piga.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Test

class BridgeServerProtocolPolicyTest {
    @Test
    fun externalEffectsRequireAuthoritativeAdmission() {
        assertEquals(true, BridgeServerProtocolPolicy.requiresAdmission("local_notification"))
        assertEquals(true, BridgeServerProtocolPolicy.requiresAdmission("clipboard_write"))
        assertEquals(true, BridgeServerProtocolPolicy.requiresAdmission("url_intent"))
        assertEquals(true, BridgeServerProtocolPolicy.requiresAdmission("text_to_speech"))
        assertEquals(true, BridgeServerProtocolPolicy.requiresAdmission("supported_app_launch"))
        assertEquals(true, BridgeServerProtocolPolicy.requiresAdmission("share_text"))
    }

    @Test
    fun pureVerificationDoesNotCrossDeviceEffectBoundary() {
        assertEquals(false, BridgeServerProtocolPolicy.requiresAdmission("orchestration_plan_verify"))
    }
}
