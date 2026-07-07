# Quickstart: Governance & Review Gates — Validation Guide

**Feature**: 005-governance-review-gates · **Date**: 2026-07-07
Proves SC-001…SC-008. References: [data-model.md](data-model.md),
[contracts/api.yaml](contracts/api.yaml), [research.md](research.md).

## Prerequisites

- Docker (PostgreSQL 16 + pgvector with WAL archiving to MinIO for the DR drill, MinIO);
  Testcontainers; `StubAiGatewayClient` — no provider needed
- Build: `./gradlew` (or Docker `gradle:8.10-jdk21`)
- Phases 3 and 4 merged (knowledge injection + three case types active)

## One-command validation

```bash
./gradlew :app:test
```

All Phase 1–4 suites re-run unchanged (SC-008) plus the six new suites below. The DR drill
(scenario 6) is a documented manual runbook, not a CI test.

## Scenario walkthroughs

### 1. Every D4 decision flows through a gate (SC-001) — `GateFlowIT`

1. Run a case on the gate-embedding workflow version → at each governance point a
   `gate_instance` opens (engine user task bridged).
2. `GET /gates/{id}` shows the artifact under review + exact decision inputs.
3. Decide APPROVE / REJECT → Decision row records reviewer, inputs ref, timestamp, verb, rationale
   (who/what/when/on-what/why); AuditEntry in the same transaction.
4. Reuse subtest: the same gate definition key/version serves Initiation, Assessment, and
   Enhancement workflows.

### 2. Comment-and-regenerate only (SC-002) — `CommentRegenerateIT`

1. REQUEST_CHANGES with comments → gate `REGENERATING`; persona re-executes (full snapshot);
   a NEW ArtifactRevision appears; the original revision is byte-unchanged; both in history.
2. `GET /gates/{id}/delta-report` → deterministic unified diff + hash.
3. Negative probe: no endpoint accepts artifact content for an AI-drafted artifact — API surface
   scan asserts absence (SC-002 "no path exists").

### 3. Reopen policy — direct only, impact-assessed (SC-003) — `ReopenPolicyIT`

1. Approve an upstream artifact and its direct + transitive dependents' gates.
2. Publish a new upstream revision → DMN produces `reopen-candidates`: depth-1 rows reopenable,
   depth>1 flagged `MANUAL_REVIEW`.
3. `POST /gates/{id}/reopen` without an impact assessment → `409`; capture
   `POST …/impact-assessment` (reason/scope/risk) then reopen → gate `REOPENED` as a recorded
   Decision with the delta report attached.
4. Assert transitive gates remain untouched (flag only).

### 4. Advisory SLA timers (SC-004) — `EscalationTimerIT`

1. Open a gate pinned to a short-SLA escalation policy version; advance the engine clock.
2. Timer fires → `escalation_activation` row (policy version, step, role), notification emitted,
   outbox event written, policy status visible via `GET /gates/{id}/escalations`.
3. Assert the gate is still `OPEN`, the user task unmoved — zero auto-approve/reject/reassign.
4. Unassigned-role subtest: activation still recorded and surfaced (`assigneeResolved=false`).

### 5. Tamper-evidence, retention, package access (SC-005, SC-006) — `AuditChainIT`, `PackageAccessIT`

1. Seal segments; then (as superuser, simulating an attacker) alter one sealed `audit_entry` →
   `POST /audit/chain/verify` reports `intact=false` with the broken segment. Delete subtest:
   same detection. Untampered chain verifies `intact=true`.
2. Retention: `GET/PUT /workspace/retention` — default 7 years; setting below the minimum → `422`.
3. Package access: delivered package readable only by granted roles; ungranted role → `403`;
   grants seeded at delivery for participant roles; no workspace-wide default.

### 6. DR drill (SC-007) — `ops/dr-drill.md` (manual runbook)

1. Take base backup + continuous WAL archiving (compose stack).
2. Destroy the DB volume; restore base + replay WAL to a point ≤ 15 min before failure (RPO).
3. Boot the app on the restored DB; run the replay-audit + chain-verify smoke suites green.
4. Record achieved RPO/RTO (RPO target ≤ 15 min, RTO target ≤ 1 h), date, operator in `quickstart-results.md`.

### 7. Gate event payload contract (Phase 7 pre-wiring) — part of `GateFlowIT`

Every gate lifecycle event in the outbox validates against the `GateEventPayload` schema
(contracts/api.yaml) — the projection-sufficiency assertion Phase 7 depends on.

### 8. Prior-phase guarantees (SC-008)

Re-run unchanged: SubmitToDeliver, ParallelExecution, Leakage, InjectionSeed, TokenBudget,
AuditGrant, Knowledge*, CaseRouting/Guard suites — all green with gates active.

## Expected results summary

| Scenario | Proves | Suite |
|---|---|---|
| 1 | SC-001 | GateFlowIT |
| 2 | SC-002 | CommentRegenerateIT |
| 3 | SC-003 | ReopenPolicyIT |
| 4 | SC-004 | EscalationTimerIT |
| 5 | SC-005, SC-006 | AuditChainIT, PackageAccessIT |
| 6 | SC-007 | ops/dr-drill.md (documented results) |
| 7 | FR-019 / Phase 7 dependency | GateFlowIT (payload assertions) |
| 8 | SC-008 | existing suites |
