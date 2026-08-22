package io.piga.phonebridge

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

object OrchestrationPlanVerifier {
    data class Result(
        val admitted: Boolean,
        val reason: String,
        val planSha256: String,
        val gate2AfterFrontier: Boolean,
        val gate2AfterRuntime: Boolean,
        val productionAuthorized: Boolean = false,
        val externalActionExecuted: Boolean = false
    )

    fun verify(plan: JSONObject): Result {
        val canonical = canonicalJson(plan)
        val hash = sha256(canonical)
        val invariants = plan.optJSONObject("invariants")
            ?: return Result(false, "missing_invariants", hash, false, false)

        if (invariants.optBoolean("productionAuthorized", true)) {
            return Result(false, "production_authority_forbidden", hash, false, false)
        }
        if (!invariants.optBoolean("gate2RequiredForExternalAction", false)) {
            return Result(false, "gate2_requirement_missing", hash, false, false)
        }
        if (!invariants.optBoolean("schedulerMayNotGrantAuthority", false)) {
            return Result(false, "scheduler_authority_invariant_missing", hash, false, false)
        }

        val schedule = plan.optJSONArray("schedule")
            ?: return Result(false, "missing_schedule", hash, false, false)
        val rows = mutableMapOf<String, Pair<Int, Int>>()
        for (i in 0 until schedule.length()) {
            val row = schedule.optJSONObject(i) ?: continue
            val gear = row.optString("gear")
            val start = row.optInt("start", -1)
            val end = row.optInt("end", -1)
            if (gear.isBlank() || start < 0 || end < start) {
                return Result(false, "invalid_schedule_row", hash, false, false)
            }
            rows[gear] = start to end
        }

        val gate2 = rows["gate2"] ?: return Result(false, "gate2_missing", hash, false, false)
        val frontier = rows["frontier"] ?: return Result(false, "frontier_missing", hash, false, false)
        val gate2AfterFrontier = gate2.first >= frontier.second
        val runtime = rows["runtime"]
        val gate2AfterRuntime = runtime == null || gate2.first >= runtime.second

        if (!gate2AfterFrontier) {
            return Result(false, "gate2_precedes_frontier", hash, false, gate2AfterRuntime)
        }
        if (!gate2AfterRuntime) {
            return Result(false, "gate2_precedes_runtime", hash, true, false)
        }

        return Result(
            admitted = true,
            reason = "ORCHESTRATION_PLAN_EVIDENCE_READY",
            planSha256 = hash,
            gate2AfterFrontier = true,
            gate2AfterRuntime = true
        )
    }

    fun receiptJson(result: Result): JSONObject = JSONObject().apply {
        put("admitted", result.admitted)
        put("reason", result.reason)
        put("planSha256", result.planSha256)
        put("gate2AfterFrontier", result.gate2AfterFrontier)
        put("gate2AfterRuntime", result.gate2AfterRuntime)
        put("productionAuthorized", false)
        put("externalActionExecuted", false)
        put("source", "phone.orchestration-plan-verifier")
    }

    private fun canonicalJson(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { key ->
            "${JSONObject.quote(key)}:${canonicalJson(value.opt(key))}"
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { index ->
            canonicalJson(value.opt(index))
        }
        is String -> JSONObject.quote(value)
        is Boolean -> if (value) "true" else "false"
        is Number -> value.toString()
        else -> JSONObject.quote(value.toString())
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
