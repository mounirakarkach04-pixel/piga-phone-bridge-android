package io.piga.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Test

class BridgeRecoveryPolicyTest {
    private val now = 1_000_000L

    @Test
    fun unpairedDeviceNeverRestarts() {
        val action = BridgeRecoveryPolicy.decide(
            BridgeRecoveryPolicy.Snapshot(false, true, false, 0L, now)
        )
        assertEquals(BridgeRecoveryPolicy.Action.SKIP_UNPAIRED, action)
    }

    @Test
    fun emergencyStopDominatesAutonomy() {
        val action = BridgeRecoveryPolicy.decide(
            BridgeRecoveryPolicy.Snapshot(true, true, true, 0L, now)
        )
        assertEquals(BridgeRecoveryPolicy.Action.SKIP_EMERGENCY_STOP, action)
    }

    @Test
    fun disarmedDeviceNeverRestarts() {
        val action = BridgeRecoveryPolicy.decide(
            BridgeRecoveryPolicy.Snapshot(true, false, false, 0L, now)
        )
        assertEquals(BridgeRecoveryPolicy.Action.SKIP_DISARMED, action)
    }

    @Test
    fun freshRuntimeHeartbeatIsNoop() {
        val action = BridgeRecoveryPolicy.decide(
            BridgeRecoveryPolicy.Snapshot(true, true, false, now - 30_000L, now)
        )
        assertEquals(BridgeRecoveryPolicy.Action.HEALTHY_NOOP, action)
    }

    @Test
    fun staleRuntimeHeartbeatRequestsRestart() {
        val action = BridgeRecoveryPolicy.decide(
            BridgeRecoveryPolicy.Snapshot(true, true, false, now - BridgeRecoveryPolicy.STALE_AFTER_MS - 1L, now)
        )
        assertEquals(BridgeRecoveryPolicy.Action.RESTART_STALE_RUNTIME, action)
    }

    @Test
    fun missingRuntimeHeartbeatRequestsRestartWhenAdmitted() {
        val action = BridgeRecoveryPolicy.decide(
            BridgeRecoveryPolicy.Snapshot(true, true, false, 0L, now)
        )
        assertEquals(BridgeRecoveryPolicy.Action.RESTART_STALE_RUNTIME, action)
    }
}
