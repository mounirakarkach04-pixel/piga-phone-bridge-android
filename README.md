# PIGA Pocket Enterprise — Android Edge

PIGA Pocket Enterprise is a governed Android execution edge for the PIGA five-engine architecture. The application pairs a device with the canonical control plane, keeps device identity inside Android Keystore, accepts only capability-scoped commands and records acknowledgements and terminal results.

## Release line

- Application ID: `io.piga.phonebridge.mobile`
- Current version: `0.2.0` (`versionCode 19`)
- Minimum Android version: Android 8.0 / API 26
- Target SDK: 35
- Canonical control plane: `https://pigapocket.com`
- Engine count: **5**
- A7SEM Reverse: cross-engine meta-operator, not an additional engine

## Governed capabilities

The current native allowlist contains:

- local notifications;
- clipboard write;
- safe HTTP/HTTPS URL intents;
- text-to-speech;
- launch of installed applications;
- Android share intents;
- local verification of PIGA orchestration plans.

Every remote command is checked for its capability scope, command ID, nonce, expiry, lease, local safety state and factory correlation where supplied. Terminal results are persisted in a local outbox before delivery so an uncertain network response does not silently replay an effect.

## Safety model

- **Master Autonomy is opt-in.** Pairing does not enable it automatically.
- **Emergency Stop dominates all autonomous execution.**
- A control-plane origin change clears pairing authority and requires fresh admission.
- Only `https://pigapocket.com` is accepted as the runtime origin.
- Cleartext network traffic is disabled.
- Android components are non-exported except the single launcher activity.
- Device signing keys are generated in Android Keystore and are not exported as private key material.

## Build locally

Requirements: JDK 17, Android SDK 35 and Gradle 8.9.

```bash
python scripts/check_piga_sync.py
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --stacktrace
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## CI and release candidates

`Android Debug APK` runs synchronization checks, unit tests, Android lint and a debug APK build.

`Android Release Candidate` builds a minimized release APK and Android App Bundle. When the four repository secrets below exist, it signs and verifies both artifacts; otherwise it produces clearly marked unsigned candidates:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_PASSWORD`

Every artifact package includes `SHA256SUMS.txt` and a machine-readable release manifest.

## Privacy and security

See [PRIVACY.md](PRIVACY.md) for the data-handling declaration and [SECURITY.md](SECURITY.md) for vulnerability reporting and security boundaries.

## Production boundary

A successful CI build proves that source, tests, lint and packaging pass. Public market release additionally requires owner-controlled signing keys, a live production control plane, store account declarations and physical-device acceptance testing. The application must never claim completion for an action without a verified result or receipt.
