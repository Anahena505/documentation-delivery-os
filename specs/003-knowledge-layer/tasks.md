---
description: "Detailed, zero-ambiguity task list for Knowledge Layer (Phase 3)"
---

# Tasks: Knowledge Layer

**Input**: Design documents from `/specs/003-knowledge-layer/`
**Prerequisites**: plan.md, spec.md, research.md (R1–R10), data-model.md, contracts/api.yaml, quickstart.md

## How to read this file

Every task is written so **any level can execute it with no design thinking required**: the exact
file path, exact table columns / enum values / method behavior, and the exact assertion to prove it
are all stated inline. Sub-bullets under a task are ordered steps — do them top to bottom.

> **⚠️ Repository status (verified 2026-07-07): Phase 3 is already implemented in the tree.**
> Every target file below already exists. Each task therefore has a `↳ File:` pointer to the real
> artifact and doubles as a **verify/complete checklist**: open the file, confirm every sub-step is
> satisfied, and check the box. If a sub-step is missing, implement exactly what is written. Nothing
> here requires inventing behavior — the design is fully resolved in the linked docs.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: different file, no dependency on an incomplete task → parallelizable.
- **[Story]**: US1 (retrieval+snapshot+replay, P1), US2 (capture→publish, P1), US3 (deprecation, P2),
  US4 (influence KPI, P3). Setup/Foundational/Polish carry no story label.

## Tech facts (from plan.md — do not re-derive)

- Java 21, Spring Boot 3.3.5, Flowable 7.0.1, Spring Data JPA, Flyway, PostgreSQL 16 + pgvector.
- Modular monolith; module = bounded context. Java at `<module>/src/main/java/com/d2os/<module>/`,
  migrations at `<module>/src/main/resources/db/migration/`, ITs at `app/src/test/java/com/d2os/app/`.
- Embedding vector dimensionality is **384** (`StubAiGatewayClient.EMBEDDING_DIMENSIONS`); the
  `knowledge_item.embedding` column is `VECTOR(384)` — the two MUST agree.
- Tests use `StubAiGatewayClient.LatencyControllableGateway` (deterministic outputs + hash-derived
  embeddings, model id `stub-embed-1.0`) — no live provider.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Register the `knowledge` module and its build wiring so all later code compiles.

- [ ] T001 Register the `knowledge` module in `settings.gradle`.
  - ↳ File: `settings.gradle` — confirm the line `include 'knowledge'` is present (after `include 'replay'`).
  - Confirm the module directory tree exists: `knowledge/src/main/java/com/d2os/knowledge/{capture,influence,api}/` and `knowledge/src/main/resources/db/migration/`.
- [ ] T002 Configure `knowledge/build.gradle` with the correct acyclic dependencies.
  - ↳ File: `knowledge/build.gradle`.
  - Confirm `implementation project(':tenancy')`, `project(':catalog')`, `project(':casecore')`, `project(':persona')` (SPI type only), `project(':observability')` (KPI emit), plus `spring-boot-starter-data-jpa` and `spring-boot-starter-web`.
  - Confirm **no** `project(':knowledge')` dependency appears in `persona/build.gradle` (acyclic — R1).
- [ ] T003 [P] Add knowledge config keys under `d2os.knowledge` in `app/src/main/resources/application.yml`.
  - Keys: `max-items-per-operation` (default `5`, R10), a per-item token ceiling, and the embed-model id.
  - Confirm `KnowledgeRetrievalService` reads `max-items-per-operation` for its cap.
- [ ] T004 [P] Ensure the `knowledge` module is component-scanned by the app.
  - ↳ File: `app/src/main/java/com/d2os/app/D2osApplication.java` — confirm `com.d2os.knowledge` is scanned/imported the same way other BC modules are, and the `KnowledgeProvider` SPI bean is wired (see T020).

**Checkpoint**: `./gradlew :knowledge:compileJava :app:compileJava` succeeds.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Schema (V13/V14), per-workspace partitioning, base entities, the `KnowledgeProvider` SPI,
the embeddings gateway op, and the gateway workspace-scope guard — everything ≥2 stories depend on.

**⚠️ CRITICAL**: No user story phase may start until this phase is complete.

### Database schema

