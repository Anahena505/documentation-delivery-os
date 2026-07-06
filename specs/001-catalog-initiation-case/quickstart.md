# Quickstart: Validating Catalog Spine + Initiation Case Type

**Feature**: [spec.md](spec.md) · **Contract**: [contracts/api.yaml](contracts/api.yaml) ·
**Data model**: [data-model.md](data-model.md)

These scenarios prove the Phase 1 exit criteria end to end. Each maps to a spec success
criterion (SC-*) — all seven must pass before the feature is done.

## Prerequisites

- JDK 21, Docker (for Testcontainers: PostgreSQL 16 + MinIO)
- An AI provider key configured **only** on the AI Gateway module (never in persona code)
- Build once: `./gradlew build` (compiles all modules, runs ArchUnit boundary checks)

## Run the stack locally

```bash
docker compose up -d postgres minio     # infra
./gradlew bootRun                       # single deployable, all modules
# API at http://localhost:8080/api/v1 — see contracts/api.yaml
```

Seed data: `./gradlew seedCatalog` loads the 9 published Phase 1 catalog assets
(1 CaseType, 1 Workflow, 3 Personas, 3 Playbooks, 9 Templates, 1 Rule, 3 Rubrics, prompts)
and a two-workspace test fixture (`ws-alpha`, `ws-beta`).

## Scenario 1 — Submitted → Delivered with zero manual edits (SC-001)

```bash
# 1. Submit the demo problem as ws-alpha
POST /submissions              → 201, note submissionId; classification=initiation
# 2. Confirm classification (human step)
POST /submissions/{id}/confirm-classification {"confirmedCaseType":"initiation"}
# 3. Open the case against a Feature
POST /cases {submissionId, featureId}   → 201; status=Planned; definitionSnapshot populated
# 4. Start the pipeline
POST /cases/{caseId}/start              → 202
# 5. Poll until Delivered
GET  /cases/{caseId}                    → status=Delivered; all 3 personas "validated"
```

**Expected**: no manual DB access at any step; `definitionSnapshot` lists exact `(key,version)`
pairs frozen at step 3 and unchanged at delivery.

## Scenario 2 — Replay reconstructs every AI output (SC-002)

```bash
POST /cases/{caseId}/replay
```

**Expected**: `matched == totalOperations`, `mismatched == 0`, every result has
`byteIdentical: true` and `snapshotComplete: true`. Also run the harness directly:
`./gradlew replayAudit -PcaseId=...` — asserts the same from stored snapshots only (no model calls).

## Scenario 3 — Tenant isolation (SC-003)

```bash
./gradlew test --tests "*LeakageSuite*"
```

**Expected**: 100% of cross-workspace attempts (ws-alpha token → ws-beta submission, case,
artifact, package, audit, KPI rows) return 404/denied with zero rows leaked. Suite runs in CI
on every commit.

## Scenario 4 — Injection blocked (SC-004)

```bash
./gradlew test --tests "*InjectionSeedSuite*"
# or manually: POST /submissions with the seeded malicious form from tests/security/seeds/
```

**Expected**: the pipeline runs the submission as data; the injection-symptom output check
fails validation on any persona output showing symptom markers; the case escalates rather than
advancing; audit trail records the block.

## Scenario 5 — Package integrity + Handover Record (SC-005)

```bash
GET  /cases/{caseId}/package            → manifest + manifestHash + handoverRecord
POST /cases/{caseId}/package/verify     → manifestHashValid=true, all artifact hashes valid
```

**Expected**: handoverRecord has all six mandatory provenance fields non-null (contents index,
submission ref, definition snapshot ref, artifact hashes, decision log ref, owner+nextAction).
Tamper test: flip one byte of an artifact in MinIO → verify returns `valid:false` for it.

## Scenario 6 — KPIs emitting (SC-006)

```bash
GET /metrics/kpis?metric=rubric_first_pass_rate
GET /metrics/kpis?metric=package_completeness
GET /metrics/kpis?metric=case_cost_tokens
```

**Expected**: after Scenario 1, each returns ≥1 sample for the delivered case; dashboard at
`/dashboard` renders all three.

## Scenario 7 — Token budget suspends the case (SC-007)

```bash
# Create a case with a deliberately tiny budget via test config, then start it
./gradlew test --tests "*TokenBudgetSuite*"
```

**Expected**: case transitions to `Suspended` **before** the breaching AI call is made;
no further OperationExecution rows appear; audit entry records the suspension.

## Bounded revise loop check (FR-005)

`./gradlew test --tests "*ReviseLoopSuite*"` — forces rubric failure: expect exactly 3
OperationExecution rows (attempt_no 1–3) then case status `Escalated`;
`POST /cases/{id}/escalations/{eid}/resolve` with `regenerate_with_comment` resumes the case.

## Done when

All seven scenarios green + unit/integration/security suites pass in CI. Then proceed to
`/speckit-tasks` output for the full implementation checklist.
