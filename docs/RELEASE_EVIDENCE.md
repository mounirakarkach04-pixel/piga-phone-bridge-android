# Release Evidence Index

For each release candidate retain:

- source commit SHA;
- synchronization-gate output;
- unit-test and lint outcomes;
- APK and AAB SHA-256 digests;
- signed/unsigned status;
- signature verification output when signed;
- control-plane health result;
- physical-device acceptance record;
- privacy/store declaration version.

The GitHub `Android Release Candidate` workflow packages the source SHA, version, control-plane origin, five-engine invariant, signing state and artifact names in `release-manifest.json`.
