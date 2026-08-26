package io.piga.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CommandExecutionJournalTest {
    @Test
    fun roundTripPreservesCorrelationEvidence() {
        val entry = CommandExecutionJournal.Entry(
            commandId = "cmd-42",
            commandNonce = "nonce-42",
            startedAtMs = 123456L,
            jobId = "job-7",
            subjobId = "subjob-3",
            verifiedPlanHash = "a".repeat(64)
        )

        val decoded = CommandExecutionJournal.decode(CommandExecutionJournal.encode(entry))
        assertNotNull(decoded)
        assertEquals(entry, decoded)
    }

    @Test
    fun malformedJournalFailsClosed() {
        assertEquals(null, CommandExecutionJournal.decode("broken"))
    }

    @Test
    fun uncertainRecoveryKeepsCommandAndFactoryCorrelation() {
        val entry = CommandExecutionJournal.Entry(
            commandId = "cmd-99",
            commandNonce = "nonce-99",
            startedAtMs = 999L,
            jobId = "job-1",
            subjobId = "subjob-2",
            verifiedPlanHash = "b".repeat(64)
        )

        val result = CommandExecutionJournal.uncertainResult(entry)
        assertEquals("cmd-99", result.commandId)
        assertEquals("nonce-99", result.commandNonce)
        assertEquals("uncertain", result.status)
        assertEquals("job-1", result.jobId)
        assertEquals("subjob-2", result.subjobId)
        assertEquals("b".repeat(64), result.verifiedPlanHash)
    }
}
