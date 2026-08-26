# Security Policy

## Supported release line

Security fixes are developed against the latest `0.x` release candidate and the default branch after merge. Older debug artifacts are not supported production releases.

## Reporting

Do not publish device identifiers, pairing material, signing keys, command payloads or exploitable details in a public issue. Before public market distribution, the repository owner must publish a verified private security contact and response process.

## Security invariants

The Android edge is designed around the following fail-closed rules:

1. Exactly five PIGA engines remain authoritative; A7SEM Reverse is a cross-engine meta-operator, not a sixth engine.
2. Capability availability never grants authority.
3. Master Autonomy requires an explicit local user action.
4. Emergency Stop overrides pairing, recovery and autonomous execution.
5. A control-plane origin change clears pairing authority and requires fresh admission.
6. Only the pinned HTTPS origin `https://pigapocket.com` is accepted.
7. Command scope, nonce, expiry and lease are checked before effect execution.
8. A command result is persisted locally before network delivery is attempted.
9. Blind replay after an uncertain effect is forbidden.
10. Missing handlers, permissions, authority or evidence block execution.

## Secret handling

- Never commit an Android signing keystore, signing password, Cloudflare token, bootstrap code or production API credential.
- GitHub release signing uses repository secrets and deletes the temporary keystore before artifact upload.
- Debug builds are not suitable for public store distribution.
- The public key may be shared for pairing; the private Android Keystore key must remain non-exportable.

## Release requirements

A production release requires all of the following:

- green synchronization, unit-test and Android lint gates;
- verified release artifact hashes;
- owner-controlled signing and signature verification;
- a live control plane with production authentication and storage;
- revocation and data-deletion procedures;
- physical-device acceptance testing;
- completed store privacy and data-safety declarations.