- [ ] T005 Create/verify the knowledge core migration.
  - ↳ File: `knowledge/src/main/resources/db/migration/V13__knowledge.sql`.
  - Confirm `knowledge_item` is `PARTITION BY LIST (workspace_id)` with columns exactly: `id UUID`, `workspace_id UUID NOT NULL REFERENCES workspace(id)`, `key TEXT`, `version INT`, `scope_level TEXT CHECK IN ('WORKSPACE','PROJECT','GLOBAL')`, `scope_ref UUID`, `tags TEXT[]`, `locale TEXT DEFAULT 'en'`, `title TEXT`, `content TEXT`, `content_hash TEXT`, `embedding VECTOR(384) NOT NULL`, `embed_model TEXT`, `status TEXT DEFAULT 'PUBLISHED' CHECK IN ('PUBLISHED','DEPRECATED')`, `source_candidate_id UUID REFERENCES capture_candidate(id)`, `supersedes_version INT`, `deprecation_reason TEXT`, `deprecated_at TIMESTAMPTZ`, `created_at TIMESTAMPTZ`.
  - Confirm `PRIMARY KEY (workspace_id, id)` and `UNIQUE (workspace_id, key, version)`.
  - Confirm `capture_candidate` is created **before** `knowledge_item` (FK order).
- [ ] T006 Verify the remaining V13 tables + RLS + append-only REVOKEs in the same file.
  - Tables: `capture_candidate` (status CHECK `CAPTURED|PREFILTERED|REDACTED|D4_PENDING|PUBLISHED|REJECTED`, `revision`, `revision_of` self-FK, `curator_operation_execution_id` FK→`operation_execution`, `rejection_stage` CHECK `PREFILTER|CURATION|D4`, `rejection_reason`), `prefilter_finding` (category CHECK `EMAIL|PHONE|ID_NUMBER|CREDENTIAL|TAGGED_SENSITIVE`, `span_start/end`, `source`), `promotion_gate_record` (gate/outcome CHECKs, `decision_id` FK→`decision`, `actor`), `knowledge_affected_execution` (`knowledge_item_key/version`, `operation_execution_id`, `case_instance_id`, `review_status OPEN|REVIEWED`).
  - Confirm the partial unique index `uq_promotion_gate_pass ON promotion_gate_record (candidate_id, gate) WHERE outcome='PASS'` (at most one PASS per gate).
  - Confirm each table has `ENABLE ROW LEVEL SECURITY` + a `ws_isolation_*` policy on `current_setting('app.workspace_id', true)::uuid`, and append-only REVOKEs (knowledge_item: `REVOKE DELETE`; prefilter_finding & promotion_gate_record: `REVOKE UPDATE, DELETE`).
- [ ] T007 Verify the per-workspace partition-creation function (R2/T2-b).
  - ↳ Same file. Confirm `create_knowledge_item_partition(ws_id UUID) RETURNS BOOLEAN LANGUAGE plpgsql SECURITY DEFINER`: idempotent (returns false if the partition already exists), creates `knowledge_item_<wsnodash>` `PARTITION OF knowledge_item FOR VALUES IN (ws_id)`, creates a per-partition HNSW index `USING hnsw (embedding vector_cosine_ops)`, and `REVOKE DELETE` on the new partition.
  - Confirm `GRANT EXECUTE ... TO d2os_app` and the seed call for the system-global workspace `00000000-0000-0000-0000-000000000000`.
- [ ] T008 Wire partition creation into workspace provisioning (same transaction as the workspace row).
  - Confirm the tenancy workspace-creation path calls `SELECT create_knowledge_item_partition(:wsId)` when a workspace is created, so every workspace has its `knowledge_item` partition + HNSW index before any item is published.
- [ ] T009 Create/verify the injection-snapshot migration.
  - ↳ File: `persona/src/main/resources/db/migration/V14__injection_snapshot.sql`.
  - Confirm `knowledge_injection_snapshot` columns: `id`, `workspace_id` FK, `operation_execution_id` FK→`operation_execution`, `knowledge_item_id` (soft ref, **no FK**), `knowledge_item_key TEXT`, `knowledge_item_version INT`, `content_hash TEXT`, `position INT`, `created_at`; `UNIQUE (operation_execution_id, position)`.
  - Confirm indexes `idx_injection_snapshot_op (operation_execution_id)` and `idx_injection_snapshot_item (knowledge_item_key, knowledge_item_version)` (reverse lookup for deprecation), RLS policy, and `REVOKE UPDATE, DELETE`.
  - Confirm `ALTER TABLE operation_execution ADD COLUMN evaluation BOOLEAN NOT NULL DEFAULT false` (R9).

### Base entities, SPI, embeddings gateway

- [ ] T010 [P] Verify the `KnowledgeItem` entity + repository.
  - ↳ Files: `knowledge/src/main/java/com/d2os/knowledge/KnowledgeItem.java`, `KnowledgeItemRepository.java`.
  - Confirm the entity maps every V13 `knowledge_item` column, and the repository exposes finders by workspace+status+scope+tags (retrieval) and by key+version (deprecation/publish).
- [ ] T011 [P] Verify the `KnowledgeScope` scope-lattice value type.
  - ↳ File: `knowledge/src/main/java/com/d2os/knowledge/KnowledgeScope.java`.
  - Confirm the `scope_level` values `WORKSPACE|PROJECT` (`GLOBAL` reserved/unreachable v1) and an ancestor-or-equal predicate: a WORKSPACE item is visible to all projects in the workspace; a PROJECT item only inside its project (R4/R10).
