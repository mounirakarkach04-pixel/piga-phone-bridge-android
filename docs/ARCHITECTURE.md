# Android Edge Architecture

## Role

The Android application is the mobile execution edge of PIGA Pocket Enterprise. It is not a sixth engine and it is not an unconstrained persistent agent. Task authority, evidence and state belong to the governed workflow.

## Control sequence

1. Pairing establishes a device identity and scoped relationship with the canonical control plane.
2. The control plane admits inference and planning through Gate 1.
3. Task-scoped workers prepare a bound action specification.
4. Material changes require re-entry at the earliest affected boundary.
5. Gate 2 admits or blocks the external action.
6. The phone validates scope, nonce, expiry, lease and local safety state.
7. The phone executes an allowlisted capability.
8. ACK and terminal result receipts are delivered to the control plane.
9. Uncertain result delivery is retried from the local outbox without re-executing the effect.

## Trust boundaries

- Android Keystore protects the device private key.
- `ControlPlaneResolver` pins the origin to `https://pigapocket.com`.
- `MainActivity` owns explicit local safety controls.
- `BridgeService` owns command validation, local execution and receipts.
- `BridgeRecoveryWorker` restores only previously admitted runtime state and cannot grant authority.
- `OrchestrationPlanVerifier` produces verification evidence but cannot authorize production execution.

## Terminal states

The wider PIGA workflow uses deterministic terminal states: `ALLOW`, `DELAY`, `BLOCK`, `UNKNOWN` and `EXPIRED`. A branch-local delay or block does not create authority and does not require unrelated admitted branches to stop.
