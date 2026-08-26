# Production Control-Plane Contract

Canonical origin: `https://pigapocket.com`

The Android edge requires the following HTTPS routes:

- `GET /api/health`
- `POST /api/factory/trigger/next`
- `POST /api/bridge/pairing/challenge`
- `POST /api/bridge/pairing/confirm`
- `POST /api/bridge/devices/{deviceId}/safety`
- `GET /api/bridge/devices/{deviceId}/commands`
- `POST /api/bridge/devices/{deviceId}/commands/{commandId}/ack`
- `POST /api/bridge/devices/{deviceId}/commands/{commandId}/result`

Production implementation requirements:

- verify GitHub OIDC for governance-wake calls;
- verify Android device signatures for runtime calls;
- enforce timestamp skew and single-use request nonces;
- enforce pairing expiry, revocation and device-key binding;
- preserve command nonce, expiry, lease and capability scope;
- bind factory-managed commands to job, subjob and hash correlation;
- persist ACK/result receipts idempotently;
- expose revocation and data-deletion operations;
- fail closed on missing evidence or storage errors.

## External-effect recovery invariant

Commands that can create a real device effect use the following local lifecycle:

`ADMITTED -> ACKED -> EFFECT_STARTED -> RESULT_PERSISTED -> RESULT_DELIVERED`

`EFFECT_STARTED` is durably journaled on the Android device immediately before the capability is invoked. The command nonce and the effect journal must be committed before crossing the device-effect boundary.

If the process stops after `EFFECT_STARTED` but before a terminal result is durably persisted, the effect is ambiguous. On re-entry the bridge MUST NOT execute that command again. It persists and signs a result with `status: "uncertain"`, preserves available command/factory correlation evidence, and waits for target-state reconciliation or an explicitly newly admitted command.

The control plane MUST treat `uncertain` as quarantined/non-replayable evidence:

- do not automatically reissue the same command or nonce;
- do not convert `uncertain` into `failed` or `succeeded` without effect-bound evidence;
- reconcile using target-state/readback evidence when the capability supports it;
- require a fresh admission and materially new command identity before a subsequent external effect is allowed;
- persist duplicate ACK/result deliveries idempotently.

A locally persisted terminal result takes precedence over an older effect journal and is redelivered without re-executing the capability. Malformed local outbox/journal records are quarantined branch-locally so one corrupt record cannot stop the complete phone runtime.

## Self-healing evidence

`runtime_heartbeat_ms` is local Android-runtime liveness evidence and is independent from `last_poll_ms`, which represents successful control-plane polling. A remote outage must not trigger a local runtime restart while the local heartbeat is fresh.

A watchdog restart request is not itself proof of recovery. Recovery is considered verified only after a fresh local runtime heartbeat is observed. Otherwise the worker defers with backoff and re-enters recovery without bypassing pairing, autonomy or emergency-stop gates.

A health endpoint is not execution authority. It reports availability and the fixed five-engine invariant only.
