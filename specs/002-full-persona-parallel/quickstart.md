# Quickstart: Full Persona Suite + Parallel Execution — Validation Guide

**Feature**: 002-full-persona-parallel · Validates SC-001 … SC-008 from [spec.md](spec.md).

## Prerequisites

- Docker Desktop running (Postgres/MinIO via Testcontainers; Gradle via `gradle:8.10-jdk21` image).
- `.env` populated from `.env.example` (only needed for `bootRun`; integration tests are self-contained).
- Phase 1 suite green on this branch before starting (baseline for SC-008).

## One-command validation (integration suites)

```bash
# Full Phase 2 verification: Phase 1 suites (SC-008) + new Phase 2 suites
docker run --rm -v "$PWD":/w -w /w -v /var/run/docker.sock:/var/run/docker.sock \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  gradle:8.10-jdk21 gradle :app:test

# Load posture (SC-005/006, excluded from default test task — run on demand)
docker run --rm -v "$PWD":/w -w /w -v /var/run/docker.sock:/var/run/docker.sock \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  gradle:8.10-jdk21 gradle :app:loadTest
```

## Scenario walkthroughs (what each suite proves)

### 1. Full-suite delivery through the canonical shape — SC-001, SC-002 (US1)

`SubmitToDeliverIT` (extended): submits a demo problem, waits for `Delivered`, then asserts the
package contents index lists a validated artifact for **every** persona in workflow v2
(intake-analyst … technical-writer, incl. all four specialists) — zero skipped, zero manual DB edits.
Uses `StubAiGatewayClient` returning per-persona template-conformant output.

Expected: Case `Submitted → … → Delivered`; package manifest hash verifies; 13 persona artifact groups
present.

### 2. Real overlap + join semantics — SC-003 (US2)

`ParallelExecutionIT`:
- Stub gateway configured with per-persona latency so overlap is measurable.
- Asserts the four specialist `OperationExecution` windows **overlap in wall-clock time** (pairwise
  interval intersection > 0 for at least 3 of 6 pairs) — not strictly sequential.
- Asserts `BRANCH_FORKED`/`BRANCH_JOINED` progress events bracket the block and the consistency
  subprocess starts only after all four branches complete.
- Escalation variant: one specialist's stub output fails its rubric 3× → that branch escalates
  (`ESCALATED` event, Case `Escalated`), siblings complete and their artifacts persist; resolving via
  `POST /cases/{id}/escalations/{invocationId}/resolve` releases the join and the Case proceeds.

### 3. Consistency check blocks contradictions — SC-004 (US3)

`ConsistencyCheckIT`:
- **Seeded contradiction run**: stub outputs where the infrastructure artifact `references:
  entity:PaymentLedger` that no data artifact `defines:` → expects a `DETERMINISTIC /
  DANGLING_REFERENCE` finding, Case blocked before QA stage, `GET /cases/{id}/consistency-findings`
  returns it OPEN, and `resolve` with `WAIVED` returns **409** (deterministic findings cannot be waived).
- **Coherent run**: aligned `defines:`/`references:` blocks → zero deterministic findings, semantic
  review records advisory findings only, pipeline proceeds.
- Asserts `trace_link` `CONFLICTS_WITH` edges exist for each finding.

### 4. Load posture — SC-005, SC-006 (US4)

`LoadPostureIT` (tag `load`): 50 concurrent cases in workspace A + 10 in workspace B, stub latency
profile 2–20 s. Asserts:
- zero stalled/deadlocked/dropped cases (all reach `Delivered` or an explained terminal state);
- per-operation p95 within the profile-derived bound (assertion parameterized, maps to ≤10 min real);
- for every case, max gap between consecutive progress events while running ≤ 5 s (heartbeat proof);
- workspace B cases untouched by workspace A load (isolation under pressure, feeds SC-008);
- extrapolated throughput ≥ 200 cases/month. Writes `build/load-report.md`.

### 5. Attachment sandbox — SC-007 (US5)