- [ ] T012 Verify the `KnowledgeProvider` SPI in persona (the acyclic seam).
  - ↳ File: `persona/src/main/java/com/d2os/persona/spi/KnowledgeProvider.java`.
  - Confirm it declares retrieval as `(workspaceId, projectId, tags, personaProfile) → injected items` carrying `(itemId, key, version, content, contentHash)`, and that **persona imports no `com.d2os.knowledge` type** (R1/R5).
- [ ] T013 Verify the AI Gateway embeddings operation.
  - ↳ Files: `persona/src/main/java/com/d2os/persona/gateway/AiGatewayClient.java` (`EmbedResult embed(EmbedRequest)`), `EmbedRequest.java`, `EmbedResult.java`, `HttpAiGatewayClient.java`.
  - Confirm `EmbedResult` carries the vector + model identity/version (Principle II), and `HttpAiGatewayClient.embed(...)` calls the provider embeddings endpoint.
- [ ] T014 [P] Verify the deterministic stub embedding (provider-free ITs).
  - ↳ File: `app/src/test/java/com/d2os/app/support/StubAiGatewayClient.java`.
  - Confirm `embed(...)` returns `new EmbedResult(deterministicEmbedding(text), "stub-provider", "stub-embed-1.0")`, `EMBEDDING_DIMENSIONS == 384`, vectors are L2-normalized and RNG-free (reproducible across replay). Confirm `InjectionEchoAiGatewayClient` stays consistent if it also implements the gateway.
- [ ] T015 Verify the gateway workspace-scope assertion seam (T2-c/R5, second isolation layer).
  - ↳ Files: `persona/src/main/java/com/d2os/persona/gateway/WorkspaceScopeGuard.java`, `KnowledgeScopeViolationException.java`.
  - Confirm that before prompt assembly the gateway asserts every injected item's `workspace_id` equals the caller's; a mismatch throws `KnowledgeScopeViolationException`, refuses the call, and writes a `SCOPE_VIOLATION_BLOCKED` AuditEntry (asserted by `KnowledgeLeakageIT`).

**Checkpoint**: Migrations apply on a fresh Testcontainers Postgres; partitions created on provisioning;
`:app:compileJava` green.

---

## Phase 3: User Story 1 — Scoped injection + per-execution snapshot + exact replay (Priority: P1) 🎯 MVP

**Goal**: Before a persona op's prompt is built, retrieve workspace-scoped/tag∩profile items, inject
them as delimited data, and write an injection snapshot in the **same transaction** as the
OperationExecution — so replay reconstructs the knowledge context byte-for-byte (FR-001…FR-007, FR-020).

**Independent Test**: Seed a workspace's knowledge set, run an Initiation persona op, confirm only
in-scope/tag∩profile items inject, a snapshot with exact `(key,version)`+`content_hash`+`position` is
attached same-tx, and replay reproduces the output byte-for-byte after the items change.

### Tests for User Story 1 ⚠️ (must FAIL before implementation; here: confirm they PASS)

- [ ] T016 [P] [US1] Verify `KnowledgeRetrievalIT` (SC-001/002).
  - ↳ File: `app/src/test/java/com/d2os/app/KnowledgeRetrievalIT.java`.
  - Asserts: seed workspace A (workspace-scoped, project-scoped matching + non-matching tag/profile, one DEPRECATED); run an Initiation persona op via stub gateway; knowledge slot contains only PUBLISHED, scope-ancestor, tag∩profile items (≤ cap); `knowledge_injection_snapshot` rows exist for exactly those with exact `(key,version)`+`content_hash`+`position`; crash-injection subtest → no execution without its snapshots (same-tx).
- [ ] T017 [P] [US1] Verify `KnowledgeReplayIT` (SC-003).
  - ↳ File: `app/src/test/java/com/d2os/app/KnowledgeReplayIT.java`.
  - Asserts: execute a knowledge-injected op, record output hash; publish a new version of one injected item and deprecate another; replay reconstructs the slot from snapshots (verifying `content_hash`) and output is byte-identical.
- [ ] T018 [P] [US1] Verify `KnowledgeLeakageIT` (SC-004, both layers).
  - ↳ File: `app/src/test/java/com/d2os/app/KnowledgeLeakageIT.java`.
  - Asserts: retrieval in A returns zero B items (partition pruning, T2-b); bypass subtest referencing a B item under A's scope → gateway refuses + `SCOPE_VIOLATION_BLOCKED` AuditEntry (T2-c).

### Implementation for User Story 1

