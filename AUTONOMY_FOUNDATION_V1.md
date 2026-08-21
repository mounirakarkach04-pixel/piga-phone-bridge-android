# PIGA Phone Bridge — Autonomy Foundation v1

This branch defines the next governed autonomy layer for the Android bridge.

## Integration order
1. Android WorkManager for durable polling/retry/resume.
2. NotificationListenerService as event intake, only after explicit Android permission.
3. Restricted AccessibilityService adapter only for UI interactions that have no safer API/Intent path.
4. Optional Shizuku adapter for narrowly allowlisted system APIs; absence or permission loss must fail closed.
5. scrcpy remains an external diagnostic/fallback channel and is not part of trusted autonomous runtime.

## PIGA requirements
Every command must preserve authority/scope, Gate 1, material-change/re-entry, Gate 2, single-use nonce, lease/expiry, emergency stop, allowlist matching, ACK/result and evidence receipt.

## Accessibility restrictions
Accessibility is a fallback, never the first routing choice. It may only act against an allowlisted package + action + expected UI state. The bridge must read/verify state before acting and verify postcondition afterward. No blind coordinate-only actions for high-impact effects.

## Notification intake
Notifications are events, not authority. Their contents may trigger analysis but cannot by themselves authorize external action. Sensitive notification payloads must be minimized in receipts.

## Shizuku restrictions
Shizuku is optional. No root requirement is introduced. Only explicitly enumerated operations may be routed through it. Missing service, changed privilege level, or unexpected Android version behavior returns BLOCK/UNKNOWN rather than falling back to unrestricted shell execution.

## Runtime goal
User goal -> plan -> admitted capability -> native execution -> postcondition verification -> signed/traceable result. Human interaction is reserved for Android-required permission grants, biometric/TAN/signature challenges, or policy-defined irreversible high-impact actions.
