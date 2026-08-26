# Changelog

## 0.2.0 — release candidate

- Replaced the legacy duplicate pairing launcher with a single PIGA Pocket Enterprise dashboard.
- Pinned all control-plane use to `https://pigapocket.com` and rejected alternative hosts, paths, ports and cleartext origins.
- Made Master Autonomy explicitly opt-in and kept Emergency Stop dominant.
- Added dark product styling, app identity resources and a single launcher surface.
- Added release shrinking, resource shrinking and BuildConfig version reporting.
- Advanced the phone orchestration contract to version code 19 / version name 0.2.0.
- Extended the synchronization gate with market-readiness and origin-pinning invariants.
- Added resolver unit tests, a reproducible release-candidate workflow and SHA-256 manifests.
- Converted the governance heartbeat to event-driven execution with a bounded fallback heartbeat and branch-local DELAY behavior.
- Added privacy, security, store-listing and release-checklist documentation.

## 0.1.17

- Established persistent governed runtime recovery, command receipts and orchestration-plan verification.
