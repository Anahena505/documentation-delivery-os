# Implementation Plan: Assessment + Enhancement Case Types

**Branch**: `004-assessment-enhancement-case-types` | **Date**: 2026-07-07 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/004-assessment-enhancement-case-types/spec.md`

## Summary

Phase 4 adds the second and third case types as **pure catalog content**: Assessment (read-only —
findings + recommendation package, no mutating artifacts) and Enhancement (delta-docs + impact
analysis trace-linked to a prior delivered baseline), each shipped as authored Definitions
(CaseType, Workflow BPMN, Templates, Rules, Rubrics) through `CatalogSeedLoader` — **zero new
database tables** (§16 proof, SC-007). Intake gains DMN case-type classification with a mandatory
human confirm/override step (recorded as a Decision), plus a conditional-artifact DMN whose output
is folded into the `CaseDefinitionSnapshot`'s expected-artifact set before pinning (e.g., DPIA iff
personal data). The Q2 guard lands as optimistic concurrency on the Feature aggregate: a guarded
`UPDATE … WHERE aggregate_version = ? AND active_mutating_case_id IS NULL` admits exactly one
active mutating case per Feature (Assessment exempt), released in the same transaction as the
case's terminal transition. Only two column-only migrations (V15 tenancy, V16 intake) — no new
tables anywhere.

## Technical Context

**Language/Version**: Java 21 (Gradle toolchain; build runs via Docker `gradle:8.10-jdk21`)

**Primary Dependencies**: Spring Boot 3.3.5, Flowable 7.0.1 (embedded BPMN + DMN — two new
process definitions, two new DMN decision tables), Spring Data JPA, Flyway; no new third-party
dependency in this phase

**Storage**: PostgreSQL 16 + pgvector unchanged (RLS-enforced, `d2os_app` role); **zero new
tables** — V15/V16 are column additions only; S3/MinIO unchanged

**Testing**: JUnit 5 + Testcontainers, StubAiGatewayClient; existing suites re-run unchanged
(SC-008); new CaseRoutingIT, AssessmentReadOnlyIT, EnhancementBaselineIT, MutatingGuardIT,
ConditionalArtifactIT, SchemaFreezeIT (information_schema table-set assertion for SC-007)

**Target Platform**: Linux server (single-region cloud), local dev via docker-compose

**Project Type**: Modular-monolith web service (11 bounded-context modules; Phase 4 adds **no new
module** — extends catalog, intake, casecore, orchestration, artifacts seed content and services)

**Performance Goals**: Classification (DMN + proposal persistence) adds ≤ 1 s to intake; the Q2
guard is a single guarded UPDATE (no measurable creation-path cost); Phase 2 load posture must
hold with three case types active

**Constraints**: Zero new tables (FR-016/SC-007 — hard §16 target); optimistic guard, never a
lock held across a case lifecycle (Q2); Assessment must be structurally unable to mutate
(enforcement at the artifact write path, not by persona good behavior); Enhancement must reference
the baseline via existing `trace_link` edges, never copy it; human confirm is the authority of
record on case type (machine proposal advisory)

**Scale/Scope**: Catalog: 2 CaseType, 2 Workflow (BPMN), ~8 Template, 2 Rule (DMN), ~4 Rubric
Definitions + prompt sets; 2 column-only migrations; ~4 new/changed API endpoints; 6 new IT suites

## Constitution Check

*GATE: evaluated against Constitution v1.0.0 before Phase 0 research; re-checked after Phase 1 design.*

| # | Principle | Status | How this plan complies |
|---|---|---|---|
| I | Definition/Instance Immutability | ✅ PASS | Both case types ship as new published DefinitionAsset versions (CaseType/Workflow/Template/Rule/Rubric) via the seed loader; nothing existing is mutated. Cases pin their type's definitions into `CaseDefinitionSnapshot` at `Planned` exactly as Initiation does — the conditional-artifact DMN output is folded in **before** pinning, so the expected set is frozen with the snapshot. |
| II | Reproducible, Bounded AI (NON-NEGOTIABLE) | ✅ PASS | Assessment/Enhancement personas run through the unchanged Phase 2/3 execution path (snapshots, rubrics, revise loop, knowledge injection with same-tx injection snapshots). Classification is D2 (rules route) + human confirm (human authority) — the machine proposal never creates a Case by itself; overrides are recorded Decisions. |
| III | System of Record Integrity | ✅ PASS | Routing proposal/confirmation live as columns on `problem_submission` + Decision/AuditEntry rows in the same transaction; Enhancement baseline anchoring uses the existing polymorphic `trace_link` edges (AD-7) — no new relationship tables, no dual writes. |
| IV | Workspace Isolation & Provenance | ✅ PASS | No new tables ⇒ no new RLS surface; new columns live on already-policied tables. Enhancement provenance is explicit: every delta/impact artifact carries a `DERIVES_FROM` trace link to the pinned baseline ArtifactRevision. Catalog extension by authoring (not schema) is Principle IV's "extend existing structure" made literal. |
| V | Default-Deny Security & Auditable Gates | ✅ PASS | Assessment read-only enforcement is default-deny at the artifact write path (allowlist of findings/recommendation kinds; blocked attempts audited). Delivery is blocked until conditionally required artifacts exist. Case-type confirmation/override and guard conflicts are auditable records. |

**Post-design re-check (after data-model + contracts)**: no violations introduced — V15/V16 add
columns to existing RLS-policied tables only; SchemaFreezeIT enforces the no-new-tables invariant
in CI. **GATE: PASS.**

## Project Structure

### Documentation (this feature)

```text
specs/004-assessment-enhancement-case-types/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── api.yaml         # Phase 1 output (delta over Phase 3 api.yaml)
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created by /speckit-plan)
```

### Source Code (repository root)

No new module; the phase is catalog content + thin engineering in five existing modules:

```text
catalog/                       # + CatalogSeedLoader v4 seed set: Assessment + Enhancement
│                              #   CaseType/Workflow/Template/Rule/Rubric/Prompt definitions
orchestration/
├── src/main/resources/processes/assessment-v1.bpmn20.xml    # NEW: findings→recommendation shape
├── src/main/resources/processes/enhancement-v1.bpmn20.xml   # NEW: baseline→delta→impact shape
├── src/main/resources/dmn/case-type-classification.dmn      # NEW: submission attrs → proposed type
├── src/main/resources/dmn/conditional-artifacts.dmn         # NEW: attrs → extra required artifacts
├── src/main/java/com/d2os/orchestration/
│   └── BaselineResolutionDelegate.java   # NEW: resolve delivered baseline, seed DERIVES_FROM edges
intake/
├── src/main/java/com/d2os/intake/
│   ├── CaseTypeClassificationService.java # NEW: DMN proposal + ambiguity → UNDETERMINED
│   └── ...                                # SubmissionController + confirm/override endpoint
├── src/main/resources/db/migration/V16__case_type_classification.sql   # columns only
casecore/
├── src/main/java/com/d2os/casecore/
│   ├── MutatingCaseGuard.java             # NEW: guarded UPDATE on feature aggregate (Q2)
│   ├── CaseService.java                   # + guard on create, slot release on terminal (same tx)
│   └── ...                                # snapshot pinning folds conditional artifacts in
tenancy/
├── src/main/resources/db/migration/V15__feature_mutating_guard.sql     # columns only
artifacts/
└── src/main/java/com/d2os/artifacts/      # + read-only enforcement at write path (case-type
                                           #   capability flag; blocked attempt → audit + continue)
app/
└── src/test/java/com/d2os/app/
    ├── CaseRoutingIT.java                 # US1: 3-way classification, confirm/override, ambiguity
    ├── AssessmentReadOnlyIT.java          # US2: findings-only package, blocked write audited
    ├── EnhancementBaselineIT.java         # US3: delta+impact trace-linked; no-baseline rejection
    ├── MutatingGuardIT.java               # US4: concurrent creates → exactly one; exemption; release
    ├── ConditionalArtifactIT.java         # US5: DPIA required iff personal data; delivery blocked
    └── SchemaFreezeIT.java                # SC-007: information_schema table set unchanged
```

**Structure Decision**: keep the 11-module monolith; the phase's essence is that new case types
are **authored, not engineered** — the only code is routing (intake), the guard (casecore/tenancy),
baseline resolution (orchestration), and write-path enforcement (artifacts). Migrations continue
the ordered stream: **V15** (tenancy — feature guard columns), **V16** (intake — classification
columns). Both are column additions to existing RLS-policied tables; SchemaFreezeIT pins the table
inventory.

## Complexity Tracking

> No constitution violations — table intentionally empty.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| — | — | — |
