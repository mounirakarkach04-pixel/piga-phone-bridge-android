# Governed Mobile Capability Router

This feature branch extends the existing signed PIGA Phone Bridge runtime with additional fail-closed Android capabilities while preserving the current pairing, safety sync, nonce, lease, expiry, ACK/result and audit-compatible command lifecycle.

Admitted native command types in this branch:
- local_notification → pocket.notification
- clipboard_write → pocket.clipboard.write
- url_intent → pocket.intent.url
- text_to_speech → pocket.tts
- supported_app_launch → pocket.app.launch
- share_text → pocket.share.text

Safety properties:
- Master Autonomy must be enabled.
- Emergency Stop blocks execution.
- Command nonce is single-use locally.
- Expiry and lease are checked before execution.
- Command type and capability scope must match the local allowlist.
- URL intents are limited to http/https.
- Missing handlers/apps fail closed.
- Share actions open a target/chooser but do not bypass target-app confirmation or send controls.
- High-risk external effects are not added here.
