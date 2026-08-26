package io.piga.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFailsWith
import org.junit.Test

class BridgeRuntimeRequestContractTest {
    @Test
    fun canonical_message_matches_enterprise_contract() {
        val message = BridgeRuntimeRequestContract.canonicalMessage(
            method = "post",
            apiPath = "/api/bridge/devices/device-1/safety",
            timestampMs = "1770000000123",
            counter = "41",
            requestId = "request-1",
            body = "{\"emergencyStop\":true}",
            controlPlaneOrigin = "https://pigapocket.com",
        )

        assertEquals(
            "POST\n/bridge/devices/device-1/safety\n1770000000123\n41\nrequest-1\n{\"emergencyStop\":true}\nhttps://pigapocket.com",
            message,
        )
    }

    @Test
    fun counter_is_monotonic_and_server_safe() {
        assertEquals(1L, BridgeRuntimeRequestContract.nextCounter(0L))
        assertEquals(42L, BridgeRuntimeRequestContract.nextCounter(41L))
        assertFailsWith<IllegalArgumentException> {
            BridgeRuntimeRequestContract.nextCounter(9_007_199_254_740_991L)
        }
    }
}
