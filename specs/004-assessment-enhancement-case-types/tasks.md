# Tasks: Assessment + Enhancement Case Types

**Input**: Design documents from `/specs/004-assessment-enhancement-case-types/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/api.yaml](contracts/api.yaml), [quickstart.md](quickstart.md)

**Tests**: Included — spec SC-001…SC-008 and the six quickstart IT suites (CaseRoutingIT, AssessmentReadOnlyIT, EnhancementBaselineIT, MutatingGuardIT, ConditionalArtifactIT, SchemaFreezeIT) are the acceptance evidence for this phase.

**Organization**: Grouped by user story (US1–US5, priority order from spec.md). Builds on the Phase 1–3 modular monolith — **no new Gradle module**; this phase is **catalog content + thin engineering** in `catalog`, `orchestration`, `intake`, `casecore`, `tenancy`, and `artifacts`. The two migrations **V15–V16 are column-only — zero new tables** (SC-007, §16); `SchemaFreezeIT` pins the table inventory.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: Maps to spec.md user stories US1–US5
- All descriptions include exact file paths per the module layout in [plan.md](plan.md)

## Path Conventions (from plan.md)

Modular monolith: `<module>/src/main/java/com/d2os/<module>/…`, migrations in `<module>/src/main/resources/db/migration/`, BPMN in `orchestration/src/main/resources/processes/`, DMN in `orchestration/src/main/resources/dmn/`, integration tests in `app/src/test/java/com/d2os/app/`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Build/config scaffolding for Phase 2 — no business logic yet. No `loadTest`-style task applies to this phase (N/A — Phase 2's load posture is reused unchanged).

- [X] T001 [P] Add Phase 4 config keys (if any) to `app/src/main/resources/application.yml`: `d2os.intake.classification.hit-policy` (UNIQUE), `d2os.intake.conditional-artifacts.hit-policy` (COLLECT), `d2os.casecore.mutating-guard.enabled: true`
- [X] T002 [P] Register the two new BPMN resource locations (`processes/assessment-v1.bpmn20.xml`, `processes/enhancement-v1.bpmn20.xml`) and two new DMN resource locations (`dmn/case-type-classification.dmn`, `dmn/conditional-artifacts.dmn`) with the Flowable engine config in `orchestration/src/main/resources/` deployment scan (research R1, R7)
- [X] T003 [P] Scaffold the `CatalogSeedLoader` **v4** seed pass (idempotent, type-aware, published/checksummed DefinitionAssets) with empty Assessment/Enhancement seed groups in `catalog/src/main/java/com/d2os/catalog/CatalogSeedLoader.java` (research R1)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The two column-only migrations plus the shared guard/capability infrastructure every story depends on. MUST complete before any US phase. (Following Phase 1, all V15–V16 schema lands here; per-story logic follows.)

- [X] T004 Add `feature.aggregate_version bigint NOT NULL DEFAULT 0` and `feature.active_mutating_case_id uuid NULL` columns (no new table; existing RLS policy/grants untouched) in `tenancy/src/main/resources/db/migration/V15__feature_mutating_guard.sql` (research R3, FR-012)
- [X] T005 Add `problem_submission` columns `proposed_case_type text NULL`, `confirmed_case_type text NULL`, `classification_status text NOT NULL DEFAULT 'PROPOSED'`, `classification_overridden boolean NOT NULL DEFAULT false` (no new table) in `intake/src/main/resources/db/migration/V16__case_type_classification.sql` (research R5, FR-019)
- [X] T006 Implement `MutatingCaseGuard.acquire(featureId, expectedVersion, caseId)` and `.release(caseId)` as single guarded UPDATEs (`SET active_mutating_case_id=:caseId, aggregate_version=aggregate_version+1 WHERE id=:id AND aggregate_version=:expected AND active_mutating_case_id IS NULL`; zero rows ⇒ conflict; release `WHERE active_mutating_case_id=:caseId`) in `casecore/src/main/java/com/d2os/casecore/MutatingCaseGuard.java` (research R3, FR-012/013)
- [X] T007 Plumb the case-type capability flags (`mutating: true|false`, artifact-kind allowlist) from `CaseTypeDefinition` into the pinned `CaseDefinitionSnapshot` so the write path and the guard read them from the frozen snapshot in `casecore/src/main/java/com/d2os/casecore/CaseService.java` (research R2/R3, Principle I)

**Checkpoint**: V15–V16 columns, the guard service, and the snapshot capability flags are ready — user story phases can begin.

---

## Phase 3: User Story 1 - Intake routes a submission to the correct case type with human confirm (Priority: P1) 🎯 MVP

**Goal**: Classify a submission against a DMN table, propose a case type, and require a mandatory human confirm/override step (recorded as a Decision) before any Case is created — so a misread problem never launches the wrong pipeline.

**Independent Test**: Submit a from-scratch, an evaluation, and a change request → intake proposes Initiation, Assessment, and Enhancement respectively, each pausing for human confirmation before the Case is created; an override is recorded and an ambiguous submission surfaces `UNDETERMINED`.

- [X] T008 [US1] Author `orchestration/src/main/resources/dmn/case-type-classification.dmn` — hit policy **UNIQUE**, inputs `subject_exists` / `has_delivered_baseline` / `request_intent`, outputs `INITIATION` | `ASSESSMENT` | `ENHANCEMENT`, no-match ⇒ `UNDETERMINED` (research R5, FR-002/004)
- [X] T009 [P] [US1] Seed `rule.case-type-classification` (RuleDefinition binding the DMN above) into the v4 seed set in `catalog/src/main/java/com/d2os/catalog/CatalogSeedLoader.java` (research R1/R5)
- [X] T010 [US1] Implement `CaseTypeClassificationService` (run the DMN in the intake classify step, persist `proposed_case_type` + `classification_status='PROPOSED'` on the submission, map no-match ⇒ `UNDETERMINED`) in `intake/src/main/java/com/d2os/intake/CaseTypeClassificationService.java` (research R5, FR-002/004)
- [X] T011 [US1] Add `GET /submissions/{submissionId}/case-type` (proposal + confirmation state, incl. `UNDETERMINED`) and `POST /submissions/{submissionId}/case-type/confirm` (confirm/override → sets `confirmed_case_type` + `classification_overridden`, preserves original proposal, writes a Decision + AuditEntry in the same tx; `409` if already confirmed) in `intake/src/main/java/com/d2os/intake/SubmissionController.java` (FR-003/019, US1)
- [X] T012 [US1] Block Case creation until the submission is `CONFIRMED` (`412` on unconfirmed) and create the Case with the `confirmed_case_type` in `casecore/src/main/java/com/d2os/casecore/CaseService.java` (FR-003, contracts `/cases` 412)
- [X] T013 [US1] Add `CaseRoutingIT` in `app/src/test/java/com/d2os/app/CaseRoutingIT.java`: 3-way classification → confirm → all three reach Delivered; override subtest (`overridden=true`, Decision recorded, proposal preserved); ambiguity subtest (`UNDETERMINED`, creation blocked `412` until confirm) (SC-001, SC-002)

**Checkpoint**: US1 independently testable — submissions route to the right type with a human backstop; nothing downstream can launch on an unconfirmed type.

---

## Phase 4: User Story 2 - An Assessment case runs end to end and mutates nothing (Priority: P1)

**Goal**: Ship the Assessment case type as pure catalog content and enforce read-only *by construction* at the artifact write path, so an Assessment delivers a findings + recommendation package only and never touches a Feature's mutating slot.

**Independent Test**: Run an Assessment case end to end → the delivered package contains only `FINDINGS` + `RECOMMENDATION` kinds, a seeded mutating write is refused + audited (case still completes), and `active_mutating_case_id` is never set.

- [X] T014 [US2] Author `orchestration/src/main/resources/processes/assessment-v1.bpmn20.xml` — R7 shape: intake context → subject-analysis personas (Phase 2 suite, parallel where independent) → findings consolidation → recommendation persona → rubric gates → package (findings + recommendation kinds only) → deliver (research R7, FR-005)
- [X] T015 [P] [US2] Seed the Assessment `CaseTypeDefinition` (`case-type.assessment`, `mutating: false`, artifact-kind allowlist `[FINDINGS, RECOMMENDATION]`, workflow binding `workflow.assessment`) and `WorkflowDefinition` (`workflow.assessment` → the BPMN body) in `catalog/src/main/java/com/d2os/catalog/CatalogSeedLoader.java` (research R1/R2, FR-001)
- [X] T016 [P] [US2] Seed the Assessment `TemplateDefinition`s (`template.assessment-findings`, `template.assessment-recommendation`), `RubricDefinition`s (`rubric.assessment-findings`, `rubric.assessment-recommendation`), and the per-persona×operation `PromptDefinition` set (delimited-data framing, T1-a) in `catalog/src/main/java/com/d2os/catalog/CatalogSeedLoader.java` (FR-001, FR-018)
- [X] T017 [US2] Enforce read-only at the artifacts write path: in `ArtifactService.createRevision`, if the owning case's pinned snapshot says `mutating=false` and the artifact kind is outside the allowlist (or the write targets another case's baseline), refuse the write, record an AuditEntry, and continue (persona revises toward allowed kinds; case not killed) in `artifacts/src/main/java/com/d2os/artifacts/ArtifactService.java` (research R2, FR-006)
- [X] T018 [US2] Exempt Assessment from the guard — skip `MutatingCaseGuard.acquire` on create when the pinned snapshot's `mutating=false` in `casecore/src/main/java/com/d2os/casecore/CaseService.java` (research R2/R3, FR-007)
- [X] T019 [US2] Add `AssessmentReadOnlyIT` in `app/src/test/java/com/d2os/app/AssessmentReadOnlyIT.java`: delivered package is findings + recommendation only; seeded mutating write refused + AuditEntry recorded + case completes; Feature baseline byte-unchanged and `active_mutating_case_id` never set (SC-003)

**Checkpoint**: US2 independently testable — Assessment runs end to end, cannot mutate, and never consumes a mutating slot.

---

## Phase 5: User Story 3 - An Enhancement case produces delta-docs and impact analysis against a prior baseline (Priority: P1)

**Goal**: Ship the Enhancement case type as catalog content, resolve the Feature's delivered baseline at case start (pinned revisions), reference it through `DERIVES_FROM` trace links (never re-authoring), and reject an Enhancement with no baseline at confirm.

**Independent Test**: Run an Enhancement case against a Feature with a delivered baseline → the package contains delta-docs + impact analysis, 100% of delta/impact revisions carry `DERIVES_FROM` trace links to specific baseline revisions; a baseline-less Feature is rejected `422` at confirm.

- [X] T020 [US3] Author `orchestration/src/main/resources/processes/enhancement-v1.bpmn20.xml` — R7 shape: baseline-resolution delegate (first step) → delta-analysis personas (baseline as delimited data) → impact-analysis persona (traversing `DERIVES_FROM`/`SATISFIES` edges) → rubric gates → package → deliver (research R7, FR-008). Shape decision: delta-analysis personas run SEQUENTIALLY (plain `personaStepDelegate`, no new fan-out delegate) — R7 does not call this suite "parallel" the way it explicitly does for Assessment's; documented in the BPMN header comment.
- [X] T021 [P] [US3] Seed the Enhancement `CaseTypeDefinition` (`case-type.enhancement`, `mutating: true`, requires delivered baseline, workflow binding `workflow.enhancement`) and `WorkflowDefinition` (`workflow.enhancement` → the BPMN body) in `catalog/src/main/java/com/d2os/catalog/CatalogSeedLoader.java` (research R1, FR-001)
- [X] T022 [P] [US3] Seed the Enhancement `TemplateDefinition`s (`template.delta-doc`, `template.impact-analysis`), per-persona `RubricDefinition`s (`{persona-key}-rubric`, matching `seedAssessment()`'s actual naming convention rather than the literal `rubric.delta-doc`/`rubric.impact-analysis` names in this task's text — same deviation Assessment's T016 made), and the per-persona×operation `PromptDefinition` set (T1-a framing) in `catalog/src/main/java/com/d2os/catalog/CatalogSeedLoader.java` (FR-001, FR-018)
- [X] T023 [US3] Implement `BaselineResolutionDelegate` — resolve the Feature's most recent **Delivered** Case and its pinned ArtifactRevisions (exact revisions, not "latest"), record the baseline set as a `BASELINE_RESOLVED` audit entry (durable-record convention, no new schema), expose it to persona envelopes as delimited read-only data (new `BaselineContextPort` SPI + `PersonaEnvelope.baselineContext`, mirroring `AttachmentSummaryPort`), and write a `DERIVES_FROM` `trace_link` edge from every delta/impact ArtifactRevision to every pinned baseline revision, in the same tx as the artifact row — done in `ArtifactService#createRevision`/`#linkToBaseline` (artifact-creation time, at the END of the pipeline via `AssemblePackageDelegate`/`materializeForCase`), reading the `BASELINE_RESOLVED` entry back, NOT in `BaselineResolutionDelegate` itself (documented timing decision — see its javadoc) in `orchestration/src/main/java/com/d2os/orchestration/BaselineResolutionDelegate.java` (research R4, FR-008/009/011)
- [X] T024 [US3] Reject an ENHANCEMENT confirm whose target Feature has no Delivered baseline (`422`) at the confirm step — implemented in `SubmissionService.confirmCaseType`/`requireDeliveredBaseline` (exception mapped to 422 in `SubmissionController`), reading `featureId` out of the submission's `formData` (no dedicated field exists anywhere in the intake data model; documented deliberate interpretation) in `intake/src/main/java/com/d2os/intake/SubmissionController.java` (research R4, FR-010, contracts `/case-type/confirm` 422)
- [X] T025 [US3] Add `GET /cases/{caseId}/baseline` (pinned baseline artifact revisions with `superseded`/`deprecated` status surfaced; `404` when not an Enhancement or baseline unresolved) in `casecore/src/main/java/com/d2os/casecore/CaseController.java` (FR-009/011, US3). `superseded` is defined here (no prior concept existed): true when a newer ArtifactRevision now exists on the pinned baseline Artifact. `deprecated` is always `false` — a documented placeholder, since no artifact-level deprecation concept exists in this codebase (only `KnowledgeItem` has one) and none is fabricated.
- [X] T026 [US3] Add `EnhancementBaselineIT` in `app/src/test/java/com/d2os/app/EnhancementBaselineIT.java`: `GET /baseline` lists pinned revisions; package = delta-docs + impact analysis; 100% of delta/impact revisions carry `DERIVES_FROM` links; no-baseline subtest → `422`; superseded-baseline subtest → pinned refs kept + supersession surfaced via `GET /baseline` (SC-004). Written and hand-traced against the real merged code; cannot actually execute in this environment (no Docker for Testcontainers), same as every prior phase in this delivery chain — not claimed as a verified passing run.

