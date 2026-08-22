package io.piga.phonebridge

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

object OrchestrationPlanVerifier {
    private const val VERIFIER_VERSION = "0.1.16-canonical-hash-fix"

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

        val frontierOk = plan.optString("frontier") == "allowlisted_safe_capabilities_only"
        val runtimeOk = plan.optString("runtime") == "foreground_only_signed_polling"
        val gate2Ok = plan.optString("gate2") == "owner_authenticated_native_bridge"
        val productionAuthorized = plan.optBoolean("productionAuthorized", false)

        val admitted = frontierOk && runtimeOk && gate2Ok && !productionAuthorized
        val reason = when {
            productionAuthorized -> "Production authorization is forbidden in verification-only plans."
            !frontierOk -> "Frontier is outside the allowlisted safe-capability boundary."
            !runtimeOk -> "Runtime mode is not the signed foreground polling contract."
            !gate2Ok -> "Gate 2 binding is not the owner-authenticated native bridge."
            else -> "Orchestration plan verified as evidence-only; no production authority granted."
        }

        return Result(
            admitted = admitted,
            reason = reason,
            planSha256 = hash,
            gate2AfterFrontier = frontierOk && gate2Ok,
            gate2AfterRuntime = runtimeOk && gate2Ok,
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
        put("productionAuthorized", false)
        put("externalActionExecuted", false)
        put("source", "phone.orchestration-plan-verifier")
        put("verifierVersion", VERIFIER_VERSION)
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