- [ ] T019 [US1] Verify `KnowledgeRetrievalService` (the Open Host Service, R10).
  - ↳ File: `knowledge/src/main/java/com/d2os/knowledge/KnowledgeRetrievalService.java`.
  - Confirm one SQL query with mandatory predicates `workspace_id=:ws`, `status='PUBLISHED'`, scope ancestor-or-equal (via `KnowledgeScope`), tag overlap with (operation tags ∩ persona profile), ordered by vector cosine similarity to the operation-context embedding (one gateway `embed` call), capped at `d2os.knowledge.max-items-per-operation`. Deterministic filters bound entitlement; similarity only ranks within the entitled set.
- [ ] T020 [US1] Verify `EmbeddingIndexer` and SPI wiring.
  - ↳ Files: `knowledge/src/main/java/com/d2os/knowledge/EmbeddingIndexer.java`; the `KnowledgeProvider` impl bean.
  - Confirm items are embedded via the gateway `embed` op at publish/new-version time and `embed_model` is recorded on the row (R3); confirm the knowledge module implements `KnowledgeProvider` delegating to `KnowledgeRetrievalService`, wired as the persona SPI bean in `app` (keeps the dependency acyclic).
- [ ] T021 [US1] Verify the envelope knowledge slot.
  - ↳ File: `persona/src/main/java/com/d2os/persona/ExecutionEnvelopeBuilder.java`.
  - Confirm it calls `KnowledgeProvider.retrieve(...)`, renders returned items into the envelope as **delimited data** (same T1-a framing as submission text), and passes the resolved `(itemId, key, version, content, contentHash)` list with stable `position` order to the recorder.
- [ ] T022 [US1] Verify same-transaction snapshot write.
  - ↳ Files: `persona/src/main/java/com/d2os/persona/OperationExecutionRecorder.java`, `KnowledgeInjectionSnapshot.java`, `KnowledgeInjectionSnapshotRepository.java`.
  - Confirm one `knowledge_injection_snapshot` row per injected item (with `position`, `content_hash`) is written in the **same transaction** as the `operation_execution` row (FR-006, R5) — never a second transaction.
- [ ] T023 [US1] Verify injected-knowledge tokens count against the case budget.
  - ↳ File: `persona/src/main/java/com/d2os/persona/TokenBudgetGuard.java`.
  - Confirm injected knowledge text is counted toward the per-case token budget before envelope insertion (R10, NFR-7), keeping `TokenBudgetSuiteIT` valid.
- [ ] T024 [US1] Verify the replay harness reconstructs knowledge from snapshots.
  - ↳ Module: `replay/`.
  - Confirm replay orders `knowledge_injection_snapshot` by `position`, loads the exact `(key,version)`, verifies `content_hash`, and rebuilds the slot from the snapshot (not current items) — FR-007.
- [ ] T025 [US1] Verify the KnowledgeItem seed loader.
  - ↳ File: `knowledge/src/main/java/com/d2os/knowledge/KnowledgeSeedLoader.java`.
  - Confirm it seeds, with provenance, the fixture set: workspace-scoped, project-scoped (matching + non-matching tags/profile), and a DEPRECATED item (FR-021, quickstart checklist), invoking `EmbeddingIndexer` so seeds are searchable.
- [ ] T026 [US1] Verify the operator read surface for items.
  - ↳ File: `knowledge/src/main/java/com/d2os/knowledge/api/KnowledgeController.java`.
  - Confirm `GET /api/v1/knowledge/items` (filters `status`, `scopeLevel`, `tag`, `key`) and `GET /knowledge/items/{itemId}` (with `sourceCandidateId`/`supersedesVersion` provenance) per contracts/api.yaml; responses are workspace-scoped only.

**Checkpoint**: `KnowledgeRetrievalIT`, `KnowledgeReplayIT`, `KnowledgeLeakageIT` green — MVP demonstrable.

---

## Phase 4: User Story 2 — Capture → pre-filter → curator redaction → D4 → publish (Priority: P1)

**Goal**: On Case `Delivered`, capture project-confidential candidates; publish only via the fixed
default-deny pipeline (pre-filter → Curator redaction → workspace-owner D4), each gate audited, no
bypass path (FR-008…FR-013, FR-019, FR-021, SC-005/006).

**Independent Test**: Complete a demo Case; candidates appear `CAPTURED`/confidential/non-promotable;
drive one through pre-filter → redaction (new revision) → D4 APPROVE to a published KnowledgeItem with
provenance; any gate rejection publishes nothing.

### Tests for User Story 2 ⚠️

