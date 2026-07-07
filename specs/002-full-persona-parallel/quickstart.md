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

`AttachmentSandboxIT`:
- Uploads a seeded malicious document (injection-style text) → status reaches `SUMMARIZED`.
- Captures every prompt sent through the stub gateway and asserts the **raw attachment text never
  appears** in any persona prompt — only the summary, inside untrusted-data delimiters.
- Uploads an unparseable/oversized file → `REJECTED` (or 413/422 at upload), audited, Case unaffected.
- Injection-echo variant: if a persona output nonetheless echoes injected instructions, the Phase 1
  injection-symptom check still blocks it (re-uses `InjectionEchoAiGatewayClient`).

### 6. Phase 1 guarantees under concurrency — SC-008

Re-run unchanged: `LeakageSuiteIT` (extended with a *concurrent two-workspace parallel-block*
scenario), `InjectionSeedSuiteIT`, `TokenBudgetSuiteIT` (plus workspace-cap variant → offending case
`Suspended`), `AuditGrantSuiteIT` (now also covers `progress_event` append-only), and `ReplayHarness`
verification that every AI output of a parallel case — including the semantic consistency review and
attachment summaries — replays byte-identical from stored snapshots.

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
