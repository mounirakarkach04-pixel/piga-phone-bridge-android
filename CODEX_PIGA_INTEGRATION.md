# Codex Integration — PIGA Pocket Enterprise

## Role
Codex is an engineering capability/short-lived worker inside the existing Pocket Enterprise cross-engine workflow. It is not a persistent autonomous authority and not a sixth engine.

## Controlled path
`Task/Event -> PIGA Inference Admission -> Existing Engine Routing -> Codex Engineering Worker -> Build/Test Evidence -> Material-Change Check -> PIGA Action Admission -> Phone Bridge/Release Action -> ACK/Result -> Evidence/Reconciliation`

## Allowed engineering outcomes
- inspect repository and CI state
- diagnose Android/Gradle/Kotlin defects
- implement bounded fixes and refactors
- add or improve tests
- harden pairing, capability routing, receipts and auditability without weakening controls
- prepare reviewable commits/PRs

## Not authorized by engineering success
- production deployment
- installation on a paired device
- sending messages or performing external transactions
- broadening device permissions/capabilities
- changing security/governance policy
- incurring paid resource usage

Those effects require their own Action Admission and applicable device/user/platform controls.

## Material-change triggers
Re-enter governance review if a change affects identity/keys, signature algorithms or verification, pairing trust, command schema, replay/nonce rules, lease/expiry, master-autonomy/emergency-stop logic, allowlists, Android permissions, external intents/shares, evidence/receipt semantics, network targets, or cost boundaries.

## Evidence contract
Each engineering run should emit or preserve: input task, scope, changed paths, diff/commit identity, test commands, test outcomes, material-change classification, unresolved UNKNOWNs, and recommended next gate. Runtime device actions remain accountable through signed command + ACK/result evidence.

## Current autonomy interpretation
Codex can substantially automate software engineering and repair, but it does not by itself turn a paired phone into unrestricted autonomous execution. Full end-to-end autonomy remains conditional on verified Phone Bridge execution, capability admission, reconciliation, and evidence.
