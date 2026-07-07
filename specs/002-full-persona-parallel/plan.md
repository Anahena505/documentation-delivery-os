# Implementation Plan: Full Persona Suite + Parallel Execution

**Branch**: `002-full-persona-parallel` | **Date**: 2026-07-07 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/002-full-persona-parallel/spec.md`

## Summary

Phase 2 turns the Phase 1 three-placeholder-persona sequential thin slice into the real product: the
full authored persona suite (13 documentation personas; Knowledge Curator deferred to Phase 3) runs the
canonical Initiation shape, with the four analysis specialists (Security ∥ UX ∥ Data ∥ Infra) executing
concurrently through a BPMN parallel gateway on Flowable's async job executor. A Consistency-Check
subprocess after the join runs deterministic cross-artifact checks (blocking) plus a rubric-scored
semantic coherence review (advisory → escalate). New surfaces: sandboxed attachment
extraction/summarization before any prompt is built (AD-12 extended to uploads), per-workspace budget
rollup and rate limits on the AI Gateway (T5-b), a ≤5 s progress-event stream, a dual-state
reconciliation job (engine history ↔ domain Case state), and a load-test harness proving NFR-1/2/3
(50 concurrent cases, 200/month, ≤10 min p95 per operation). All Phase 1 guarantees (replay, RLS,
append-only audit, injection check, cost suspension) must hold unchanged under concurrency.

## Technical Context

**Language/Version**: Java 21 (Gradle toolchain; build runs via Docker `gradle:8.10-jdk21`)

**Primary Dependencies**: Spring Boot 3.3.5, Flowable 7.0.1 (embedded BPMN + DMN, async job
executor), Spring Data JPA, Flyway, AWS SDK v2 S3 client (MinIO in dev), provider-agnostic AI
Gateway (Anthropic Messages API shape), Apache Tika (new — attachment text extraction)

**Storage**: PostgreSQL 16 + pgvector (RLS-enforced, least-privilege `d2os_app` role), S3/MinIO
object store for artifact + attachment content

**Testing**: JUnit 5 + Testcontainers (docker-outside-of-docker), StubAiGatewayClient for
deterministic persona output, existing IT suites (SubmitToDeliver, Leakage, InjectionSeed,
TokenBudget, AuditGrant) extended; new ParallelExecutionIT, ConsistencyCheckIT, AttachmentSandboxIT,
LoadPostureIT

**Target Platform**: Linux server (single-region cloud), local dev via docker-compose

**Project Type**: Modular-monolith web service (10 bounded-context Gradle modules + `app` bootstrap)

**Performance Goals**: ≥50 concurrent active Cases per workspace without degradation (NFR-1);
≥200 cases/month per workspace (NFR-2); single OperationExecution ≤10 min p95 excluding human
waits; user-visible progress events ≤5 s apart (NFR-3)

**Constraints**: Parallel branches must never block siblings (async jobs, `exclusive=false`);
per-job RLS binding must hold on every concurrent executor thread; join must not discard branch
output on escalation; simultaneous AI calls capped by bounded worker pool; raw attachment bytes
never enter a prompt

**Scale/Scope**: 13 operational personas (11 newly authored + 3 placeholder upgrades), ~14 revised
v0 templates, 1 new BPMN process version, 1 consistency subprocess, ~5 new tables, ~6 new API
endpoints, 4 new integration test suites

## Constitution Check

*GATE: evaluated against Constitution v1.0.0 before Phase 0 research; re-checked after Phase 1 design.*

| # | Principle | Status | How this plan complies |
|---|---|---|---|
| I | Definition/Instance Immutability | ✅ PASS | New personas/prompts/rubrics/workflow ship as **new published DefinitionAsset versions** (initiation workflow → v2); Phase 1 placeholder definitions are superseded, never mutated. Running cases keep their pinned v1 snapshot (AD-4) — only new cases resolve v2. |
| II | Reproducible, Bounded AI (NON-NEGOTIABLE) | ✅ PASS | Every parallel branch records its own OperationExecution snapshot exactly as in Phase 1 (same code path, now on concurrent jobs). Semantic consistency review is itself a persona-style operation with prompt/model/input snapshot + rubric. Attachment summaries are recorded as operation inputs, so replay stays byte-identical. Bounded revise loop unchanged. |
| III | System of Record Integrity | ✅ PASS | Consistency findings, attachments, progress events, and reconciliation results are relational rows written with AuditEntry in the same transaction; outbox events extended, no dual writes. Cross-artifact conflicts reference artifacts via existing `trace_link` polymorphic edges (AD-7). |
| IV | Workspace Isolation | ✅ PASS | Concurrent job threads each re-bind RLS per transaction via `WorkspaceRlsBinder` (Phase 1 mechanism, now exercised in parallel — leakage suite re-run under concurrency). Attachment objects stored under workspace-scoped keys; per-workspace rate limits keyed by workspace id. |
| V | Default-Deny Security & Auditable Gates | ✅ PASS | Attachment pipeline is default-deny (allowlisted formats, size caps, reject-on-unparseable); raw bytes never reach prompts (AD-12/T1-d). Deterministic conflicts hard-block delivery; advisory findings escalate to a human decision — never silently proceed. Append-only audit grants unchanged. |

**Post-design re-check (after data-model + contracts)**: no violations introduced — new tables carry
`workspace_id` + RLS policies; no schema mutation of Phase 1 definitions; consistency semantic check
routes through the AI Gateway like every other AI call. **GATE: PASS.**

## Project Structure

### Documentation (this feature)

```text
specs/002-full-persona-parallel/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── api.yaml         # Phase 1 output (delta over Phase 1 api.yaml)
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created by /speckit-plan)
```

### Source Code (repository root)

Existing modular monolith (one Gradle module per bounded context, PD-1). Phase 2 adds **no new
module**; it extends existing ones and adds one BPMN/DMN resource set:

```text
catalog/                       # + CatalogSeedLoader v2 seed set (13 personas, prompts, rubrics, templates)
intake/
├── src/main/java/com/d2os/intake/
│   ├── attachment/            # NEW: Attachment entity/repo/service, sandboxed extraction pipeline
│   └── ...                    # SubmissionController gains attachment upload endpoint
├── src/main/resources/db/migration/V10__attachments.sql
orchestration/
├── src/main/resources/processes/initiation-v2.bpmn20.xml   # NEW: canonical shape w/ parallel block
├── src/main/resources/processes/consistency-check.bpmn20.xml # NEW: callActivity subprocess
├── src/main/java/com/d2os/orchestration/
│   ├── PersonaStepDelegate.java        # unchanged contract; reused by parallel branches
│   ├── ConsistencyCheckDelegate.java   # NEW
│   ├── EscalationBridge.java           # NEW: branch-level escalation wait/resume signalling
│   └── ReconciliationJob.java          # NEW: scheduled engine↔domain dual-state reconciler
persona/
├── src/main/java/com/d2os/persona/
│   ├── consistency/            # NEW: DeterministicCrossChecks, SemanticConsistencyOperation, findings
│   ├── gateway/                # + WorkspaceRateLimiter (T5-b), workspace budget rollup
│   └── ...                     # ExecutionEnvelopeBuilder gains attachment-summary slot
├── src/main/resources/db/migration/V11__consistency_findings.sql
casecore/
├── src/main/java/com/d2os/casecore/
│   ├── progress/               # NEW: ProgressEvent entity/emitter/heartbeat + controller (SSE/poll)
│   └── ...                     # branch-aware escalation state on CaseInstance
├── src/main/resources/db/migration/V12__progress_and_budget.sql
observability/                  # + gate cycle time, regeneration rate KPIs (§KP additions)
app/
└── src/test/java/com/d2os/app/
    ├── ParallelExecutionIT.java       # NEW (US2: overlap, join, no-block)
    ├── ConsistencyCheckIT.java        # NEW (US3: seeded contradiction blocks; coherent passes)
    ├── AttachmentSandboxIT.java       # NEW (US5: raw bytes never in prompt)
    ├── LoadPostureIT.java             # NEW (US4: 50 concurrent, p95, progress cadence) [tagged slow]
    └── ...                            # Phase 1 suites re-run unchanged (SC-008)
```

**Structure Decision**: keep the 10-module monolith; parallel execution is an orchestration+persona
concern, attachments are an intake concern, progress/budget are casecore/observability concerns —
each lands in its owning bounded context. New Flyway migrations V10–V12 continue the single ordered
migration stream (each in the module that owns the tables, consistent with V1–V9).

## Complexity Tracking

> No constitution violations — table intentionally empty.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| — | — | — |
