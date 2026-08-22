package io.piga.phonebridge

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrchestrationPlanVerifierTest {
    @Test
    fun governedGearboxV16PlanIsVerifiedWithoutGrantingAuthority() {
        val plan = JSONObject()
            .put("gearboxVersion", "1.6")
            .put(
                "invariants",
                JSONObject()
                    .put("productionAuthorized", false)
                    .put("schedulerMayNotGrantAuthority", true)
                    .put("gate2RequiredForExternalAction", true)
                    .put("workersAreEphemeral", true)
                    .put("freeOnly", true)
            )
            .put(
                "schedule",
                JSONArray()
                    .put(row("frontier", 10, 12))
                    .put(row("runtime", 12, 15))
                    .put(row("gate2", 15, 17))
                    .put(row("action", 17, 18))
            )

        val result = OrchestrationPlanVerifier.verify(plan)

        assertTrue(result.admitted)
        assertTrue(result.gate2AfterFrontier)
        assertTrue(result.gate2AfterRuntime)
        assertTrue(result.actionAfterGate2)
        assertFalse(result.productionAuthorized)
        assertFalse(result.externalActionExecuted)
    }

    @Test
    fun authorityOrOrderingDriftFailsClosed() {
        val plan = JSONObject()
            .put("gearboxVersion", "1.6")
            .put(
                "invariants",
                JSONObject()
                    .put("productionAuthorized", false)
                    .put("schedulerMayNotGrantAuthority", true)
                    .put("gate2RequiredForExternalAction", true)
                    .put("workersAreEphemeral", true)
                    .put("freeOnly", true)
            )
            .put(
                "schedule",
                JSONArray()
                    .put(row("frontier", 10, 14))
                    .put(row("gate2", 12, 13))
            )

        val result = OrchestrationPlanVerifier.verify(plan)

        assertFalse(result.admitted)
        assertFalse(result.productionAuthorized)
    }

    @Test
    fun paidOrAuthorizingPlanFailsClosed() {
        val plan = JSONObject()
            .put("gearboxVersion", "1.6")
            .put(
                "invariants",
                JSONObject()
                    .put("productionAuthorized", true)
                    .put("schedulerMayNotGrantAuthority", true)
                    .put("gate2RequiredForExternalAction", true)
                    .put("workersAreEphemeral", true)
                    .put("freeOnly", false)
            )
            .put(
                "schedule",
                JSONArray()
                    .put(row("frontier", 1, 2))
                    .put(row("gate2", 2, 3))
            )

        val result = OrchestrationPlanVerifier.verify(plan)

        assertFalse(result.admitted)
        assertFalse(result.productionAuthorized)
    }

    private fun row(gear: String, start: Long, end: Long): JSONObject = JSONObject()
        .put("gear", gear)
        .put("start", start)
        .put("end", end)
}
