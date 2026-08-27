package io.piga.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Test

class CommandExecutionPolicyTest {
    @Test
    fun pendingResultIsRedeliveredWithoutExecution() {
        assertEquals(
            CommandExecutionPolicy.Decision.REDELIVER_PENDING_RESULT,
            CommandExecutionPolicy.decide(
                hasPendingResult = true,
                hasExecutionJournal = true,
                nonceReplay = true
            )
        )
    }

    @Test
    fun ambiguousEffectIsReportedUncertainWithoutReplay() {
        assertEquals(
            CommandExecutionPolicy.Decision.REPORT_UNCERTAIN,
            CommandExecutionPolicy.decide(
                hasPendingResult = false,
                hasExecutionJournal = true,
                nonceReplay = true
            )
        )
    }

    @Test
    fun nonceReplayWithoutJournalIsRejected() {
        assertEquals(
            CommandExecutionPolicy.Decision.REJECT_NONCE_REPLAY,
            CommandExecutionPolicy.decide(
                hasPendingResult = false,
                hasExecutionJournal = false,
                nonceReplay = true
            )
        )
    }

    @Test
    fun freshCommandCanExecute() {
        assertEquals(
            CommandExecutionPolicy.Decision.EXECUTE,
            CommandExecutionPolicy.decide(
                hasPendingResult = false,
                hasExecutionJournal = false,
                nonceReplay = false
            )
        )
    }
}
