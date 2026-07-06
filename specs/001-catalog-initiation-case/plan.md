# Implementation Plan: Catalog Spine + Initiation Case Type

**Branch**: `001-catalog-initiation-case` | **Date**: 2026-07-06 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/001-catalog-initiation-case/spec.md`

**Note**: Filled by the `/speckit-plan` command per `.specify/templates/plan-template.md`.

## Summary

Build the D2OS Phase 1 end-to-end thin slice: a structured ProblemSubmission is classified
(DMN + human confirm), a CaseInstance pins a CaseDefinitionSnapshot at `Planned`, the
Initiation workflow (embedded Flowable BPMN, strictly sequential) executes three AI personas
through a validation pipeline (rubric ≥80% weighted, no critical fail; 2 revises then
escalate), and a hash-stamped ExecutionPackage with a full-provenance HandoverRecord is
delivered — every transition audited in-transaction and replayable byte-identically from
stored OperationExecution snapshots. Technical approach: Java 21 + Spring Boot modular
monolith, PostgreSQL 16 with RLS as sole system of record, embedded Flowable 7 for BPMN/DMN,
S3-compatible object storage for artifact content, thin provider-agnostic AI Gateway.

## Technical Context

**Language/Version**: Java 21 (LTS) + Spring Boot 3.x — modular monolith, one Gradle/Maven
module per bounded context (per source plan FM.4 / PD-1)

**Primary Dependencies**: Flowable 7 (embedded BPMN 2.0 + DMN decision tables), Spring Data
JPA, Spring Security (workspace-scoped authN/Z), provider SDKs behind internal AI Gateway
abstraction (Anthropic-first, provider-agnostic per AS-5)

**Storage**: PostgreSQL 16 — system of record (AD-6); row-level security keyed on
`workspace_id` (AD-10/T2); JSONB for definition bodies; S3 API (AWS S3 prod / MinIO dev) for
artifact content blobs with SHA-256 hashes

**Testing**: JUnit 5 + Testcontainers (Postgres, MinIO) for integration; ArchUnit for module
boundary enforcement; dedicated suites: T1 injection seeds, T2 cross-tenant leakage, T6
append-only grant test, NFR-6 replay-audit harness

**Target Platform**: Single-region cloud, Linux container (one deployable); on-prem path kept
open by embedded-engine choice (Q6)

**Project Type**: Web service (modular monolith backend + minimal Problem Form / dashboard UI)

**Performance Goals**: Single-threaded persona execution acceptable in Phase 1 (concurrency is
Phase 2 / NFR-1); queue-and-resume job config so engine wait-states survive restart (NFR-4)

**Constraints**: Per-case token budget → Case `Suspended` on breach (NFR-7); audit entry and
state change committed in the same transaction (Principle V); append-only DB grants on the
audit stream (T6-a); encryption at rest for submissions/artifacts (T3-b)

**Scale/Scope**: 1–3 devs (AS-1); Phase 1 scope ≈ 90.5 person-days; 10 epics E1.1–E1.10;
~19 DB entity groups; 9 catalog assets authored/revised; single workspace-pair test fixture
for isolation proofs

## Constitution Check

*GATE: evaluated against Constitution v1.0.0 before Phase 0 research; re-checked post-design.*

| # | Principle | Compliance in this design | Status |
|---|-----------|---------------------------|--------|
| I | Definition/Instance Immutability | `DefinitionAsset` supertype with publish-time immutability + semver enforcement; `(key, version)` resolution service; CaseDefinitionSnapshot pinned at `Planned`; no runtime override path exists anywhere | ✅ PASS |
| II | Reproducible, Bounded AI (NON-NEGOTIABLE) | OperationExecution persists prompt version, model identity/version, inputs, injected knowledge per call; replay harness verifies byte-identical reconstruction against stored outputs; personas stateless via envelope builder (no persona→persona path); submission text delimited as untrusted data (T1-a) + injection-symptom output check (T1-b); gate-comment-and-regenerate only | ✅ PASS |
| III | System of Record Integrity | PostgreSQL sole SoR; transactional outbox → event store (no dual write); generalized `trace_link`/`dependency` polymorphic edge tables (AD-7); no graph store built in Phase 1 (projection deferred to Phase 7 by design) | ✅ PASS |
| IV | Workspace Isolation & Provenance | `workspace_id` on every row + RLS policies; cross-tenant leakage suite in CI; v0 templates revised-not-discarded (7 revised + 2 GAP authored greenfield with provenance) | ✅ PASS |
| V | Default-Deny Security & Auditable Gates | Event + AuditEntry written in same tx; append-only grants on audit stream; human confirm step on classification; escalation (not silent retry) after bounded revise loop; knowledge promotion out of scope this phase (no promotion path exists = default-deny trivially holds) | ✅ PASS |

**Gate result: PASS — no violations, Complexity Tracking not required.**

Post-design re-check (after Phase 1 artifacts below): still PASS — data model contains no
mutable definition pointer (all runtime rows reference `(definition_id, definition_version)`),
and no contract exposes cross-workspace access.

## Project Structure

### Documentation (this feature)

```text
specs/001-catalog-initiation-case/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
│   └── api.yaml         # OpenAPI 3.1 — intake, case, package, replay, metrics endpoints
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
src/
├── catalog/          # BC: DefinitionAsset supertype, 8 definition tables, publish/semver,
│                     #     (key,version) resolution, CaseDefinitionSnapshot  [E1.1, E1.8]
├── tenancy/          # BC: Workspace/Project/Version/Feature, RLS, authN/Z    [E1.2]
├── intake/           # BC: ProblemSubmission schema+API, Problem Form UI,
│                     #     DMN classify + human confirm, sensitivity tagging  [E1.3]
├── casecore/         # BC: Case state machine, Event+AuditEntry same-tx,
│                     #     outbox, runtime tables (PersonaInvocation,
│                     #     OperationExecution, ActivityExecution, ActionItem) [E1.4]
├── orchestration/    # BC: embedded Flowable, Initiation BPMN, DMN approach
│                     #     table, wait-state mirroring, queue-and-resume      [E1.5]
├── persona/          # BC: envelope builder, AI Gateway v1, token budget,
│                     #     template renderer, validation pipeline, revise
│                     #     loop, injection-symptom check, snapshots           [E1.6]
├── artifacts/        # BC: ArtifactRevision + object storage, trace_link/
│                     #     dependency edges, package manifest + hash,
│                     #     HandoverRecord                                     [E1.7]
├── observability/    # KPI emission + minimal dashboard                       [E1.9]
└── replay/           # Replay harness: reconstruct + diff                     [E1.10]

catalog/              # Authored catalog content (Phase 0 structure, AD-11)
├── templates/        # 7 revised v0 + 2 greenfield (Task Breakdown, Handover Record)
├── personas/         # 3 PersonaDefinitions
├── playbooks/        # 3 PlaybookDefinitions
├── workflows/        # Initiation WorkflowDefinition (BPMN) + CaseTypeDefinition
└── rules/            # 1 RuleDefinition (DMN classify) + 3 RubricDefinitions + prompts

tests/
├── unit/             # per bounded context
├── integration/      # submit→deliver end-to-end (Testcontainers)
├── security/         # T1 injection seeds, T2 leakage suite, T6 grant test
└── replay/           # NFR-6 replay-audit
```

**Structure Decision**: Single deployable modular monolith (PD-1) — one Spring module per
bounded context listed above; §14 "services" are logical modules, not network services.
Outbox + module boundaries (ArchUnit-enforced) keep later extraction mechanical.

## Complexity Tracking

> Constitution Check passed with no violations — this section intentionally empty.