- [ ] T027 [P] [US2] Verify `CapturePromotionIT` (SC-005/006).
  - ↳ File: `app/src/test/java/com/d2os/app/CapturePromotionIT.java`.
  - Asserts: case → `Delivered` → candidates `CAPTURED`/confidential; pre-filter produces `prefilter_finding` rows for seeded email/phone/tagged-sensitive and excludes sensitive fields → `PREFILTERED`; Curator redaction saved as new revision → `REDACTED`, prior revision intact; D4: approver==redaction actor → **403**, gate-order violation → **409**, workspace-owner APPROVE → `PUBLISHED` KnowledgeItem with `source_candidate_id` + Decision + AuditEntry; sibling REJECT at each stage → non-promotable with stage+reason; zero partial promotion.

### Implementation for User Story 2

- [ ] T028 [P] [US2] Verify the `CaptureCandidate` entity + state machine.
  - ↳ Files: `knowledge/.../capture/CaptureCandidate.java` (+ `CaptureCandidateRepository.java`).
  - Confirm nested enums `Status{CAPTURED,PREFILTERED,REDACTED,D4_PENDING,PUBLISHED,REJECTED}` and `RejectionStage{PREFILTER,CURATION,D4}`; `revision`/`revision_of` chaining; only forward transitions exist. Confirm `IllegalCandidateTransitionException` guards illegal jumps.
- [ ] T029 [P] [US2] Verify `PrefilterFinding` and `PromotionGateRecord` entities + repositories.
  - ↳ Files: `knowledge/.../capture/PrefilterFinding.java` (enum `Category{EMAIL,PHONE,ID_NUMBER,CREDENTIAL,TAGGED_SENSITIVE}`), `PromotionGateRecord.java` (enums `Gate{PREFILTER,CURATION,D4}`, `Outcome{PASS,REJECT}`), + their repositories.
  - Confirm the "≤ one PASS per (candidate, gate)" invariant is backed by the DB partial unique index (T006).
- [ ] T030 [US2] Verify `CaptureService` (harvest, FR-008/R4).
  - ↳ File: `knowledge/.../capture/CaptureService.java`.
  - Confirm it harvests candidate lessons from a completed case's artifacts/decisions into revision-1 `CAPTURED` candidates that are PROJECT-scoped, project-confidential, non-promotable, with an AuditEntry in the same tx.
- [ ] T031 [US2] Verify `SensitivityPreFilter` (deterministic, R7/T3).
  - ↳ File: `knowledge/.../capture/SensitivityPreFilter.java` (nested `Detector`, `Span`).
  - Confirm deterministic detectors (email, phone, national-id/IBAN, credential shapes) + propagation of intake field-level sensitivity tags (`INTAKE_TAG:<field>`, T3-a); writes `prefilter_finding` rows; sensitive-tagged fields excluded by default from candidate content (T3-c, FR-010); transitions `CAPTURED→PREFILTERED`. No LLM call.
- [ ] T032 [US2] Verify `RedactionService` (new revision, FR-011/012).
  - ↳ File: `knowledge/.../capture/RedactionService.java`.
  - Confirm the human-confirmed redaction is saved as a **new** candidate revision (revision_of chain, prior revision preserved), rubric-validated; transitions `PREFILTERED→REDACTED`; only the latest revision proceeds to D4.
- [ ] T033 [US2] Verify `PromotionGateService` (default-deny order + publish/reject).
  - ↳ File: `knowledge/.../capture/PromotionGateService.java` (+ `GateOrderViolationException`, `D4AuthorizationException`).
  - Confirm: gate-order violations throw `GateOrderViolationException` → **409**; each gate writes a `PromotionGateRecord`; D4 APPROVE publishes a `KnowledgeItem` version (scope raised to approved level, `source_candidate_id` provenance) via `EmbeddingIndexer`, writing Decision + AuditEntry same-tx; REJECT leaves candidate confidential/non-promotable with `rejection_stage`+`rejection_reason`; D4 actor must hold workspace-owner role and differ from the redaction actor, else `D4AuthorizationException` → **403** (FR-009/013/019, non-self-satisfiable).
- [ ] T034 [US2] Verify the capture BPMN process (R6, standalone).
  - ↳ File: `orchestration/src/main/resources/processes/knowledge-capture.bpmn20.xml`.
  - Confirm flow: capture service task → pre-filter service task → Curator persona service task (redaction draft) → **D4 user task** (workspace owner) → publish/reject service task. Initiation workflow (`initiation-v2.bpmn20.xml`) is untouched.
- [X] T035 [US2] Verify the case-end trigger.
  - ↳ File: `orchestration/src/main/java/com/d2os/orchestration/CaseDeliveredKnowledgeTrigger.java`.
  - Confirm it starts `knowledge-capture` correlated by `caseInstanceId` when a case reaches `Delivered` (mechanism per the deviation below).
  - > **Deviation (accepted):** implemented as a `@Scheduled` sweep over Flowable's non-RLS history for finished `initiation-v2` instances, **not** a classic outbox relay — `event_outbox` is RLS-scoped + append-only (`REVOKE UPDATE/DELETE`), so a "flip published_at" relay is structurally impossible. Idempotent by case business key; mirrors the existing `ReconciliationJob`. Same delivery signal, different mechanism.
