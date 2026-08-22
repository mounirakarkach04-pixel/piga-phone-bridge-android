package io.piga.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CommandReceiptContractTest {
    @Test
    fun ackPathIncludesAckSuffix() {
        assertEquals(
            "/api/bridge/devices/device-1/commands/command-9/ack",
            CommandReceiptContract.ackPath("device-1", "command-9")
        )
    }

    @Test
    fun resultPathIncludesResultSuffix() {
        assertEquals(
            "/api/bridge/devices/device-1/commands/command-9/result",
            CommandReceiptContract.resultPath("device-1", "command-9")
        )
    }

    @Test
    fun pendingResultRoundTripsAcrossPersistenceBoundary() {
        val original = CommandReceiptContract.PendingResult(
            commandId = "command-9",
            commandNonce = "nonce-7",
            status = "succeeded",
            detail = "effect executed\nreceipt pending",
            createdAtMs = 123456789L
        )

        val restored = CommandReceiptContract.decodePendingResult(
            CommandReceiptContract.encodePendingResult(original)
        )

        assertNotNull(restored)
        assertEquals(original, restored)
    }
}
