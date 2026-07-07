# Implementation Plan: Knowledge Layer

**Branch**: `003-knowledge-layer` | **Date**: 2026-07-07 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/003-knowledge-layer/spec.md`

## Summary

Phase 3 adds the governed knowledge layer to the existing Initiation pipeline: a new `knowledge`
bounded-context module owning the immutable, versioned KnowledgeItem (workspace-anchored scope
lattice + tags + lifecycle), a Knowledge Retrieval Service exposed as an Open Host Service to
persona execution, and a per-workspace LIST-partitioned pgvector index so similarity retrieval is
structurally incapable of crossing workspaces (T2-b) — with the AI Gateway independently asserting
workspace scope on every injection (T2-c). Every knowledge-injected OperationExecution records an
injection snapshot (exact item id + version, same transaction) and the Phase 1 replay harness is
extended to reconstruct the injected context byte-for-byte (AD-9/NFR-6). A case-end capture
subprocess (standalone BPMN process triggered on Case `Delivered`) produces project-confidential
candidates that can only be published through the fixed default-deny pipeline: deterministic
sensitivity/PII pre-filter → Knowledge Curator persona redaction (rubric-gated, version-controlled)
→ workspace-owner D4 gate (Q5/T3-c). Deprecation removes an item from retrieval and flags — never
rewrites — the past executions that used it. A knowledge-influence KPI (rubric-score delta
with/without an item) is emitted through the existing `kpi_sample` stream (E3.4).

## Technical Context

**Language/Version**: Java 21 (Gradle toolchain; build runs via Docker `gradle:8.10-jdk21`)

**Primary Dependencies**: Spring Boot 3.3.5, Flowable 7.0.1 (embedded BPMN — capture process +
D4 user task), Spring Data JPA, Flyway, pgvector (`vector` column + HNSW per-partition indexes),
provider-agnostic AI Gateway extended with an embeddings operation (Anthropic-shape API in prod,
deterministic stub in tests)

**Storage**: PostgreSQL 16 + pgvector (RLS-enforced, least-privilege `d2os_app` role);
`knowledge_item` LIST-partitioned by `workspace_id` (partition created at workspace provisioning);
S3/MinIO unchanged (knowledge content is relational text, not object-store blobs)

**Testing**: JUnit 5 + Testcontainers (docker-outside-of-docker), StubAiGatewayClient extended
with deterministic embeddings; existing IT suites (SubmitToDeliver, Leakage, InjectionSeed, Replay,
ParallelExecution) re-run unchanged (SC-009); new KnowledgeRetrievalIT, KnowledgeReplayIT,
CapturePromotionIT, DeprecationIT, KnowledgeLeakageIT, InfluenceKpiIT

**Target Platform**: Linux server (single-region cloud), local dev via docker-compose

**Project Type**: Modular-monolith web service (adds an 11th bounded-context Gradle module:
`knowledge`)

**Performance Goals**: Retrieval adds no user-visible latency step — knowledge resolution runs
inside envelope building and must stay well under the ≤10 min p95 per-operation bound (NFR-3);
retrieval itself budgeted ≤ 500 ms p95 at the seeded-catalog scale; Phase 2 load posture (50
concurrent cases) must hold with injection enabled

**Constraints**: Injection snapshot written in the same transaction as OperationExecution
(Principle II/III); vector index partitioned per workspace + gateway scope assertion = two
independent isolation layers (T2-b/c, defense in depth); default-deny promotion — no code path
publishes a candidate without pre-filter → redaction → D4 in order (Q5, Principle V); deprecation
never mutates historical snapshots (FR-015/016); personas never write knowledge directly (AD-8,
FR-019)

**Scale/Scope**: 1 new Gradle module, ~7 new tables (2 Flyway migrations V13–V14), 1 new BPMN
process (knowledge-capture), 1 new persona + playbook + rubric + prompt set (Curator), ~8 new API
endpoints, 1 AI Gateway operation (embed), 6 new integration test suites, seed KnowledgeItem set

## Constitution Check

*GATE: evaluated against Constitution v1.0.0 before Phase 0 research; re-checked after Phase 1 design.*

| # | Principle | Status | How this plan complies |
|---|---|---|---|
| I | Definition/Instance Immutability | ✅ PASS | KnowledgeItem follows the immutable `(key, version)` discipline: publish freezes a version, any change (including Curator redaction) produces a new version, prior versions stay retrievable for replay. The Curator persona/playbook/rubric/prompts ship as new published DefinitionAssets through `CatalogSeedLoader`; the capture process is a new versioned ProcessDefinition — running cases keep their pinned snapshots. |
| II | Reproducible, Bounded AI (NON-NEGOTIABLE) | ✅ PASS | Injection snapshot (exact item id + version per injected item) is written on every knowledge-injected OperationExecution in the same transaction; replay reconstructs the injected context from the snapshot, keeping byte-identical replay. The Curator is a stateless persona: it drafts redactions inside the governed flow, never publishes, never approves its own output — publication requires the human D4 gate. Embeddings go through the AI Gateway and record model identity/version. |
| III | System of Record Integrity | ✅ PASS | All knowledge state (items, versions, candidates, gate outcomes, snapshots, deprecation flags, KPI samples) is relational rows written with AuditEntry in the same transaction; outbox events extended for knowledge lifecycle. The pgvector embedding is a derived column on the relational row — searchable, but the row is the record. No graph/dual-write introduced. |
| IV | Workspace Isolation & Provenance | ✅ PASS | `knowledge_item` is LIST-partitioned per workspace **and** carries the standard RLS policy; the AI Gateway asserts the caller's workspace against every injected item before prompt assembly (T2-c) — a scope error is refused, not leaked. Capture candidates inherit the case's workspace; promotion widens scope only through the governed pipeline, preserving provenance (candidate → redaction version → published item chain). New module extends the established per-BC structure. |
| V | Default-Deny Security & Auditable Gates | ✅ PASS | Promotion is default-deny by construction: candidates are born project-confidential/non-promotable; the only publish path is pre-filter → Curator redaction (sensitive fields excluded by default, version-controlled) → D4 human gate, each step producing an auditable record; any rejection leaves the candidate non-promotable with a recorded reason. Deprecation is an audited governance action that flags affected executions without rewriting history. |

**Post-design re-check (after data-model + contracts)**: no violations introduced — all new tables
carry `workspace_id` + RLS; the D4 gate is a human decision recorded as a Decision row (the
first-class gate subprocess remains Phase 5); no schema mutation of Phase 1/2 tables (only additive
FKs to `operation_execution`). **GATE: PASS.**

## Project Structure

### Documentation (this feature)

```text
specs/003-knowledge-layer/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── api.yaml         # Phase 1 output (delta over Phase 2 api.yaml)
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created by /speckit-plan)
```

### Source Code (repository root)

Existing modular monolith (one Gradle module per bounded context, PD-1). Phase 3 adds **one new
module** (`knowledge` — the Knowledge bounded context named in the phase plan as Knowledge
Retrieval Svc) and extends four existing ones:

```text
knowledge/                     # NEW MODULE: Knowledge bounded context
├── src/main/java/com/d2os/knowledge/
│   ├── KnowledgeItem.java             # immutable versioned entity (+ repository)
│   ├── KnowledgeScope.java            # scope lattice value (WORKSPACE | PROJECT) + ref
│   ├── KnowledgeRetrievalService.java # Open Host Service: (scope, tags, persona profile) → items
│   ├── EmbeddingIndexer.java          # embeds published items via AI Gateway embed op
│   ├── DeprecationService.java        # deprecate + flag affected executions (same tx)
│   ├── capture/                       # CaptureCandidate, CaptureService, SensitivityPreFilter,
│   │   └── ...                        #   RedactionService (versioned), PromotionGateService (D4)
│   ├── influence/                     # InfluenceEvaluationService (with/without paired runs)
│   └── api/                           # KnowledgeController, CandidateController, InfluenceController
├── src/main/resources/db/migration/V13__knowledge.sql
└── build.gradle                       # depends on tenancy, catalog, persona (SPI), casecore
persona/
├── src/main/java/com/d2os/persona/
│   ├── ExecutionEnvelopeBuilder.java  # + knowledge slot (injected items rendered as data)
│   ├── OperationExecutionRecorder.java# + writes knowledge_injection_snapshot in same tx
│   ├── gateway/AiGatewayClient.java   # + embed(...) operation; scope assertion on injection
│   └── spi/KnowledgeProvider.java     # NEW SPI implemented by knowledge module (no cycle)
├── src/main/resources/db/migration/V14__injection_snapshot.sql
orchestration/
├── src/main/resources/processes/knowledge-capture.bpmn20.xml  # NEW: capture → prefilter →
│   │                                                          #   curator task → D4 userTask
├── src/main/java/com/d2os/orchestration/
│   ├── CaseDeliveredKnowledgeTrigger.java  # NEW: starts capture process on Delivered (outbox)
│   ├── CuratorStepDelegate.java            # NEW: runs Curator persona op (reuses PersonaStep path)
│   └── PreFilterDelegate.java              # NEW: deterministic sensitivity/PII pass
catalog/                       # + CatalogSeedLoader v3 seed set (Curator persona/playbook/rubric/
│                              #   prompts, seed KnowledgeItems)
replay/                        # + replay harness reconstructs injected knowledge from snapshots
observability/                 # + knowledge_influence KPI emission (reuses kpi_sample, V9)
app/
└── src/test/java/com/d2os/app/
    ├── KnowledgeRetrievalIT.java      # US1: scope/tags/profile matching, snapshot written
    ├── KnowledgeReplayIT.java         # US1/SC-003: byte-identical replay after item edits
    ├── KnowledgeLeakageIT.java        # SC-004: partition + gateway assertion (T2-b/c)
    ├── CapturePromotionIT.java        # US2: full pipeline, rejection paths, default-deny
    ├── DeprecationIT.java             # US3: retrieval exclusion + flags, history preserved
    └── InfluenceKpiIT.java            # US4: with/without delta emitted; not-yet-measurable
```

**Structure Decision**: add the `knowledge` module rather than growing `persona` — knowledge has
its own lifecycle, governance, and API surface, and the phase plan names it a distinct service
behind an Open Host Service boundary. `persona` must not depend on `knowledge` (envelope building
consumes a `KnowledgeProvider` SPI defined in `persona`, implemented in `knowledge` and wired in
`app`), keeping the dependency direction acyclic. Migrations continue the single ordered stream:
**V13** (knowledge module: item/candidate/gate/flag tables + partitioning), **V14** (persona
module: injection-snapshot table, FK to `operation_execution`).

## Complexity Tracking

> No constitution violations — table intentionally empty. (The new module is demonstrated need —
> a named bounded context with its own governance surface — not speculative structure.)

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| — | — | — |
