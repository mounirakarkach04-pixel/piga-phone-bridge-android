# Physical-Device Acceptance Protocol

Run on a clean device or emulator only after CI passes.

1. Install the signed or debug candidate and confirm exactly one launcher icon.
2. Confirm first launch shows `NOT PAIRED`, Master Autonomy off and the trusted control plane.
3. Attempt to enable Master Autonomy before pairing; expect a local block.
4. Pair against `https://pigapocket.com` with a fresh owner approval code.
5. Restart the process and device; confirm pairing persists but authority does not silently broaden.
6. Explicitly enable Master Autonomy and verify the foreground runtime notification.
7. Execute one command per allowlisted capability and capture ACK/result receipts.
8. Replay a used nonce; expect rejection.
9. Deliver expired and lease-expired commands; expect rejection.
10. Interrupt network delivery after a local effect; restore network and verify result delivery without effect replay.
11. Enable Emergency Stop; confirm service stop and all new effects blocked.
12. Change stored origin in a controlled test build; confirm pairing is cleared and re-entry required.
13. Deny notification permission; confirm notification effects block while unrelated capabilities remain branch-local.
14. Clear app storage and confirm local device and pairing data are removed.

Record device model, Android version, build SHA, artifact SHA-256, date and observed receipts.
