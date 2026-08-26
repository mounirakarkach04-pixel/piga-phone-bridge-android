# Privacy Notice — PIGA Pocket Enterprise Android

Effective date: 2026-08-26

## Scope

This notice describes the Android application in this repository. The application is a controlled execution edge for a user-selected PIGA Pocket Enterprise control plane.

## Data processed on the device

The application stores the following operational data in Android application storage:

- a randomly generated device identifier;
- the public part of an Android Keystore device key;
- pairing state and pairing identifier;
- the canonical control-plane address;
- Master Autonomy and Emergency Stop state;
- command nonces, pending result receipts and runtime timestamps;
- local diagnostics outcomes.

The private device key remains in Android Keystore and is not intentionally exported by the application.

## Data transmitted

When the user pairs the device or starts the governed runtime, the application may transmit to `https://pigapocket.com`:

- device identifier and public key;
- pairing challenge and confirmation data;
- declared capability scopes;
- signed runtime request metadata;
- local safety state;
- acknowledgements, results and verification receipts;
- content required by an explicitly admitted capability, such as notification text or a share payload.

The application does not contain an advertising SDK or a third-party behavioral analytics SDK in the current source tree.

## Purpose

Data is processed to authenticate the device, enforce governance decisions, deliver capability-scoped work, prevent replay, recover interrupted result delivery and provide an auditable execution receipt.

## Retention and deletion

Local application data can be removed through Android system settings by clearing application storage or uninstalling the application. A production control plane must provide an operator process for revoking a pairing and deleting server-side device records. Store publication must identify the responsible operator and verified privacy contact before public distribution.

## Security

The application disables cleartext traffic, pins the canonical control-plane origin, uses short-lived command leases and nonces, and stops autonomous execution when Emergency Stop is active or required authority is missing.

## Children

The application is an enterprise automation tool and is not designed for children.

## Changes

Material privacy changes require a new version of this notice and a fresh release review.