- [ ] T036 [P] [US2] Verify the BPMN service-task delegates.
  - ↳ Files: `orchestration/.../CaptureStepDelegate.java`, `PreFilterDelegate.java`, `CuratorStepDelegate.java`, `CaptureWaitReleaserImpl.java` (+ `knowledge/.../capture/CaptureWaitReleaser.java`).
  - Confirm `CuratorStepDelegate` produces the redaction via `RedactionService` (v1: deterministically — see the deviation below), `PreFilterDelegate` invokes `SensitivityPreFilter`, and the wait-releaser resumes the process on D4 completion.
  - > **Deviation (accepted):** the Curator step does **not** run through `PersonaExecutionService`. That path resolves definitions from the case's *frozen* `CaseDefinitionSnapshot`, which pins only the initiation suite — so `knowledge-curator` is not resolvable there and the snapshot is immutable (Principle I). v1 produces the redaction deterministically (the pre-filter already excluded PII); the Curator persona/playbook/rubric/prompt are still seeded (T037) for provenance and future snapshot inclusion.
- [ ] T037 [US2] Verify the Curator governance assets are seeded (FR-021, Principle I).
  - ↳ File: `catalog/src/main/java/com/d2os/catalog/CatalogSeedLoader.java` (v3 seed set).
  - Confirm it publishes: Knowledge Curator PersonaDefinition (with the knowledge profile = allowed tags/domains as a content-level field), one curation Playbook, one curation Rubric, and the Curator prompt set — as new published DefinitionAssets with provenance.
- [ ] T038 [US2] Verify the candidate governance API.
  - ↳ File: `knowledge/src/main/java/com/d2os/knowledge/api/CandidateController.java`.
  - Confirm `GET /knowledge/candidates` (filters `status`, `caseId`), `GET /knowledge/candidates/{id}` (returns `CandidateDetail`: latest revision, prefilter findings, gate records), `POST .../redaction` (**201** new revision / **409** not PREFILTERED), `POST .../d4` (**200** with `publishedItemId` / **403** role-or-self / **409** state-or-order) per contracts/api.yaml.

**Checkpoint**: `CapturePromotionIT` green — full default-deny pipeline end-to-end.

---

## Phase 5: User Story 3 — Deprecation flags past executions without rewriting history (Priority: P2)

**Goal**: Deprecating an item removes it from new retrievals and flags every past OperationExecution
whose snapshot referenced it — snapshots and outputs never mutated (FR-014…FR-016, SC-007).

**Independent Test**: Run ops injecting item K, deprecate K; K excluded from new retrievals; exactly the
affected executions flagged; snapshots/outputs byte-unchanged; a flagged execution still replays.

### Tests for User Story 3 ⚠️

- [ ] T039 [P] [US3] Verify `DeprecationIT` (SC-007).
  - ↳ File: `app/src/test/java/com/d2os/app/DeprecationIT.java`.
  - Asserts: execute ops injecting item K; `POST /knowledge/items/{K}/deprecate`; K excluded from new retrievals; `knowledge_affected_execution` flags exactly the executions whose snapshots referenced K (count == endpoint `affectedExecutions`); snapshots/outputs byte-unchanged; replay of a flagged execution reproduces its original output.

### Implementation for User Story 3

- [ ] T040 [US3] Verify `DeprecationService` (single-transaction, R8).
  - ↳ Files: `knowledge/src/main/java/com/d2os/knowledge/DeprecationService.java` (+ `AlreadyDeprecatedException.java`).
  - Confirm one transaction: set item version `status=DEPRECATED` (+ `deprecated_at`, required `deprecation_reason`), audited governance action; then insert one `knowledge_affected_execution` per distinct `operation_execution` referencing the deprecated version via an insert-select over `knowledge_injection_snapshot` (using `idx_injection_snapshot_item`); never touch snapshots/outputs. Re-deprecate → `AlreadyDeprecatedException` → **409**.
  - > **Deviation (accepted):** the governance record is an immutable **`AuditEntry`**, not a `decision` row. The V4 `decision` table is `NOT NULL case_instance_id` + CHECK-constrains `decision_type` to the case D-gates (`D1..D4`); a knowledge-item deprecation is bound to no single case and is not a D-gate, so it cannot be a `decision` row. The `audit_entry` stream is the correct system-of-record vehicle for FR-016.
- [ ] T041 [US3] Verify DEPRECATED items are excluded from new retrieval.
  - ↳ File: `knowledge/.../KnowledgeRetrievalService.java` (T019).
  - Confirm the `status='PUBLISHED'` predicate excludes deprecated versions and that in-flight, already-built envelopes keep their snapshotted versions (FR-014).
