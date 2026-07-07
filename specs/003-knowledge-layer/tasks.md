# Tasks: Knowledge Layer

**Input**: Design documents from `/specs/003-knowledge-layer/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/api.yaml](contracts/api.yaml), [quickstart.md](quickstart.md)

**Tests**: Included — spec SC-001…SC-009 and the six quickstart IT suites (KnowledgeRetrievalIT, KnowledgeReplayIT, KnowledgeLeakageIT, CapturePromotionIT, DeprecationIT, InfluenceKpiIT) plus the re-run Phase 1/2 suites are the acceptance evidence.

**Organization**: Grouped by user story (US1–US4, priority order from spec.md). Builds on the Phase 1/2 modular monolith — **adds the new `knowledge` Gradle module** (the 11th bounded context) and extends `persona`, `orchestration`, `catalog`, `replay`, `observability`, `tenancy`, and `app` tests. Migrations continue the single ordered stream at **V13–V14**.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: Maps to spec.md user stories US1–US4
- All descriptions include exact file paths per the module layout in [plan.md](plan.md)

## Path Conventions (from plan.md)

Modular monolith: `<module>/src/main/java/com/d2os/<module>/…`, migrations in `<module>/src/main/resources/db/migration/`, BPMN in `orchestration/src/main/resources/processes/`, integration tests in `app/src/test/java/com/d2os/app/`. The new `knowledge` module follows the same per-BC layout; `persona` never depends on `knowledge` (retrieval crosses the boundary through the `KnowledgeProvider` SPI in `persona/spi`, research R1).

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Build/config scaffolding for Phase 2 — new module wiring and deterministic embeddings, no business logic yet.

- [X] T001 [P] Create the `knowledge` module `knowledge/build.gradle` (depends on `tenancy`, `catalog`, `persona` SPI, `casecore`; pgvector/JPA on the runtime classpath) and register it in `settings.gradle` via `include 'knowledge'`
- [X] T002 [P] Add Phase 3 config keys to `app/src/main/resources/application.yml`: `d2os.knowledge.max-items-per-operation: 5`, `d2os.knowledge.retrieval-budget-ms: 500`, `d2os.knowledge.per-item-token-ceiling`, and `d2os.ai-gateway.embed-model` (research R10, R3)
- [X] T003 [P] Extend `app/src/test/java/com/d2os/app/support/StubAiGatewayClient.java` with a deterministic hash-derived embedding (vector seeded from content bytes) so retrieval/replay/leakage ITs are reproducible with no provider (research R3). NOTE: the `AiGatewayClient.embed(...)` interface method + `EmbedRequest`/`EmbedResult` types are T007 (Foundational); implemented here as a standalone `deterministicEmbedding(...)` helper (+ `EMBEDDING_DIMENSIONS`) that T007's override will delegate to, so this Setup task stays independent of Phase 2

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Schema + shared retrieval/injection/embedding infrastructure every story depends on. MUST complete before any US phase. (Following Phase 1, all V13–V14 schema and the SPI land here; per-story logic follows.)

- [X] T004 Create the V13 knowledge schema — `knowledge_item` (LIST-partitioned by `workspace_id`, HNSW index per partition on `embedding vector`, RLS policy + `d2os_app` grants), `capture_candidate`, `prefilter_finding`, `promotion_gate_record`, `knowledge_affected_execution` (all `workspace_id` + RLS + grants) in `knowledge/src/main/resources/db/migration/V13__knowledge.sql` (data-model V13, research R2/R4–R8). NOTE: per-workspace partition + HNSW index are created by a SECURITY DEFINER `create_knowledge_item_partition(uuid)` function (T008 calls it), since least-privilege `d2os_app` cannot `CREATE PARTITION OF` an owner-owned table; `embedding vector(384)` matches `StubAiGatewayClient.EMBEDDING_DIMENSIONS`; append-only tables enforced via `REVOKE`
- [X] T005 Create the V14 persona schema — `knowledge_injection_snapshot` (FK → `operation_execution`, UNIQUE `(operation_execution_id, position)`, RLS + grants) and add `evaluation boolean NOT NULL DEFAULT false` to `operation_execution` in `persona/src/main/resources/db/migration/V14__injection_snapshot.sql` (data-model V14, research R5/R9). `knowledge_item` ref kept soft (no FK) so snapshots survive deprecation/partitioning
- [X] T006 Define the `KnowledgeProvider` SPI — `(workspace, project, operation tags, persona profile) → resolved items` — in `persona/src/main/java/com/d2os/persona/spi/KnowledgeProvider.java`, so `persona` consumes retrieval without depending on `knowledge` (research R1, keeps the dependency acyclic)
- [X] T007 Add the AI Gateway `embed(EmbedRequest) → EmbedResult` operation (text → vector + model identity/version) to `persona/src/main/java/com/d2os/persona/gateway/AiGatewayClient.java` and `HttpAiGatewayClient.java` (research R3, Principle II records the embed model). Added `EmbedRequest`/`EmbedResult` records + `embed-model`/`embed-base-url` on `AiGatewayProperties`; `StubAiGatewayClient.embed(...)` override delegates to the T003 deterministic helper
- [X] T008 Add the partition-creation hook at workspace provisioning (create the `knowledge_item` LIST partition + its HNSW index in the same transaction that creates the workspace row) in `tenancy/src/main/java/com/d2os/tenancy/WorkspaceProvisioningService.java` (research R2, T2-b). Also added `app → knowledge` dependency in `app/build.gradle` so Flyway picks up V13

**Checkpoint**: Phase 2 schema, the retrieval SPI, the embed operation, and per-workspace partitioning ready — user story phases can begin.

---

## Phase 3: User Story 1 - Scoped knowledge is injected into persona operations and snapshotted for exact replay (Priority: P1) 🎯 MVP

**Goal**: Retrieve workspace-scoped, tag/profile-matched knowledge, inject it as delimited data, and record a per-execution injection snapshot in the same transaction so replay reconstructs the context byte-for-byte.

**Independent Test**: Seed a workspace's knowledge set, run an Initiation persona operation, and confirm the retrieved items are workspace-scoped and tag/profile-matched, that an injection snapshot recording each item's exact version is attached to the OperationExecution, and that replay reproduces the same knowledge context byte-for-byte.

- [X] T009 [US1] Implement the `KnowledgeItem` immutable versioned entity + `KnowledgeScope` value (`WORKSPACE` | `PROJECT`, `GLOBAL` reserved) and repository in `knowledge/src/main/java/com/d2os/knowledge/KnowledgeItem.java` and `KnowledgeScope.java` (FR-001, data-model, research R4)
- [X] T010 [US1] Implement `KnowledgeRetrievalService` as the Open Host Service — single query with mandatory predicates (`workspace_id`, `status='PUBLISHED'`, scope ancestor-or-equal, tag∩persona-profile overlap) ordered by vector similarity, capped at `max-items-per-operation`, ≤500 ms p95 — in `knowledge/src/main/java/com/d2os/knowledge/KnowledgeRetrievalService.java` (FR-002/003, research R10)
- [X] T011 [P] [US1] Implement `EmbeddingIndexer` (embed published items once at publish / re-embed on new version via the gateway `embed` op; record `embed_model` on the version row) in `knowledge/src/main/java/com/d2os/knowledge/EmbeddingIndexer.java` (research R3, FR-004)
- [X] T012 [US1] Implement the `KnowledgeProvider` SPI wiring in `app` binding `KnowledgeRetrievalService` to `persona`'s SPI port in `app/src/main/java/com/d2os/app/D2osApplication.java` (research R1, no cycle)
- [X] T013 [US1] Add the knowledge slot to `persona/src/main/java/com/d2os/persona/ExecutionEnvelopeBuilder.java` — call the `KnowledgeProvider`, render returned items inside the T1-a untrusted-data delimiters (knowledge on the data side of the injection boundary), account injected tokens against the case budget (FR-002, research R5/R10, AD-12)
- [X] T014 [US1] Write `knowledge_injection_snapshot` rows (exact `(id, key, version)` + `content_hash` + `position`) in the **same transaction** as the `operation_execution` row in `persona/src/main/java/com/d2os/persona/OperationExecutionRecorder.java` (FR-006, research R5, Principle II/III)
- [X] T015 [US1] Add the gateway workspace-scope assertion — reject + audit any injection whose item workspace ≠ caller workspace before prompt assembly — in `persona/src/main/java/com/d2os/persona/gateway/HttpAiGatewayClient.java` (FR-005, T2-c, defense in depth over R2)
- [X] T016 [US1] Extend the replay harness to reconstruct the injected knowledge slot from snapshots (order by `position`, verify `content_hash` against the snapshotted item version, not current state) in `replay/src/main/java/com/d2os/replay/ReplayHarness.java` and `SnapshotCompletenessCheck.java` (FR-007, research R5)
- [X] T017 [P] [US1] Add `KnowledgeRetrievalIT` in `app/src/test/java/com/d2os/app/KnowledgeRetrievalIT.java`: only PUBLISHED, scope-ancestor, tag∩profile items injected (≤ cap); snapshot rows exist with exact `(key, version)`+hash+position in the same tx (crash-injection subtest) (SC-001, SC-002)
- [X] T018 [P] [US1] Add `KnowledgeReplayIT` in `app/src/test/java/com/d2os/app/KnowledgeReplayIT.java`: after publishing a new version of one injected item and deprecating another, replay reconstructs the snapshotted context and reproduces the output byte-for-byte (SC-003)

**Checkpoint**: US1 independently testable — scoped knowledge injected, snapshotted, and byte-identically replayable.

---

## Phase 4: User Story 2 - Post-case lessons are captured, curated, and promoted through a default-deny gate (Priority: P1)

**Goal**: A case-end capture → deterministic pre-filter → Curator redaction (rubric-gated, versioned) → workspace-owner D4 pipeline that publishes a KnowledgeItem only after all three gates pass in order, default-deny.

**Independent Test**: Complete a demo Case, confirm capture produces project-confidential non-promotable candidates, drive one through pre-filter → Curator redaction → D4 into a published KnowledgeItem with sensitive fields excluded and the redaction recorded as a new version, and confirm a candidate that fails any gate is never published.

- [X] T019 [US2] Author `orchestration/src/main/resources/processes/knowledge-capture.bpmn20.xml` — standalone process: capture service task → pre-filter service task → Curator persona service task (rubric-validated) → D4 user task → publish/reject service task; wait/escalation reuse the Phase 2 `EscalationBridge` pattern (research R6)
- [X] T020 [US2] Implement `CaseDeliveredKnowledgeTrigger` — outbox consumer that starts `knowledge-capture` on Case `Delivered`, correlated by `caseInstanceId` — in `orchestration/src/main/java/com/d2os/orchestration/CaseDeliveredKnowledgeTrigger.java` (FR-008, research R6)
- [X] T021 [US2] Implement `CaptureCandidate` entity/repository + `CaptureService` (harvest candidates from case artifacts/decisions, born PROJECT-scoped/confidential/non-promotable, revision chain via `revision_of`) in `knowledge/src/main/java/com/d2os/knowledge/capture/` (FR-008, data-model state machine)
- [X] T022 [P] [US2] Implement `SensitivityPreFilter` (deterministic pattern detectors: email/phone/id-number/credential + intake sensitivity-tag propagation → `prefilter_finding` rows; sensitive-tagged fields excluded from content by default) and `PreFilterDelegate` in `knowledge/src/main/java/com/d2os/knowledge/capture/SensitivityPreFilter.java` and `orchestration/src/main/java/com/d2os/orchestration/PreFilterDelegate.java` (FR-010, research R7, T3-a/T3-c)
- [X] T023 [US2] Implement `RedactionService` (save Curator redaction as a NEW candidate revision, preserving the prior revision; PREFILTERED → REDACTED) in `knowledge/src/main/java/com/d2os/knowledge/capture/RedactionService.java` and the `CuratorStepDelegate` that runs the Curator persona op through the `PersonaStepDelegate` path in `orchestration/src/main/java/com/d2os/orchestration/CuratorStepDelegate.java` (FR-011/012, research R6)
- [X] T024 [US2] Implement `PromotionGateService` — enforce gate order (PREFILTER → CURATION → D4), at-most-one PASS per gate, non-self-satisfiable D4 (approver ≠ redaction actor, workspace-owner role), publish the KnowledgeItem version with `source_candidate_id` provenance on APPROVE, record `promotion_gate_record` + Decision + AuditEntry in the same tx — in `knowledge/src/main/java/com/d2os/knowledge/capture/PromotionGateService.java` (FR-009/013/019, Q5, Principle V)
- [X] T025 [P] [US2] Author the Knowledge Curator persona + curation Playbook + curation Rubric + redaction Prompt seeds and the initial KnowledgeItem seed set (provenance discipline) in `catalog/src/main/java/com/d2os/catalog/CatalogSeedLoader.java` (FR-021, Principle I)
- [X] T026 [US2] Add the candidate/redaction/D4 endpoints — `GET /knowledge/candidates`, `GET /knowledge/candidates/{id}`, `POST …/{id}/redaction` (201/409), `POST …/{id}/d4` (200/403/409) — in `knowledge/src/main/java/com/d2os/knowledge/api/CandidateController.java` (contracts api.yaml, FR-009/011/013)
- [X] T027 [US2] Add `CapturePromotionIT` in `app/src/test/java/com/d2os/app/CapturePromotionIT.java`: Delivered → candidates CAPTURED/confidential; pre-filter findings + default exclusion; redaction new revision; D4 self-approval → 403, owner APPROVE → PUBLISHED with provenance, each-stage REJECT stays non-promotable with reason, gate-order violation → 409, zero partial promotion (SC-005, SC-006)

**Checkpoint**: US2 independently testable — default-deny promotion pipeline publishes only through all three gates.

---

## Phase 5: User Story 3 - Deprecating a KnowledgeItem flags its past executions without rewriting history (Priority: P2)

**Goal**: Deprecation removes an item from new retrievals and flags — never mutates — the past executions whose snapshots referenced it.

**Independent Test**: Run an operation that injects an item, deprecate it, and confirm the past execution is flagged and surfaced for review, the item is no longer retrieved into new operations, and replaying the flagged execution still reproduces its original output byte-for-byte.

- [X] T028 [US3] Implement `DeprecationService.deprecate(itemKey, version-range, reason)` — in one transaction: set item `DEPRECATED` (+ Decision + AuditEntry) and insert-select one `knowledge_affected_execution` row per distinct `operation_execution` whose snapshot references a deprecated version; never touch snapshots/outputs — in `knowledge/src/main/java/com/d2os/knowledge/DeprecationService.java` (FR-014/015/016, research R8)
- [X] T029 [US3] Add the `status='PUBLISHED'` exclusion so `DEPRECATED` items drop out of new-operation retrieval while in-flight snapshotted envelopes are unaffected, in `knowledge/src/main/java/com/d2os/knowledge/KnowledgeRetrievalService.java` (FR-014, research R8/R10)
- [X] T030 [P] [US3] Add `POST /knowledge/items/{id}/deprecate` (200 returns flagged-execution count, 409 already deprecated), `GET /knowledge/affected-executions`, `POST /knowledge/affected-executions/{flagId}/review`, and item listing/inspection (`GET /knowledge/items`, `GET /knowledge/items/{id}`) in `knowledge/src/main/java/com/d2os/knowledge/api/KnowledgeController.java` (contracts api.yaml, FR-014/015)
- [X] T031 [P] [US3] Add `DeprecationIT` in `app/src/test/java/com/d2os/app/DeprecationIT.java`: deprecated item excluded from new retrieval; `knowledge_affected_execution` flags exactly the referencing executions (count matches endpoint); snapshots/outputs byte-unchanged; replay of a flagged execution reproduces its original output (SC-007)

**Checkpoint**: US3 independently testable — deprecation flags without rewriting history.

---

## Phase 6: User Story 4 - A knowledge-influence KPI shows each item's rubric-score contribution (Priority: P3)

**Goal**: An on-demand paired with/without evaluation emits the rubric-score delta per item through the existing `kpi_sample` stream, reporting not-yet-measurable for never-injected items.

**Independent Test**: For a chosen KnowledgeItem, run an evaluation operation with and without the item injected against the same rubric and confirm the system emits a knowledge-influence value equal to the rubric-score delta, attributable to that item.

- [X] T032 [US4] Implement `InfluenceEvaluationService` — paired runs through the persona execution path (item force-included then force-excluded, same rubric version), both flagged `evaluation=true` so they never feed delivery, emitting the with-minus-without delta — in `knowledge/src/main/java/com/d2os/knowledge/influence/InfluenceEvaluationService.java` (FR-017, research R9)
- [X] T033 [P] [US4] Emit `kpi_sample(metric='knowledge_influence', value=delta)` tagged with item key/version (reuse V9, no schema change) in `observability/src/main/java/com/d2os/observability/KpiEmitter.java` (FR-018, research R9)
- [X] T034 [US4] Add `POST /knowledge/items/{id}/influence-evaluations` (202) and `GET /metrics/knowledge-influence` (MEASURED delta samples, or NOT_YET_MEASURABLE for a never-injected item — never fabricated) in `knowledge/src/main/java/com/d2os/knowledge/influence/InfluenceController.java` (contracts api.yaml, FR-018)
- [X] T035 [P] [US4] Add `InfluenceKpiIT` in `app/src/test/java/com/d2os/app/InfluenceKpiIT.java`: two evaluation-flagged executions under one rubric version, `kpi_sample` delta emitted, `GET /metrics/knowledge-influence` returns MEASURED with the delta; never-injected item → NOT_YET_MEASURABLE, no sample (SC-008)

**Checkpoint**: US4 independently testable — influence delta measured and emitted, not fabricated.

---

## Phase 7: Polish & Cross-Cutting Concerns (SC-009 — Phase 1/2 guarantees under the knowledge layer)

**Purpose**: Prove nothing regressed and enforce the new boundaries.

- [X] T036 [P] Add `KnowledgeLeakageIT` in `app/src/test/java/com/d2os/app/KnowledgeLeakageIT.java`: retrieval in workspace A returns zero of B's items regardless of tag/similarity match (partition pruning, T2-b) and a B-item-under-A's-scope envelope is refused + audited by the gateway assertion (T2-c) — both layers independently block (SC-004)
- [ ] T037 [P] Re-run the Phase 1/2 suites unchanged in `app/src/test/java/com/d2os/app/` — `SubmitToDeliverIT`, `ParallelExecutionIT`, `LeakageSuiteIT`, `InjectionSeedIT`, `TokenBudgetSuiteIT` (now counting injected-knowledge tokens against the case budget), `AuditGrantSuiteIT`, and the replay suite — asserting all pass with knowledge injection active (SC-009)
- [X] T038 [P] Seed and verify the initial KnowledgeItem set (workspace- and project-scoped, matching/non-matching tags, plus a DEPRECATED item for the retrieval fixtures) via `catalog/src/main/java/com/d2os/catalog/CatalogSeedLoader.java` (FR-021, quickstart scenario 1)
- [X] T039 [P] Add an ArchUnit rule that the `persona` module has no dependency on the `knowledge` module (retrieval flows only through the `KnowledgeProvider` SPI — enforces the acyclic direction) in `test-support/src/main/java/com/d2os/testsupport/ArchitectureRules.java` (research R1)
- [ ] T040 [P] Update the `quickstart.md` success checklist and run the full `:app:test` suite green (Phase 1 + Phase 2 + all six Phase 3 suites)

---

## Dependencies & Execution Order

- **Setup (T001–T003)** → **Foundational (T004–T008)** block everything.
- **US1 (T009–T018)** is the MVP and precedes the others (establishes retrieval, injection, snapshot, and replay the rest reference).
- **US2 (T019–T027)** depends on US1's `KnowledgeItem`/publish path (T009) and the Curator persona execution path; the capture process is standalone and does not touch the initiation workflow (research R6).
- **US3 (T028–T031)** depends on US1's snapshot table (T014) and retrieval predicate (T010) — it filters on status and reads snapshots to flag executions.
- **US4 (T032–T035)** depends on US1's injection/execution path and Foundational `evaluation` column (T005); the paired evaluation reuses the persona execution path.
- **Polish (T036–T040)** depends on all stories being present.

**Story independence**: US2, US3, US4 each deliver an isolable capability testable on top of US1. US3 (deprecation) and US4 (influence KPI) can proceed in parallel once US1's snapshot/retrieval are in place; US2 (capture/promotion) is largely independent of US3/US4 after US1.

## Parallel Execution Examples

- **Setup**: T001, T002, T003 all `[P]` — different files (build/config/test-support).
- **Foundational schema**: T004 (V13, knowledge) and T005 (V14, persona) are separate migrations in separate modules — author in parallel — then T006–T008.
- **US1**: T011 (indexer), T017 and T018 (IT suites) are `[P]` — distinct files — around the T013/T014/T015 injection path.
- **US2**: T022 (pre-filter) and T025 (seeds) are `[P]` — different files — alongside the T021/T023/T024 pipeline.
- **US3**: T030 (endpoints) and T031 (IT) `[P]` after T028/T029.
- **US4**: T033 (KPI emit) and T035 (IT) `[P]` around T032/T034.
- **Polish**: T036–T040 all `[P]` — independent test/seed/config files.

## Implementation Strategy

**MVP = US1 only** (Phase 1–3): scoped, workspace-isolated retrieval injected as delimited data with a same-transaction injection snapshot that replays byte-for-byte. This is the phase's headline value — reproducible, auditable injected knowledge — demonstrable before capture/governance (US2), deprecation (US3), or the influence KPI (US4) are built.

**Incremental delivery**: US1 (scoped injection + snapshot + replay) → US2 (default-deny capture→curation→publish) → then P2 US3 (deprecation flagging) → P3 US4 (influence KPI) → Polish (SC-004 leakage + SC-009 Phase 1/2 regression). Each phase is independently testable and leaves the system shippable.

---

**Total: 40 tasks** — Setup 3 · Foundational 5 · US1 10 · US2 9 · US3 4 · US4 4 · Polish 5.
