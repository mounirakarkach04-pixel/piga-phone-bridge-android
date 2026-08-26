# PIGA Pocket Enterprise Android — Release Checklist

## Automated gates

- [ ] PIGA synchronization gate passes.
- [ ] Unit tests pass for debug and release variants.
- [ ] Android lint passes with no release-blocking findings.
- [ ] Debug APK builds and receives a SHA-256 digest.
- [ ] Minimized release APK and AAB build.
- [ ] Release manifest states version, Git SHA, signing state and five-engine invariant.
- [ ] Signed artifacts verify successfully when signing secrets are present.

## Runtime acceptance

- [ ] Fresh install starts with Master Autonomy off.
- [ ] Emergency Stop disarms the runtime and remains dominant after restart.
- [ ] Only one launcher icon is installed.
- [ ] Pairing works against `https://pigapocket.com`.
- [ ] A noncanonical control-plane URL is rejected.
- [ ] Origin migration clears pairing and requires fresh admission.
- [ ] Notification permission denial blocks only notification effects.
- [ ] Nonce replay, expired commands and expired leases are rejected.
- [ ] Pending terminal results survive process restart and are not re-executed.
- [ ] Physical-device tests cover Android 8, Android 13 and the current target version.

## Owner-controlled publication gates

- [ ] Production Android signing key is created and backed up securely.
- [ ] GitHub signing secrets are installed without exposing the keystore.
- [ ] Production Cloudflare deployment and DNS route are active.
- [ ] Privacy contact, support contact and deletion procedure are published.
- [ ] Store screenshots, feature graphic, listing and content rating are approved.
- [ ] Google Play developer account and Play App Signing decisions are completed.
- [ ] Final signed AAB is uploaded to an internal testing track before public rollout.

A release is not described as public-market ready until every applicable item is evidenced. A blocked owner-controlled item must remain visible rather than being simulated as complete.