- [ ] T042 [US3] Verify the deprecate endpoint.
  - ↳ File: `knowledge/.../api/KnowledgeController.java`.
  - Confirm `POST /knowledge/items/{itemId}/deprecate` returns `{itemId, affectedExecutions}` (**200**) / **409** already deprecated, per contracts/api.yaml.
- [ ] T043 [US3] Verify the affected-execution review surface.
  - ↳ Files: `knowledge/src/main/java/com/d2os/knowledge/KnowledgeAffectedExecution.java` (+ repository); `KnowledgeController` review endpoints.
  - Confirm `GET /knowledge/affected-executions` (filters `reviewStatus`, `knowledgeKey`) and `POST /knowledge/affected-executions/{flagId}/review` (flag-only `OPEN→REVIEWED`, audited, execution untouched), per contracts/api.yaml + `AffectedExecution` schema.

**Checkpoint**: `DeprecationIT` green — deprecation flags without rewriting history.

---

## Phase 6: User Story 4 — Knowledge-influence KPI (Priority: P3)

**Goal**: On demand, run a paired with/without evaluation under one rubric version and emit
`kpi_sample(metric='knowledge_influence', value=delta)`; report `NOT_YET_MEASURABLE` for a
never-injected item (FR-017/018, SC-008).

**Independent Test**: `POST /knowledge/items/{id}/influence-evaluations`; assert two `evaluation=true`
runs under the same rubric version, a `knowledge_influence` sample == score delta, and
`GET /metrics/knowledge-influence` returns MEASURED; never-injected item → NOT_YET_MEASURABLE, no sample.

### Tests for User Story 4 ⚠️

- [ ] T044 [P] [US4] Verify `InfluenceKpiIT` (SC-008).
  - ↳ File: `app/src/test/java/com/d2os/app/InfluenceKpiIT.java`.
  - Asserts: `POST /knowledge/items/{id}/influence-evaluations` → two `evaluation=true` OperationExecutions (with/without) under the same RubricDefinition version; a `kpi_sample` row `metric=knowledge_influence` value=score delta with `dimensions->>'key'/'version'`; `GET /metrics/knowledge-influence` returns `MEASURED`; never-injected item → `NOT_YET_MEASURABLE`, no fabricated sample.

### Implementation for User Story 4

- [ ] T045 [US4] Verify `InfluenceEvaluationService` (paired runs, R9).
  - ↳ File: `knowledge/src/main/java/com/d2os/knowledge/influence/InfluenceEvaluationService.java` (nested `InfluenceResult`, `RunResult`).
  - Confirm it runs the target operation twice through the normal persona path (item force-included, then force-excluded) under identical inputs and the same RubricDefinition version, both flagged `evaluation=true` (never feed delivery), scores both, and computes `value = score_with − score_without`.
- [ ] T046 [US4] Verify the KPI emission + V15 schema widening.
  - ↳ Files: `observability/.../KpiEmitter.java`, `KpiSample.java`, `observability/src/main/resources/db/migration/V15__knowledge_influence_kpi.sql`.
  - Confirm V15 widens the `kpi_sample.metric` CHECK to include `knowledge_influence`, adds `dimensions JSONB DEFAULT '{}'`, and indexes `(dimensions->>'key', dimensions->>'version') WHERE metric='knowledge_influence'`. Confirm `KpiEmitter` emits `metric='knowledge_influence'` with `{key,version}` dimensions and the delta value. (Note: data-model.md said "no schema change"; V15 is the reconciled minimal widening — the V9 CHECK forbids new metric names.)
- [ ] T047 [US4] Verify the influence API.
  - ↳ File: `knowledge/src/main/java/com/d2os/knowledge/influence/InfluenceController.java` (nested `EvaluationRequest`, `InfluenceMetric`, `Sample`).
  - Confirm `POST /knowledge/items/{itemId}/influence-evaluations` (**202**, body `{caseId, personaKey}`, returns the measured `InfluenceResult`) and `GET /metrics/knowledge-influence` (per-item MEASURED reading or NOT_YET_MEASURABLE, never fabricated) per contracts/api.yaml + `InfluenceReading`/`InfluenceResult` schemas. Confirm it does not collide with the existing `observability/.../MetricsController.java`.
  - > **Deviation (accepted, contract updated):** the request body is `{caseId, personaKey}`, **not** `{operationDefinitionKey}`. A rubric version only resolves through a case's pinned `CaseDefinitionSnapshot` (Principle I), so a bare operation key cannot supply the scoring rubric. `contracts/api.yaml` was updated to match; the run is synchronous (deterministic stub gateway) and returns the `InfluenceResult` with the 202.