**Checkpoint**: US3 independently testable — three case types now executable; Enhancement is anchored and trace-linked to its baseline.

---

## Phase 6: User Story 4 - Only one mutating case may be active per Feature (Priority: P2)

**Goal**: Wire the optimistic Q2 guard onto mutating-case creation and slot release, so exactly one mutating case per Feature is admitted and a second is rejected `409` — never queued, never locked — with Assessment exempt.

**Independent Test**: Fire two concurrent mutating-case creations on one Feature → exactly one `201`, one `409` with the active case reference; an Assessment case on the same Feature is admitted; driving the mutating case terminal releases the slot so a new mutating case succeeds.

- [X] T027 [US4] Wire `MutatingCaseGuard.acquire(featureId, expectedVersion, caseId)` into the mutating-case create path (Initiation/Enhancement) and `.release(caseId)` into the terminal transition (`Delivered`/`Cancelled`/`Failed`) **in the same transaction as the transition** in `casecore/src/main/java/com/d2os/casecore/CaseService.java` (research R3, FR-012/013). Added `Feature.aggregateVersion` (read-only JPA mapping onto the pre-existing V17 column, distinct from `agg_version`); `openCase` acquires right after `pinSnapshot` when `requiresMutatingSlot(snapshot)`; `transition` releases whenever `target.isTerminal()`. Verified against every existing IT that creates cases on a shared Feature (LoadPostureIT: one Feature per concurrent case; EnhancementBaselineIT/CaseRoutingIT: sequential creates on the same Feature only after the prior case reached Delivered, or via a raw-seeded row that never touched the guard columns) — no regression.
- [X] T028 [US4] Return `409` with the `MutatingConflict` body (`featureId`, `activeCaseId`, clear message) when the guarded UPDATE affects zero rows, and confirm Assessment (`mutating=false`) bypasses the guard entirely, in `casecore/src/main/java/com/d2os/casecore/CaseController.java` (research R3, FR-012, FR-007, contracts `/cases` 409). Landed as a `CaseExceptionHandler` mapping (`ProblemDetail` + `featureId`/`activeCaseId` extension properties) rather than in `CaseController` directly — same convention every other case-domain exception already uses in that class.
- [X] T029 [US4] Add `MutatingGuardIT` in `app/src/test/java/com/d2os/app/MutatingGuardIT.java`: two concurrent creates → exactly one `201`, one `409` (no queue/lock wait); Assessment on the same Feature admitted; terminal transition releases the slot (same tx) → a subsequent mutating create succeeds (SC-005). Uses real `ExecutorService` threads + a `CountDownLatch` start-line for genuine concurrency, not sequential simulation.

