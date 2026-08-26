package io.piga.phonebridge

/**
 * A7SEM Reverse recovery policy for commands that can create an external device effect.
 *
 * The important distinction is between a command that is merely being delivered again
 * and a command for which a durable local marker proves that execution was allowed to
 * cross the device-effect boundary. Once that boundary may have been crossed we never
 * execute the same command/nonce automatically again.
 */
internal object CommandExecutionPolicy {
    enum class Decision {
        REDELIVER_PENDING_RESULT,
        REPORT_UNCERTAIN,
        REJECT_NONCE_REPLAY,
        EXECUTE
    }

    fun decide(
        hasPendingResult: Boolean,
        hasExecutionJournal: Boolean,
        nonceReplay: Boolean
    ): Decision = when {
        hasPendingResult -> Decision.REDELIVER_PENDING_RESULT
        hasExecutionJournal -> Decision.REPORT_UNCERTAIN
        nonceReplay -> Decision.REJECT_NONCE_REPLAY
        else -> Decision.EXECUTE
    }
}
