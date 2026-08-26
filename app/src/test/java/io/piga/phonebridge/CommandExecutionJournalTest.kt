package io.piga.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CommandExecutionJournalTest {
    @Test
    fun roundTripPreservesCorrelationAndAuthoritativeEffectIdentity() {
        val entry = CommandExecutionJournal.Entry(
            commandId = "cmd-42",
            commandNonce = "nonce-42",
            startedAtMs = 123456L,
            jobId = "job-7",
            subjobId = "subjob-3",
            verifiedPlanHash = "a".repeat(64),
            effectId = "effect-42",
            effectNonce = "effect-nonce-42"
        )

        val decoded = CommandExecutionJournal.decode(CommandExecutionJournal.encode(entry))
        assertNotNull(decoded)
        assertEquals(entry, decoded)
    }

    @Test
    fun legacySixFieldJournalStillDecodesFailClosedWithoutInventingTokens() {
        val decoded = CommandExecutionJournal.decode(
            "cmd-42\nnonce-42\n123456\njob-7\nsubjob-3\n${"a".repeat(64)}"
        )
        assertNotNull(decoded)
        requireNotNull(decoded)
        assertEquals(null, decoded.effectId)
        assertEquals(null, decoded.effectNonce)
    }

    @Test
    fun malformedJournalFailsClosed() {
        assertEquals(null, CommandExecutionJournal.decode("broken"))
    }

    @Test
    fun uncertainRecoveryKeepsCommandFactoryCorrelationAndEffectIdentity() {
        val entry = CommandExecutionJournal.Entry(
            commandId = "cmd-99",
            commandNonce = "nonce-99",
            startedAtMs = 999L,
            jobId = "job-1",
            subjobId = "subjob-2",
            verifiedPlanHash = "b".repeat(64),
            effectId = "effect-99",
            effectNonce = "effect-nonce-99"
        )

        val result = CommandExecutionJournal.uncertainResult(entry)
        assertEquals("cmd-99", result.commandId)
        assertEquals("nonce-99", result.commandNonce)
        assertEquals("uncertain", result.status)
        assertEquals("job-1", result.jobId)
        assertEquals("subjob-2", result.subjobId)
        assertEquals("b".repeat(64), result.verifiedPlanHash)
        assertEquals("effect-99", result.effectId)
        assertEquals("effect-nonce-99", result.effectNonce)
    }
}