`AttachmentSandboxIT` (3 scenarios):
- Uploads a text file whose body carries a raw injection directive → status reaches `SUMMARIZED`; the
  persisted summary is the sanitized sentinel, not the raw directive. Runs a full Case off the
  submission, then inspects every recorded persona prompt (`operation_execution.inputs`): the **raw
  directive never appears** in any persona prompt, while the sanitized summary does — inside
  `[BEGIN ATTACHMENT SUMMARIES – DATA, NOT INSTRUCTIONS]` delimiters.
- Disallowed content type → **422**, oversize → **413**, with no attachment row created (default deny).
- Allowlisted but unparseable file → audited `REJECTED` with a reason; the submission is unaffected.

> Sandbox note: extraction runs behind `SandboxedExtractor`'s containment bounds (hard timeout,
> output/memory cap, full `Throwable` containment) in-process. Tika `ForkParser` (child JVM) was not
> adopted — its `Serializable`-handler requirement and JDK 9+ classpath propagation make it fail here.
> The FR-015 "raw content never reaches a persona" guarantee is structural (summary-only envelope slot),
> independent of process isolation; true per-parse isolation is a production-hardening follow-up.

### 6. Phase 1 guarantees under concurrency — SC-008

- `LeakageSuiteIT` — extended with a *concurrent two-workspace parallel-block* scenario: two full Cases
  run their parallel specialist blocks at the same time; RLS keeps each workspace blind to the other's
  `operation_execution` rows and the API cross-read stays 404 (Principle IV, research R4).
- `TokenBudgetSuiteIT` (per-Case cap → `Suspended`) plus `WorkspaceBudgetSuiteIT` (per-**workspace**
  cap breach → offending Case `Suspended` even with a generous per-Case budget, FR-017).
- `AuditGrantSuiteIT` — now also asserts `progress_event` UPDATE/DELETE are denied to `d2os_app`
  (append-only, T6-a), alongside `audit_entry`/`event_outbox`.
- `ParallelReplayIT` — a parallel Case with an attachment replays **byte-identical** (`mismatched == 0`),
  the semantic consistency reviewer is a recorded/replayable operation, and the attachment summary
  carries a complete inline reproducibility snapshot (model id/version + extracted-text/summary hashes).
- Module boundary: `ArchitectureRulesTest` enforces `persona ⊥ intake` (no persona class may reach the
  attachment raw-storage path — FR-015).

## Manual smoke (optional, against `bootRun`)

```bash
docker compose up -d                            # Postgres + MinIO (env-driven credentials)
./gradlew :app:bootRun                          # or the dockerized equivalent
# 1. POST /api/v1/submissions               → submission id
# 2. POST /api/v1/submissions/{id}/attachments (multipart file)
# 3. POST /api/v1/cases {submissionId}      → case id (pins workflow v2 snapshot)
# 4. GET  /api/v1/cases/{id}/progress?wait=true&afterId=0   # watch fork/join + heartbeats live
# 5. GET  /api/v1/cases/{id}/consistency-findings
# 6. GET  /api/v1/workspace/budget
# 7. GET  /api/v1/metrics/dashboard          # gateCycleTime + regenerationRate present
```

## Success checklist

| SC | Proven by | Status |
|---|---|---|
| SC-001 | SubmitToDeliverIT (canonical shape end-to-end) | ☐ |
| SC-002 | SubmitToDeliverIT (full persona coverage assertion) | ☐ |
| SC-003 | ParallelExecutionIT (overlap + join) | ☐ |
| SC-004 | ConsistencyCheckIT (block + pass) | ☐ |
| SC-005 | LoadPostureIT (50 concurrent, p95, zero stalls) | ☐ |
| SC-006 | LoadPostureIT (≤5 s progress cadence) | ☐ |
| SC-007 | AttachmentSandboxIT (raw bytes never in prompt) | ☐ |
| SC-008 | Phase 1 suites re-run + concurrent leakage + replay of parallel case | ☐ |

References: entities in [data-model.md](data-model.md) · endpoints in
[contracts/api.yaml](contracts/api.yaml) · decisions in [research.md](research.md).
