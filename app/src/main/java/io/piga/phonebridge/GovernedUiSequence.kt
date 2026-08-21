package io.piga.phonebridge

import org.json.JSONObject

/**
 * Executes a bounded, pre-admitted UI sequence.
 *
 * The sequence is deliberately non-generative on-device: every step must be
 * supplied in advance with its own expectedStateHash and postcondition data.
 * Any failed step aborts the sequence immediately and requires re-entry.
 */
object GovernedUiSequence {
    data class SequenceResult(
        val ok: Boolean,
        val completedSteps: Int,
        val detail: String
    )

    fun execute(payload: JSONObject): SequenceResult {
        val steps = payload.optJSONArray("steps")
            ?: return SequenceResult(false, 0, "Sequence steps missing.")

        if (steps.length() !in 1..8) {
            return SequenceResult(false, 0, "Sequence must contain 1..8 admitted steps.")
        }

        var completed = 0
        for (i in 0 until steps.length()) {
            val step = steps.optJSONObject(i)
                ?: return SequenceResult(false, completed, "Step $i is not an object.")
            val action = step.optString("action").trim()
            val stepPayload = step.optJSONObject("payload")
                ?: return SequenceResult(false, completed, "Step $i payload missing.")

            // Every mutable UI step must bind to an exact pre-observed state.
            val stateHash = stepPayload.optString("expectedStateHash").trim()
            if (!stateHash.matches(Regex("^[a-fA-F0-9]{64}$"))) {
                return SequenceResult(false, completed, "Step $i expectedStateHash missing or invalid.")
            }

            val result = when (action) {
                "click" -> PigaAccessibilityService.governedClick(stepPayload)
                "set_text" -> PigaAccessibilityService.governedSetText(stepPayload)
                else -> return SequenceResult(false, completed, "Step $i action is not admitted.")
            }

            if (!result.ok) {
                return SequenceResult(
                    false,
                    completed,
                    "Sequence stopped at step $i ($action): ${result.detail}"
                )
            }
            completed++
        }

        return SequenceResult(true, completed, "Governed UI sequence completed: $completed step(s).")
    }
}
