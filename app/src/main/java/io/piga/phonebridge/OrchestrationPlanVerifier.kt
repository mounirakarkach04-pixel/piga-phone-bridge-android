package io.piga.phonebridge

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Deterministic, authority-neutral verifier for governed orchestration plans.
 *
 * Verification is evidence only. It never grants production or action authority;
 * Gate 2 and the local safety interlock remain mandatory for any external effect.
 */
object OrchestrationPlanVerifier {
    private const val VERIFIER_VERSION = "0.2.0-factory-contract-v1"
    private const val EXPECTED_GEARBOX_VERSION = "1.6"

    data class Result(
        val admitted: Boolean,
        val reason: String,
        val planSha256: String,
        val gate2AfterFrontier: Boolean,
        val gate2AfterRuntime: Boolean,
        val actionAfterGate2: Boolean,
        val productionAuthorized: Boolean = false,
        val externalActionExecuted: Boolean = false
    )

    fun verify(plan: JSONObject): Result {
        val canonical = canonicalJson(plan)
        val hash = sha256(canonical)

        val gearboxVersion = plan.optString("gearboxVersion")
        val invariants = plan.optJSONObject("invariants")
        val schedule = plan.optJSONArray("schedule")

        if (gearboxVersion != EXPECTED_GEARBOX_VERSION) {
            return blocked("gearbox_version_mismatch", hash)
        }
        if (invariants == null || schedule == null || schedule.length() == 0) {
            return blocked("plan_structure_missing", hash)
        }

        if (invariants.optBoolean("productionAuthorized", true)) {
            return blocked("production_authority_forbidden", hash)
        }
        if (!invariants.optBoolean("schedulerMayNotGrantAuthority", false)) {
            return blocked("scheduler_authority_invariant_missing", hash)
        }
        if (!invariants.optBoolean("gate2RequiredForExternalAction", false)) {
            return blocked("gate2_invariant_missing", hash)
        }
        if (!invariants.optBoolean("workersAreEphemeral", false)) {
            return blocked("ephemeral_worker_invariant_missing", hash)
        }
        if (!invariants.optBoolean("freeOnly", false)) {
            return blocked("free_only_invariant_missing", hash)
        }

        val rows = mutableMapOf<String, JSONObject>()
        for (index in 0 until schedule.length()) {
            val row = schedule.optJSONObject(index)
                ?: return blocked("schedule_row_invalid", hash)
            val gear = row.optString("gear").trim()
            if (gear.isBlank() || rows.containsKey(gear)) {
                return blocked("schedule_gear_invalid_or_duplicate", hash)
            }
            val start = row.optLong("start", Long.MIN_VALUE)
            val end = row.optLong("end", Long.MIN_VALUE)
            if (start < 0L || end <= start) {
                return blocked("schedule_interval_invalid", hash)
            }
            rows[gear] = row
        }

        val frontier = rows["frontier"]
            ?: return blocked("frontier_missing", hash)
        val gate2 = rows["gate2"]
            ?: return blocked("gate2_missing", hash)

        val gate2AfterFrontier = gate2.optLong("start") >= frontier.optLong("end")
        if (!gate2AfterFrontier) {
            return blocked(
                reason = "gate2_before_frontier",
                hash = hash,
                gate2AfterFrontier = false
            )
        }

        val runtime = rows["runtime"]
        val gate2AfterRuntime = runtime == null || gate2.optLong("start") >= runtime.optLong("end")
        if (!gate2AfterRuntime) {
            return blocked(
                reason = "gate2_before_runtime",
                hash = hash,
                gate2AfterFrontier = true,
                gate2AfterRuntime = false
            )
        }

        val action = rows["action"]
        val actionAfterGate2 = action == null || action.optLong("start") >= gate2.optLong("end")
        if (!actionAfterGate2) {
            return blocked(
                reason = "action_before_gate2",
                hash = hash,
                gate2AfterFrontier = true,
                gate2AfterRuntime = gate2AfterRuntime,
                actionAfterGate2 = false
            )
        }

        return Result(
            admitted = true,
            reason = "verified_governed_plan",
            planSha256 = hash,
            gate2AfterFrontier = true,
            gate2AfterRuntime = gate2AfterRuntime,
            actionAfterGate2 = actionAfterGate2,
            productionAuthorized = false,
            externalActionExecuted = false
        )
    }

    fun receiptJson(result: Result): JSONObject = JSONObject().apply {
        put("admitted", result.admitted)
        put("reason", result.reason)
        put("planSha256", result.planSha256)
        put("gate2AfterFrontier", result.gate2AfterFrontier)
        put("gate2AfterRuntime", result.gate2AfterRuntime)
        put("actionAfterGate2", result.actionAfterGate2)
        put("productionAuthorized", false)
        put("externalActionExecuted", false)
        put("source", "phone.orchestration-plan-verifier")
        put("verifierVersion", VERIFIER_VERSION)
        put("gearboxVersion", EXPECTED_GEARBOX_VERSION)
    }

    private fun blocked(
        reason: String,
        hash: String,
        gate2AfterFrontier: Boolean = false,
        gate2AfterRuntime: Boolean = false,
        actionAfterGate2: Boolean = false
    ): Result = Result(
        admitted = false,
        reason = reason,
        planSha256 = hash,
        gate2AfterFrontier = gate2AfterFrontier,
        gate2AfterRuntime = gate2AfterRuntime,
        actionAfterGate2 = actionAfterGate2,
        productionAuthorized = false,
        externalActionExecuted = false
    )

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
