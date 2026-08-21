package io.piga.phonebridge

import android.content.Context
import org.json.JSONObject
import java.time.Instant

/**
 * Executes a bounded, pre-admitted UI sequence with deterministic checkpoint/resume.
 *
 * The sequence is non-generative on-device: every step is supplied in advance.
 * A checkpoint is persisted only after a step succeeds and is verified. Resume is
 * admitted only when sequenceId, sequence fingerprint, next step and current UI
 * state all match the persisted checkpoint. Any mismatch requires re-entry.
 */
object GovernedUiSequence {
    data class SequenceResult(
        val ok: Boolean,
        val completedSteps: Int,
        val detail: String
    )

    fun execute(payload: JSONObject): SequenceResult {
        val context = PigaApplication.contextOrNull()
            ?: return SequenceResult(false, 0, "Application context unavailable; re-entry required.")
        return execute(context, payload)
    }

    fun execute(context: Context, payload: JSONObject): SequenceResult {
        val sequenceId = payload.optString("sequenceId").trim()
        if (!sequenceId.matches(Regex("^[A-Za-z0-9._:-]{8,120}$"))) {
            return SequenceResult(false, 0, "Valid sequenceId is required.")
        }

        val steps = payload.optJSONArray("steps")
            ?: return SequenceResult(false, 0, "Sequence steps missing.")
        if (steps.length() !in 1..8) {
            return SequenceResult(false, 0, "Sequence must contain 1..8 admitted steps.")
        }

        val prefs = context.getSharedPreferences("piga_bridge", Context.MODE_PRIVATE)
        val fingerprint = sha256(steps.toString())
        val prefix = "ui_sequence_${sha256(sequenceId).take(24)}"
        val savedId = prefs.getString("${prefix}_id", null)
        val savedFingerprint = prefs.getString("${prefix}_fingerprint", null)
        val savedNextStep = prefs.getInt("${prefix}_next_step", 0)
        val savedStateHash = prefs.getString("${prefix}_state_hash", null)
        val savedStatus = prefs.getString("${prefix}_status", null)

        var startIndex = 0
        if (savedStatus == "in_progress") {
            if (savedId != sequenceId || savedFingerprint != fingerprint) {
                return SequenceResult(false, 0, "Checkpoint identity/fingerprint mismatch; re-entry required.")
            }
            if (savedNextStep !in 0..steps.length()) {
                return SequenceResult(false, 0, "Checkpoint step index invalid; re-entry required.")
            }
            if (savedNextStep == steps.length()) {
                clearCheckpoint(prefs, prefix)
                return SequenceResult(true, steps.length(), "Sequence checkpoint already completed.")
            }
            val observedState = prefs.getString("accessibility_last_state_hash", "").orEmpty().lowercase()
            if (!savedStateHash.isNullOrBlank() && observedState != savedStateHash.lowercase()) {
                return SequenceResult(false, savedNextStep, "Material UI change since checkpoint; re-entry required.")
            }
            startIndex = savedNextStep
        } else if (savedStatus == "blocked") {
            return SequenceResult(false, savedNextStep, "Sequence is blocked; explicit re-entry is required before retry.")
        } else {
            prefs.edit()
                .putString("${prefix}_id", sequenceId)
                .putString("${prefix}_fingerprint", fingerprint)
                .putInt("${prefix}_next_step", 0)
                .putString("${prefix}_state_hash", prefs.getString("accessibility_last_state_hash", "").orEmpty())
                .putString("${prefix}_status", "in_progress")
                .putString("${prefix}_updated_at", Instant.now().toString())
                .apply()
        }

        var completed = startIndex
        for (i in startIndex until steps.length()) {
            val step = steps.optJSONObject(i)
                ?: return fail(prefs, prefix, completed, "Step $i is not an object.")
            val action = step.optString("action").trim()
            val stepPayload = step.optJSONObject("payload")
                ?: return fail(prefs, prefix, completed, "Step $i payload missing.")

            val stateHash = stepPayload.optString("expectedStateHash").trim()
            if (!stateHash.matches(Regex("^[a-fA-F0-9]{64}$"))) {
                return fail(prefs, prefix, completed, "Step $i expectedStateHash missing or invalid.")
            }

            val currentHash = prefs.getString("accessibility_last_state_hash", "").orEmpty().lowercase()
            if (currentHash != stateHash.lowercase()) {
                return fail(prefs, prefix, completed, "Step $i state mismatch; re-entry required.")
            }

            val result = when (action) {
                "click" -> PigaAccessibilityService.governedClick(stepPayload)
                "set_text" -> PigaAccessibilityService.governedSetText(stepPayload)
                else -> return fail(prefs, prefix, completed, "Step $i action is not admitted.")
            }

            if (!result.ok) {
                return fail(prefs, prefix, completed, "Sequence stopped at step $i ($action): ${result.detail}")
            }

            completed++
            val verifiedState = prefs.getString("accessibility_last_state_hash", "").orEmpty()
            if (!verifiedState.matches(Regex("^[a-fA-F0-9]{64}$"))) {
                return fail(prefs, prefix, completed, "Verified post-step state missing; re-entry required.")
            }

            prefs.edit()
                .putInt("${prefix}_next_step", completed)
                .putString("${prefix}_state_hash", verifiedState)
                .putString("${prefix}_status", "in_progress")
                .putString("${prefix}_updated_at", Instant.now().toString())
                .apply()
        }

        prefs.edit()
            .putInt("${prefix}_next_step", completed)
            .putString("${prefix}_status", "completed")
            .putString("${prefix}_updated_at", Instant.now().toString())
            .apply()
        clearCheckpoint(prefs, prefix)
        return SequenceResult(true, completed, "Governed UI sequence completed: $completed step(s); checkpoint cleared.")
    }

    private fun fail(
        prefs: android.content.SharedPreferences,
        prefix: String,
        completed: Int,
        detail: String
    ): SequenceResult {
        prefs.edit()
            .putString("${prefix}_status", "blocked")
            .putString("${prefix}_last_error", detail.take(500))
            .putString("${prefix}_updated_at", Instant.now().toString())
            .apply()
        return SequenceResult(false, completed, detail)
    }

    private fun clearCheckpoint(prefs: android.content.SharedPreferences, prefix: String) {
        prefs.edit()
            .remove("${prefix}_id")
            .remove("${prefix}_fingerprint")
            .remove("${prefix}_next_step")
            .remove("${prefix}_state_hash")
            .remove("${prefix}_status")
            .remove("${prefix}_last_error")
            .remove("${prefix}_updated_at")
            .apply()
    }

    private fun sha256(value: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
