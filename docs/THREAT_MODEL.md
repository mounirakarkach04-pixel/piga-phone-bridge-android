# Android Edge Threat Model

## Protected assets

- device private key;
- pairing authority;
- Master Autonomy and Emergency Stop state;
- command nonces and leases;
- terminal result outbox;
- control-plane origin;
- factory correlation hashes.

## Primary threats and controls

| Threat | Control |
|---|---|
| Malicious control-plane redirect | Exact HTTPS origin pin and material-change re-entry |
| Stolen pairing identifier | Device ECDSA signature required on runtime requests |
| Command replay | Single-use command nonce persisted before local execution |
| Delayed command | Expiry and lease checks before execution |
| Scope escalation | Static command-type/capability-scope allowlist |
| Repeated side effect after network ambiguity | Persisted terminal-result outbox; no effect replay |
| Background restart grants authority | Recovery requires paired + explicit Master Autonomy + no Emergency Stop |
| Untrusted Android component invocation | Pairing, diagnostics, service and receiver are non-exported |
| Debug artifact mistaken for store build | Debug application ID suffix and clearly separated release workflow |
| Signing-key disclosure | Secrets-only signing path and temporary-keystore deletion |

## Residual production risks

The control plane, account recovery, device revocation, rate limits, abuse prevention, operator identity and store-distribution controls must be validated in the deployed environment. A passing Android build does not prove those external controls.
