# PIGA Phone Orchestration Contract v0.1

Purpose: let the Android phone node independently verify an orchestration plan before any downstream capability execution is considered.

## Planned bridge command

- type: `orchestration_plan_verify`
- capabilityScope: `pocket.orchestration.verify`
- payload.plan: the complete optimized-plan JSON produced by the canonical PIGA orchestration runner.

## Local verification

`OrchestrationPlanVerifier` verifies:

1. `productionAuthorized == false`
2. `gate2RequiredForExternalAction == true`
3. `schedulerMayNotGrantAuthority == true`
4. Gate 2 starts after Frontier ends.
5. If Runtime is present, Gate 2 starts after Runtime ends.
6. Every schedule row has valid non-negative start/end values.
7. A local SHA-256 receipt is computed from the received plan.

## Result semantics

An admitted plan yields `ORCHESTRATION_PLAN_EVIDENCE_READY` only. It never means production approval, deployment approval, or permission for an external action.

Hard invariants in every receipt:

- `productionAuthorized=false`
- `externalActionExecuted=false`

## Current integration state

The phone-side verifier is implemented in the Android repository. The existing `BridgeService` command dispatch still needs the new command type added to its allowlist and `when` dispatch before remote orchestration-plan verification is live end-to-end.

Until that dispatch wiring is present, status is `IMPLEMENTED_COMPONENT / NOT_END_TO_END_ACTIVE`.
