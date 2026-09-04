# Kalnbach Operations 1.0 — Release Readiness

Status: RELEASE CANDIDATE

## A7SEM forward review
- Identity and tenant separation: existing Supabase auth/profile model retained.
- Role boundary: management and employee navigation remain separated.
- Operational capabilities: planning, time, absences, work slips, tasks, invoices, users/permissions, integrations, compliance and legal modules retained.
- Evidence/accountability: existing audit/compliance surfaces retained; no PASS synthesis added.
- User experience: mobile safe areas, touch targets, reduced-motion support, form labels and external-link hardening added.
- Resilience: online/offline state and global runtime error surface added.
- Release language: FIELDTEST presentation replaced at runtime with RELEASE CANDIDATE while preserving legal review gates.

## A7SEM Reverse checks
1. No operational module was removed.
2. No role escalation or bypass was introduced.
3. No compliance BLOCK/UNKNOWN state is converted to PASS by the hardening layer.
4. Authentication tokens remain handled by the existing session/auth layer.
5. Offline mode does not claim writes are synchronized; users are told to wait for connectivity.
6. External links receive noopener/noreferrer.
7. Release Candidate is intentionally not represented as final legal/accounting certification.

## Remaining final-release gates
- End-to-end authenticated smoke test with one management and one employee account.
- Verify Supabase RLS policies and bootstrap RPC single-use/revocation behavior against production data.
- Build/sign Android APK/AAB from this release branch and verify install/upgrade path.
- Verify privacy, terms and imprint content with the business owner before public production declaration.
- Confirm invoice/accounting export behavior with the intended accounting workflow.

A final Production label should only replace Release Candidate after these gates have evidence-backed PASS results.