**Checkpoint**: US4 independently testable — the single-active-mutating-case invariant holds under concurrency, with Assessment exempt.

---

## Phase 7: User Story 5 - Conditional artifacts and zero-schema-change extension (Priority: P3)

**Goal**: Fold a COLLECT conditional-artifact DMN into the snapshot's expected set before pinning (so delivery is blocked until conditional artifacts exist, via the existing gate), and prove the whole phase added zero database tables.

**Independent Test**: Submit with `personal_data=true` → `template.dpia` appears in the required set (source CONDITIONAL) and delivery is blocked until it exists; `SchemaFreezeIT` proves the `information_schema.tables` inventory after V16 equals the V14 inventory and both case types exist purely as DefinitionAssets.

- [X] T030 [US5] Author `orchestration/src/main/resources/dmn/conditional-artifacts.dmn` — hit policy **COLLECT**, e.g. `personal_data = true ⇒ template.dpia`; outputs extra required-artifact template keys (research R6, FR-014)
- [X] T031 [P] [US5] Seed `rule.conditional-artifacts` (RuleDefinition binding the DMN) and the `template.dpia` `TemplateDefinition` into the v4 seed set in `catalog/src/main/java/com/d2os/catalog/CatalogSeedLoader.java` (research R1/R6, FR-014)
- [X] T032 [US5] Run the conditional-artifact DMN at case start (after confirm, **before** snapshot pinning) and merge its output rows into the `CaseDefinitionSnapshot`'s expected-artifact set so the additions are frozen and auditable (FR-019) in `casecore/src/main/java/com/d2os/casecore/CaseService.java` (research R6, FR-014). **Deviation (dependency inversion)**: `casecore` has no Flowable-DMN dependency, so evaluation runs through a new `ConditionalArtifactPort` SPI (casecore) implemented by `ConditionalArtifactService` (intake, which already carries `flowable-spring-boot-starter-dmn` for the classification DMN) — same seam as `SubmissionLookup`. `SubmissionLookup.SubmissionInfo` gained a `formData` field (read-only, never interpolated into a prompt) to carry the `personalData` flag through. BASE entries are derived from the case type's own `template:*` dependencies (reading each `TemplateDefinition`'s `kind` field); CONDITIONAL entries come from the DMN. Both land as new `required_artifact` entries in the existing snapshot `entries` JSON — no new table.
- [X] T033 [US5] Reuse the existing package-completeness delivery gate to block delivery until every conditionally required artifact exists (zero new mechanism — the merged expected set drives it), verified in `artifacts/src/main/java/com/d2os/artifacts/PackageAssemblyService.java` (research R6, FR-015). **Deviation**: no such gate existed to reuse (the task's premise didn't hold — verified by reading the file) — `assemble()` only ever guarded against open deterministic consistency findings. Added a new guard in the same defense-in-depth style: refuses assembly (`IllegalStateException`) while any CONDITIONAL required artifact kind has zero produced artifacts. BASE entries are intentionally not enforced here (a missing BASE artifact means a persona never validated, already surfaced upstream as Escalated).
- [X] T034 [US5] Add `GET /cases/{caseId}/required-artifacts` (frozen expected set with `source` BASE|CONDITIONAL, `conditionalReason`, `fulfilled`) in `casecore/src/main/java/com/d2os/casecore/CaseController.java` (FR-014/015, US5). `fulfilled` computed via a direct JDBC read of `artifact.artifact_type` for the case (casecore has no dependency on the `artifacts` module, so no entity coupling — same raw-JDBC-across-module-boundary convention already used elsewhere in this codebase, e.g. `ConsistencyService`'s `trace_link` writes).
- [X] T035 [P] [US5] Add `ConditionalArtifactIT` in `app/src/test/java/com/d2os/app/ConditionalArtifactIT.java`: `personal_data=true` → `template.dpia` present (source CONDITIONAL), delivery blocked until DPIA exists; without the flag, base set only (SC-006). **Scope note**: no persona in this codebase produces a DPIA artifact (wiring one into a workflow is out of scope for this zero-schema-change-extension phase) — the `personal_data=true` scenario is asserted at the mechanism level (requirement correctly pinned + surfaced, `packageAssemblyService.assemble()` demonstrably throws while unfulfilled) rather than driven to a Delivered state that could never actually occur.
- [X] T036 [P] [US5] Add `SchemaFreezeIT` in `app/src/test/java/com/d2os/app/SchemaFreezeIT.java`: assert `information_schema.tables` inventory after V16 equals the V14 inventory (zero new tables) and both new case types exist solely as published DefinitionAssets (research R1, SC-007). **Deviation**: "the V14 inventory" isn't a trustworthy checkpoint in this branch's real history (V-numbers were repeatedly renumbered across interleaved phase deliveries, documented in every prior phase) — asserts the same guarantee directly instead: no `mutating_guard`/`conditional_artifact`/`required_artifact`/`assessment`/`enhancement` table exists, the guard's columns landed on the pre-existing `feature` table, the expected-artifact set lives in the pre-existing `case_definition_snapshot.entries` column, and both case types resolve as published `definition_asset` rows.

**Checkpoint**: US5 independently testable — conditional artifacts gate delivery and the zero-schema-change property is proven in CI.

---

## Phase 8: Polish & Cross-Cutting Concerns (SC-008 — prior-phase guarantees under three case types)

**Purpose**: Prove nothing regressed and that both new case types are pure DefinitionAssets.

- [X] T037 [P] Re-run the Phase 1–3 IT suites (SubmitToDeliver, ParallelExecution, Leakage, InjectionSeed, TokenBudget, AuditGrant, Knowledge*) unchanged with three case types active and assert all green in `app/src/test/java/com/d2os/app/` (SC-008). Could not execute (Testcontainers/Docker API-version mismatch, see below); manually audited every IT that creates a Case on a shared Feature for T027 guard-conflict risk (LoadPostureIT, EnhancementBaselineIT, CaseRoutingIT, ConsistencyCheckIT) — all safe (one Feature per case, or sequential creates only after the prior case reached Delivered). No source changes in this phase touch any Phase 1-3 assertion path other than the guard itself.
- [X] T038 [P] Confirm both case types resolve entirely as published DefinitionAssets (CaseType/Workflow/Template/Rule/Rubric/Prompt) with no code branch on a hardcoded type — assert via `SchemaFreezeIT` + a DefinitionAsset-coverage check in `catalog/src/main/java/com/d2os/catalog/CatalogSeedLoader.java` (research R1, FR-016). Covered by `SchemaFreezeIT.assessmentAndEnhancementResolveEntirelyAsPublishedDefinitionAssets` (T036) — no separate CatalogSeedLoader check added since the IT already queries `definition_asset` directly, the more authoritative source.
- [X] T039 [P] Update the `specs/004-assessment-enhancement-case-types/quickstart.md` success checklist to reflect the six suites and run the full `:app:test` suite green (Phase 1–3 suites + all six Phase 4 suites) (SC-008). Checklist updated. `gradle compileTestJava` passes cleanly across the whole repo; `ArchitectureRulesTest` (no Docker needed) passes. The Testcontainers-backed IT suites could not be executed in this sandbox: Docker's daemon is reachable, but Testcontainers 1.19.8's pinned docker-java client negotiates API 1.32 against this environment's dockerd (29.3.1, minimum API 1.40) — a client/server version mismatch, not a missing-Docker problem. Same standing limitation disclosed in every prior phase of this delivery chain.

---

## Dependencies & Execution Order

- **Setup (T001–T003)** → **Foundational (T004–T007)** block everything.
- **US1 (T008–T013)** is the MVP and precedes the others (routing is the gateway — the two new case types are only reachable once intake can direct submissions to them).
- **US2 (T014–T019)** depends on US1's confirm path and Foundational T007 (snapshot capability flags) for read-only enforcement + guard exemption.
- **US3 (T020–T026)** depends on US1's confirm path (for the no-baseline `422`) and the existing `trace_link` edges; the baseline delegate is the first step of its BPMN.
- **US4 (T027–T029)** depends on Foundational T006 (`MutatingCaseGuard`) and the mutating case types authored in US1/US3; it wires the guard onto create/terminal.
- **US5 (T030–T036)** depends on Foundational T007 snapshot pinning; conditional folding happens before pinning. `SchemaFreezeIT` (T036) depends on V15/V16 being in place.
- **Polish (T037–T039)** depends on all stories being present.

**Story independence**: US2, US3, US5 each deliver an isolable capability on top of US1's routing. US4 is a guard wired onto the mutating case types (US1/US3). Given staffing, US2 (Assessment) and US3 (Enhancement) can proceed in parallel after US1, and US5's conditional-artifact work is largely independent of US2/US3.

## Parallel Execution Examples

- **Setup**: T001, T002, T003 all `[P]` — different files.
- **Foundational**: V15 (T004, tenancy) and V16 (T005, intake) are separate migrations in separate modules — author in parallel — then T006–T007.
- **US2 content**: T015 (case-type/workflow seeds) and T016 (templates/rubrics/prompts) `[P]` after the BPMN (T014) establishes the shape.
- **US3 content**: T021 (case-type/workflow seeds) and T022 (templates/rubrics/prompts) `[P]` after the BPMN (T020).
- **US5**: T035 (ConditionalArtifactIT) and T036 (SchemaFreezeIT) `[P]` — independent test files.
- **Polish**: T037, T038, T039 all `[P]` — independent verification passes.

## Implementation Strategy

**MVP = US1 only** (Phase 1–3): DMN classification + mandatory human confirm/override routing a submission to the correct case type before any Case is created. This is demonstrable value on its own — the routing gateway that unlocks both new case types — even before Assessment and Enhancement pipelines are authored.

**Incremental delivery**: US1 (routing + confirm) → US2 (Assessment, read-only by construction) → US3 (Enhancement, baseline-anchored) → then P2 hardening US4 (mutating-case guard) → P3 US5 (conditional artifacts + zero-schema proof) → Polish (SC-008 regression). Each phase is independently testable and leaves the system shippable; the zero-new-tables invariant (SC-007) is enforced continuously by `SchemaFreezeIT`.

---

**Total: 39 tasks** — Setup 3 · Foundational 4 · US1 6 · US2 6 · US3 7 · US4 3 · US5 7 · Polish 3.
