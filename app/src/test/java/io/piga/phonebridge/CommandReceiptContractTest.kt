package io.piga.phonebridge

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandReceiptContractTest {
    @Test
    fun ackAdmissionCommitAndResultPathsAreCanonical() {
        assertEquals(
            "/api/bridge/devices/device-1/commands/command-9/ack",
            CommandReceiptContract.ackPath("device-1", "command-9")
        )
        assertEquals(
            "/api/bridge/devices/device-1/commands/command-9/admission",
            CommandReceiptContract.admissionPath("device-1", "command-9")
        )
        assertEquals(
            "/api/bridge/devices/device-1/commands/command-9/admission/commit",
            CommandReceiptContract.commitPath("device-1", "command-9")
        )
        assertEquals(
            "/api/bridge/devices/device-1/commands/command-9/result",
            CommandReceiptContract.resultPath("device-1", "command-9")
        )
    }

    @Test
    fun effectIntentRoundTripsAcrossPersistenceBoundary() {
        val original = CommandReceiptContract.EffectIntent(
            commandId = "command-9",
            commandNonce = "nonce-7",
            effectId = "effect-1",
            effectNonce = "effect-nonce-1",
            phase = "execution_started",
            createdAtMs = 123456789L,
            factoryEvidenceOnly = false
        )
        val restored = CommandReceiptContract.decodeEffectIntent(
            CommandReceiptContract.encodeEffectIntent(original)
        )
        assertEquals(original, restored)
    }

    @Test
    fun pendingResultRoundTripsAcrossPersistenceBoundary() {
        val original = CommandReceiptContract.PendingResult(
            commandId = "command-9",
            commandNonce = "nonce-7",
            status = "succeeded",
            detail = "effect executed\nreceipt pending",
            createdAtMs = 123456789L,
            jobId = "job-1",
            subjobId = "subjob-1",
            verifiedPlanHash = "a".repeat(64),
            resultCode = "local_allowlist_success",
            effectBlocked = false,
            effectId = "effect-1",
            effectNonce = "effect-nonce-1"
        )
        val restored = CommandReceiptContract.decodePendingResult(
            CommandReceiptContract.encodePendingResult(original)
        )
        assertNotNull(restored)
        assertEquals(original, restored)
    }

    @Test
    fun legacyFiveFieldPendingResultStillDecodes() {
        val restored = CommandReceiptContract.decodePendingResult(
            "command-9\nnonce-7\nsucceeded\n123456789\nlegacy detail"
        )
        assertNotNull(restored)
        requireNotNull(restored)
        assertEquals("command-9", restored.commandId)
        assertNull(restored.jobId)
        assertNull(restored.subjobId)
        assertNull(restored.verifiedPlanHash)
        assertNull(restored.effectId)
    }

    @Test
    fun legacyEightFieldPendingResultStillDecodes() {
        val encoded = listOf(
            "command-9",
            "nonce-7",
            "succeeded",
            "123456789",
            "job-1",
            "subjob-1",
            "a".repeat(64),
            "legacy detail"
        ).joinToString("\n")
        val restored = CommandReceiptContract.decodePendingResult(encoded)
        assertNotNull(restored)
        requireNotNull(restored)
        assertEquals("job-1", restored.jobId)
        assertEquals("subjob-1", restored.subjobId)
        assertNull(restored.effectId)
    }

    @Test
    fun malformedEffectIntentFailsClosed() {
        assertNull(CommandReceiptContract.decodeEffectIntent("broken"))
        assertNull(CommandReceiptContract.decodeEffectIntent(
            "command\nnonce\neffect\neffectnonce\nexecution_started\nnot-a-number\nfalse"
        ))
    }

    @Test
    fun factoryCorrelationRequiresCommandPlanAndActionSpecHashes() {
        val context = JSONObject()
            .put("jobId", "job-1")
            .put("subjobId", "subjob-1")
            .put("verifiedPlanHash", "b".repeat(64))
            .put("actionSpecHash", "c".repeat(64))
            .put("expectedCommandId", "piga-123")

        val parsed = CommandReceiptContract.parseFactoryCorrelation("piga-123", context)
        assertNotNull(parsed)
        requireNotNull(parsed)
        assertEquals("job-1", parsed.jobId)
        assertEquals("subjob-1", parsed.subjobId)
        assertTrue(parsed.verifiedPlanHash.matches(Regex("^[0-9a-f]{64}$")))
        assertTrue(parsed.actionSpecHash.matches(Regex("^[0-9a-f]{64}$")))

        assertThrows(IllegalArgumentException::class.java) {
            CommandReceiptContract.parseFactoryCorrelation("wrong-command", context)
        }

        val missingActionHash = JSONObject(context.toString()).apply { remove("actionSpecHash") }
        assertThrows(IllegalArgumentException::class.java) {
            CommandReceiptContract.parseFactoryCorrelation("piga-123", missingActionHash)
        }
    }
}
