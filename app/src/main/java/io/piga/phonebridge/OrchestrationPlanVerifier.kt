package io.piga.phonebridge

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

object OrchestrationPlanVerifier {
    private const val VERIFIER_VERSION = "0.1.15-diagnostic"

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

        // One-build diagnostic: fail closed while exposing the locally computed
        // canonical hash and verifier version through the already signed result detail.
        throw IllegalStateException("PIGA_HASH_DIAGNOSTIC actualSha256=$hash verifierVersion=$VERIFIER_VERSION canonicalLength=${canonical.toByteArray(Charsets.UTF_8).size}")
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
