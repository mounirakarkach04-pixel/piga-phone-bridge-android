package io.piga.phonebridge

/**
 * Pure decision layer for A7SEM-Reverse recovery.
 * Recovery is evidence-driven: restart only when the local runtime heartbeat is stale,
 * never confuse a remote/control-plane outage with a dead Android runtime, never bypass
 * local safety gates, and classify the reason for diagnostics.
 */
object BridgeRecoveryPolicy {
    const val STALE_AFTER_MS = 90_000L

    data class Snapshot(
        val paired: Boolean,
        val masterAutonomy: Boolean,
        val emergencyStop: Boolean,
        val runtimeHeartbeatMs: Long,
        val nowMs: Long
    )

    enum class Action {
        SKIP_UNPAIRED,
        SKIP_DISARMED,
        SKIP_EMERGENCY_STOP,
        HEALTHY_NOOP,
        RESTART_STALE_RUNTIME
    }

    fun decide(snapshot: Snapshot): Action {
        if (!snapshot.paired) return Action.SKIP_UNPAIRED
        if (snapshot.emergencyStop) return Action.SKIP_EMERGENCY_STOP
        if (!snapshot.masterAutonomy) return Action.SKIP_DISARMED

        val heartbeatMissing = snapshot.runtimeHeartbeatMs <= 0L
        val heartbeatStale = heartbeatMissing || snapshot.nowMs - snapshot.runtimeHeartbeatMs > STALE_AFTER_MS
        return if (heartbeatStale) Action.RESTART_STALE_RUNTIME else Action.HEALTHY_NOOP
    }
}