**Checkpoint**: `InfluenceKpiIT` green — all four user stories independently functional.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Enforce architecture invariants, confirm prior-phase guarantees, run the phase exit gate.

- [ ] T048 [P] Verify architecture rules (persona ↛ knowledge; gateway-only providers).
  - Confirm the ArchUnit rules assert: `persona` has no dependency on `com.d2os.knowledge` (retrieval only via the `KnowledgeProvider` SPI); only `AiGatewayClient` implementations call a model provider (incl. `embed`).
- [ ] T049 [P] Re-run Phase 1/2 suites unchanged with knowledge active (SC-009).
  - Suites: `SubmitToDeliverIT`, `ParallelExecutionIT`, `ParallelReplayIT`, `LeakageSuiteIT`, `InjectionSeedSuiteIT`, `TokenBudgetSuiteIT` (now counting injected-knowledge tokens), `WorkspaceBudgetSuiteIT`, `AuditGrantSuiteIT`, `ConsistencyCheckIT`, `AttachmentSandboxIT`, `LoadPostureIT`. All must pass unchanged.
- [ ] T050 Run the quickstart.md success checklist as the phase exit gate.
  - Command: `docker run --rm -v "$PWD":/w -w /w gradle:8.10-jdk21 gradle :app:test` (or `./gradlew :app:test`).
  - Confirm all six new suites green + startup seed verification: `GET /api/v1/knowledge/items` shows the five fixtures (workspace-scoped, project-scoped matching + non-matching, DEPRECATED) with correct statuses, and the Curator persona/playbook/rubric/prompt seeded.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies.
- **Foundational (Phase 2)**: depends on Setup — **BLOCKS all user stories**.
- **User Stories (Phases 3–6)**: all depend on Foundational.
  - US1 (P1) and US2 (P1) parallelizable once Foundational is done.
  - US3 (P2) depends on US1 (needs snapshots + retrieval predicate).
  - US4 (P3) depends on US1 (reuses the persona execution/snapshot path).
- **Polish (Phase 7)**: depends on all targeted user stories.

### Within Each User Story

Tests first → entities → services → BPMN/delegates → controllers. Same-file tasks sequential;
different-file `[P]` tasks parallel.

### Parallel Opportunities

- Setup: T003 ∥ T004.
- Foundational: T010 ∥ T011 ∥ T014 (migrations T005–T009 sequential within their files).
- US1 tests T016 ∥ T017 ∥ T018; US2 entities T028 ∥ T029; delegate set T036 (multiple files).
- After Foundational, US1 and US2 (both P1) can be built by two developers concurrently.

---

## Parallel Example: User Story 1

```bash
# Verify/execute all US1 tests together:
Task: "KnowledgeRetrievalIT — app/src/test/java/com/d2os/app/KnowledgeRetrievalIT.java"
Task: "KnowledgeReplayIT   — app/src/test/java/com/d2os/app/KnowledgeReplayIT.java"
Task: "KnowledgeLeakageIT  — app/src/test/java/com/d2os/app/KnowledgeLeakageIT.java"

# Verify/execute independent foundational models together:
Task: "KnowledgeItem  — knowledge/src/main/java/com/d2os/knowledge/KnowledgeItem.java"
Task: "KnowledgeScope — knowledge/src/main/java/com/d2os/knowledge/KnowledgeScope.java"
```

---

## Implementation Strategy

### MVP First (User Story 1 only)

1. Phase 1 Setup → 2. Phase 2 Foundational (blocks everything) → 3. Phase 3 US1.
4. STOP and validate: `KnowledgeRetrievalIT` + `KnowledgeReplayIT` + `KnowledgeLeakageIT` green.
5. Demo scoped injection + exact replay — the phase headline P1.

### Incremental Delivery

Setup + Foundational → US1 (MVP) → US2 (default-deny pipeline) → US3 (deprecation) → US4 (KPI) →
Polish (arch rules + SC-009 regression + quickstart exit gate). Each story adds value without
breaking the previous ones.

### Parallel Team Strategy

After Foundational: Dev A on US1 (retrieval/snapshot/replay), Dev B on US2 (capture pipeline + BPMN +
Curator seed). US3 and US4 follow once US1's snapshot path lands.

---

## Notes

- Every task cites its design source (FR-/SC-/R-/T2-/T3-/AD- ids) and its exact file — no design re-derivation at implementation time.
- Snapshot writes and gate/deprecation state changes are same-transaction with AuditEntry (Principles II/III/V) — never split across transactions.
- Because the tree already contains an implementation, each task above is a **build-or-verify** step: open the `↳ File:`, confirm every sub-step, and check the box; implement any gap exactly as written.
- Commit after each task or logical group; stop at any checkpoint to validate a story independently.
