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

A health endpoint is not execution authority. It reports availability and the fixed five-engine invariant only.
