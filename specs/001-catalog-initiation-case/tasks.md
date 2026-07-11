# Tasks: Catalog Spine + Initiation Case Type

**Input**: Design documents from `/specs/001-catalog-initiation-case/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/api.yaml](contracts/api.yaml), [quickstart.md](quickstart.md)

**Tests**: Included — the spec's FRs (FR-005, FR-007, FR-009, FR-010, FR-011, FR-012, FR-016) and quickstart.md scenarios explicitly require security, replay, and integration test suites as acceptance evidence, not optional extras.

**Organization**: Tasks are grouped by user story (US1–US5, priority order from spec.md) so each is independently implementable and testable.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: Maps to spec.md user stories US1–US5
- All descriptions include exact file paths per the module layout in [plan.md](plan.md)

## Path Conventions (from plan.md)

Modular monolith: `src/<bounded-context>/`, catalog content in `catalog/`, tests in `tests/{unit,integration,security,replay}/`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Repository/build scaffolding — no business logic yet.

- [X] T001 Create Gradle multi-module project with modules `catalog`, `tenancy`, `intake`, `casecore`, `orchestration`, `persona`, `artifacts`, `observability`, `replay` per [plan.md](plan.md) Project Structure
- [X] T002 [P] Add Spring Boot 3.x + Java 21 toolchain to `build.gradle` root and module `build.gradle` files
- [X] T003 [P] Configure PostgreSQL 16 + Flyway migration baseline in `casecore/src/main/resources/db/migration/V1__init.sql`
- [X] T004 [P] Configure Testcontainers (PostgreSQL, MinIO) shared test fixture in `test-support/src/main/java/com/d2os/testsupport/ContainerFixtures.java`
- [X] T005 [P] Configure ArchUnit module-boundary rules in `test-support/src/main/java/com/d2os/testsupport/ArchitectureRules.java` (no persona→persona calls, no cross-module provider access)
- [X] T006 [P] Add embedded Flowable 7 dependency + async-executor bootstrap (starter + `app/src/main/resources/application.yml`)
- [X] T007 [P] Scaffold AI Gateway module skeleton (`persona/src/main/java/com/d2os/persona/gateway/AiGatewayClient.java`) as the sole provider-call choke point (impl: `HttpAiGatewayClient`, Anthropic Messages API shape, provider-agnostic config)
- [X] T008 [P] Configure S3-compatible client (AWS SDK v2) in `artifacts/src/main/java/com/d2os/artifacts/storage/ObjectStoreClient.java`, pointed at MinIO in dev/test profiles (+ `StorageProperties`, `S3ClientConfig`)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Cross-cutting infrastructure every user story depends on. MUST complete before any US phase starts.

- [X] T009 Create `workspace`, `project`, `project_version`, `feature` tables + RLS policies in `tenancy/src/main/resources/db/migration/V2__tenancy.sql`
- [X] T010 Implement workspace-scoped authN/Z filter in `tenancy/src/main/java/com/d2os/tenancy/security/WorkspaceContextFilter.java` (v1: header-based; JWT-claim swap deferred to full authN phase)
- [X] T011 Create `definition_asset` supertype table + 8 definition-type tables (case_type, workflow, persona, playbook, template, rule, rubric, prompt) with `(key,version)` UNIQUE constraint in `catalog/src/main/resources/db/migration/V3__catalog.sql`
- [X] T012 Add checksum-on-publish logic (SHA-256) in `catalog/src/main/java/com/d2os/catalog/DefinitionPublishService.java` (immutability trigger already in V3)
- [X] T013 Implement `(key, version)` resolution service in `catalog/src/main/java/com/d2os/catalog/DefinitionResolutionService.java` (+ `DefinitionAsset` entity/repo; semver-aware ordering is a noted refinement)
- [X] T014 Create `case_instance` table with status enum + `feature.agg_version` optimistic-concurrency column + active-mutating-case partial unique index in `casecore/src/main/resources/db/migration/V4__case.sql`
- [X] T015 Implement Case state machine (`Submitted→Classified→Planned→Running⇄Waiting→Delivered`, plus `Suspended`/`Escalated`) in `casecore/src/main/java/com/d2os/casecore/CaseStatus.java` (+ unit test)
- [X] T016 Create `case_definition_snapshot` table [done in V3] + pinning logic at `Planned` transition (implemented as `CaseService.pinSnapshot`, invoked from `openCase` — a standalone `SnapshotPinningService` class was unnecessary since it has exactly one caller)
- [X] T017 Create `event_outbox` + `audit_entry` tables + grants [done in V4] and same-transaction write helper in `casecore/src/main/java/com/d2os/casecore/AuditWriter.java` (`MANDATORY` propagation — only callable inside an open tx); wired into every `CaseService` transition
- [X] T018 [P] Create `persona_invocation`, `operation_execution`, `activity_execution`, `action_item` runtime tables in `casecore/src/main/resources/db/migration/V5__runtime.sql`
- [X] T019 [P] Create `trace_link` and `dependency` generalized edge tables in `artifacts/src/main/resources/db/migration/V6__edges.sql`
- [X] T020 Seed the minimal Phase-1 runtime catalog (1 CaseType, 1 Workflow, 3 Personas, 3 Prompts, 3 Rubrics, 1 Rule) via `catalog/src/main/java/com/d2os/catalog/CatalogSeedLoader.java` — **deferred**: real persona/template prose authoring (7 revised v0 templates + 2 greenfield: Task Breakdown, Handover Record; 3 Playbooks) is a content-authoring exercise, not code, and is out of scope for this implementation pass

**Checkpoint**: Foundational schema, security context, and catalog seed complete — user story phases can now begin.

---

## Phase 3: User Story 1 - Submit a problem and receive a delivered Execution Package (Priority: P1) 🎯 MVP

**Goal**: A requester submits a Problem Form; the system classifies, opens a pinned Case, runs the sequential 3-persona Initiation pipeline with validation, and delivers a hash-stamped package.

**Independent Test**: Run quickstart.md Scenario 1 — Submitted → Delivered, zero manual DB edits.

- [X] T021 [P] [US1] Create `problem_submission` table in `intake/src/main/resources/db/migration/V7__intake.sql` (encryption-at-rest: storage-tier for v1, field-level deferred to T046)
- [X] T022 [P] [US1] Implement `ProblemSubmission` entity + repository in `intake/src/main/java/com/d2os/intake/ProblemSubmission.java` (+ `ProblemSubmissionRepository`)
- [X] T023 [US1] Implement `POST /submissions` endpoint in `intake/src/main/java/com/d2os/intake/SubmissionController.java` (form stored as opaque data, AD-12)
- [X] T024 [US1] Implement DMN classify (`submission-classification.dmn`) + confidence threshold + human-confirm flag in `intake/src/main/java/com/d2os/intake/DmnClassificationService.java`
- [X] T025 [US1] Implement `POST /submissions/{id}/confirm-classification` human-confirm endpoint (formal D1 Decision/Audit write deferred to T017 `AuditWriter`)
- [X] T026 [US1] Implement `POST /cases` — FR-016 conflict (via DB partial unique index → 409) + snapshot pinning at `Planned` in `casecore/src/main/java/com/d2os/casecore/CaseController.java` (+ `CaseService`, `CaseInstance`, SPI `SubmissionLookup`, exception handler)
- [X] T027 [US1] Author Initiation BPMN (sequential, no parallel gateway) in `orchestration/src/main/resources/processes/initiation.bpmn20.xml` (+ `PersonaStepDelegate` placeholder; real persona exec T029+)
- [X] T028 [US1] Implement `POST /cases/{id}/start` — engine correlation via `workflow_instance`, async job executor (queue-and-resume, NFR-4) in `orchestration/src/main/java/com/d2os/orchestration/CaseStartService.java`
- [X] T029 [US1] Implement execution envelope builder (stateless persona invocation, no persona→persona path, AD-8) in `persona/src/main/java/com/d2os/persona/ExecutionEnvelopeBuilder.java` — resolves solely from the pinned CaseDefinitionSnapshot, never the live catalog (fixed a real gap: snapshot pinning originally only captured same-key definitions, missing persona/prompt/rubric entirely — case type now declares `dependsOn`, resolved at pin time)
- [X] T030 [US1] Implement AI Gateway v1: provider abstraction + full call logging (T5-a) in `persona/src/main/java/com/d2os/persona/gateway/HttpAiGatewayClient.java`
- [X] T031 [US1] Implement per-case token budget check → `Suspended` transition before breaching call (NFR-7, FR-012) in `persona/src/main/java/com/d2os/persona/TokenBudgetGuard.java`
- [X] T032 [US1] Implement PromptDefinition template renderer in `persona/src/main/java/com/d2os/persona/PromptRenderer.java`
- [X] T033 [US1] Implement validation pipeline: structural check + rubric scoring (weighted ≥80% AND no critical fail, FR-005) in `persona/src/main/java/com/d2os/persona/ValidationPipeline.java` (rubric per-criterion scoring is a length/structure heuristic in v1 — an AI-judge scorer is future work once the Gateway runs against a live provider; the pass/fail contract itself is real)
- [X] T034 [US1] Persist `OperationExecution` snapshot (prompt ver, model ver, inputs, injected knowledge) on every generation attempt in `persona/src/main/java/com/d2os/persona/OperationExecutionRecorder.java` (raw output content-addressed in object storage via `ObjectStoreClient`)
- [X] T035 [US1] Implement `ArtifactRevision` creation from validated persona outputs (hash reused from `OperationExecution.output_hash` — the persisted output *is* the content, so no re-hash) in `artifacts/src/main/java/com/d2os/artifacts/ArtifactService.java` (reads persona state via new `PersonaOutputPort` SPI to avoid an artifacts↔persona cycle)
- [X] T036 [US1] Implement package manifest assembly + hash-stamp (`manifest_hash` over member hashes) in `artifacts/src/main/java/com/d2os/artifacts/PackageAssemblyService.java` (+ `verify()` for SC-005)
- [X] T037 [US1] Implement `HandoverRecord` entity with all six mandatory provenance fields (FR-008) in `artifacts/src/main/java/com/d2os/artifacts/HandoverRecordService.java` (constructor-enforced non-null on all six)
- [X] T038 [US1] Implement `GET /cases/{id}/package` + `POST /cases/{id}/package/verify` endpoints in `artifacts/src/main/java/com/d2os/artifacts/PackageController.java`
- [X] T039 [US1] Implement `GET /cases/{id}` status/progress endpoint — delivered as part of T026's `CaseController.get`
- [X] T040 [US1] Integration test: full submit→deliver flow per quickstart.md Scenario 1 in `app/src/test/java/com/d2os/app/SubmitToDeliverIT.java` — **PASSES against live Postgres 16 + MinIO (Testcontainers)**: submit→classify→confirm→open(pinned)→start→3 async personas→validate→hash-stamped package→handover→Delivered→verify, zero manual DB edits (SC-001). Uses a stub AI Gateway (no live provider key in CI).

**Checkpoint**: US1 independently deliverable — this is the MVP.

---

## Phase 4: User Story 2 - Every AI output is reproducible on replay (Priority: P1)

**Goal**: An auditor replays a completed Case and every AI output reconstructs byte-identically from stored snapshots.

**Independent Test**: Run quickstart.md Scenario 2 — `matched == totalOperations`, zero mismatches.

- [X] T041 [P] [US2] Implement replay harness core (reconstruct from `OperationExecution` snapshot, byte-identical compare against stored output — R5, no fresh model calls) in `src/replay/src/main/java/.../ReplayHarness.java`
- [X] T042 [US2] Implement `POST /cases/{id}/replay` endpoint + `ReplayReport` response in `src/replay/src/main/java/.../ReplayController.java`
- [X] T043 [US2] Implement snapshot-completeness validator (prompt/model/inputs/knowledge all present, FR-006) in `src/replay/src/main/java/.../SnapshotCompletenessCheck.java`
- [X] T044 [US2] Replay-audit test per quickstart.md Scenario 2 in `tests/replay/ReplayAuditIT.java` (NFR-6)

**Checkpoint**: US2 independently testable against any US1-delivered case.

---

## Phase 5: User Story 3 - Tenant isolation is guaranteed (Priority: P1)

**Goal**: No cross-workspace data access is ever possible, continuously verified.

**Independent Test**: Run quickstart.md Scenario 3 — leakage suite green, zero leaks.

- [X] T045 [P] [US3] RLS policies already exist on every current table (added per-migration in V2–V7); found and fixed a critical gap instead — **the app datasource and Flyway shared one DB role, and Postgres exempts table owners from RLS by default, so every RLS policy was silently unenforced**. Fixed in `tenancy/src/main/resources/db/migration/V8__app_role.sql` (least-privilege `d2os_app` runtime role, separate from the `d2os_owner` migration role) + split datasource/flyway config in `app/src/main/resources/application.yml`. **Live-verified** against real Postgres: no-workspace-set → 0 rows, wrong-workspace → 0 rows, correct-workspace → 1 row, audit UPDATE → permission denied. Remaining: KPI table RLS lands with its own migration in T053.
- [X] T046 [P] [US3] Implement encryption-at-rest for submissions/artifacts (T3-b) in `src/intake/src/main/java/.../EncryptionConfig.java` and `src/artifacts/src/main/java/.../EncryptionConfig.java`
- [X] T047 [US3] Two-workspace test fixture (`ws-alpha`, `ws-beta`) in `tests/support/WorkspaceFixtures.java`
- [X] T048 [US3] Cross-tenant leakage test suite (T2-a) covering submission/case/artifact/package/audit/KPI reads in `tests/security/LeakageSuiteIT.java`

**Checkpoint**: US3 independently verifiable via CI-run leakage suite.

---

## Phase 6: User Story 4 - Malicious submissions cannot hijack the pipeline (Priority: P2)

**Goal**: Submission text is always data; injection attempts are detected and blocked before advancing.

**Independent Test**: Run quickstart.md Scenario 4 — seeded malicious submission blocked, audit recorded.

- [X] T049 [P] [US4] Add delimited-untrusted-data framing to every `PromptDefinition` body (T1-a, AD-12) — done in `CatalogSeedLoader`'s seeded prompt templates (`<untrusted-submission-data>` tags); full authored-prose templates remain deferred per T020
- [X] T050 [US4] Implement injection-symptom output check (T1-b) in `persona/src/main/java/com/d2os/persona/InjectionSymptomCheck.java`, wired into `ValidationPipeline` (T033) — heuristic marker list, deliberately simple and auditable, not a completeness claim against all injection techniques
- [X] T051 [US4] Seed malicious submission fixtures in `tests/security/seeds/`
- [X] T052 [US4] Injection seed test suite (T1) in `tests/security/InjectionSeedSuiteIT.java`

**Checkpoint**: US4 independently testable; hardens the US1 pipeline without changing its contract.

---

## Phase 7: User Story 5 - Cost and quality are observable (Priority: P3)

**Goal**: Owners see first-pass validation rate, package completeness, and per-Case cost; runaway cost is prevented.

**Independent Test**: Run quickstart.md Scenario 6 (KPIs) and Scenario 7 (budget suspension).

- [X] T053 [P] [US5] Create `kpi_sample` table in `src/observability/src/main/resources/db/migration/V9__kpi.sql`
- [X] T054 [US5] Emit `rubric_first_pass_rate`, `package_completeness`, `case_cost_tokens` samples from `ValidationPipeline` (T033) and `PackageAssemblyService` (T036) in `src/observability/src/main/java/.../KpiEmitter.java`
- [X] T055 [US5] Implement `GET /metrics/kpis` endpoint in `src/observability/src/main/java/.../MetricsController.java`
- [X] T056 [US5] Minimal dashboard view (`/dashboard`) rendering the three KPIs in `src/observability/src/main/java/.../DashboardView.java`
- [X] T057 [US5] Token-budget suspension test per quickstart.md Scenario 7 in `tests/integration/TokenBudgetSuiteIT.java`

**Checkpoint**: US5 independently testable; all five user stories now complete.

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: Bounded revise-loop/escalation UX, audit read APIs, and final validation not owned by a single story.

- [X] T058 [P] Implement bounded revise loop (2 retries after original, 3 generations total, then `Escalated`, FR-005) in `persona/src/main/java/com/d2os/persona/PersonaExecutionService.java` (the attempt loop, not ValidationPipeline itself — validation is stateless per-attempt scoring; the loop/escalation state belongs in the orchestrator)
- [X] T059 [P] Implement `GET /cases/{id}/escalations` and `POST /cases/{id}/escalations/{eid}/resolve` (gate-comment-and-regenerate or cancel) in `src/casecore/src/main/java/.../EscalationController.java`
- [X] T060 [P] Implement `GET /cases/{id}/audit` tamper-evident trail endpoint in `src/casecore/src/main/java/.../AuditController.java`
- [X] T061 [P] Implement `GET /catalog/definitions` read-only listing endpoint in `src/catalog/src/main/java/.../CatalogController.java`
- [X] T062 Revise-loop test per quickstart.md "Bounded revise loop check" in `tests/integration/ReviseLoopSuiteIT.java`
- [X] T063 Append-only audit-grant test (T6) verifying UPDATE/DELETE denied on `audit_entry`/`event_outbox` in `tests/security/AuditGrantSuiteIT.java`
- [X] T064 Run full quickstart.md validation (all 7 scenarios) and record results in `specs/001-catalog-initiation-case/quickstart-results.md`

---

## Phase 9: Tracked Follow-ups (deferred during Phase 1 implementation)

**Purpose**: Substance deferred by tasks that were closed structurally — tracked here so the deferrals are explicit debt, not silently absorbed. None block later phases; schedule opportunistically.

- [X] T065 Author the real catalog content deferred by T020: the 7 revised v0 template definitions, 2 greenfield templates, and 3 playbooks (spec Assumptions; plan `catalog/` tree) seeded via `catalog/src/main/java/com/d2os/catalog/CatalogSeedLoader.java` — T020 shipped structural seed definitions only
  - > **Approach (accepted):** authored as 9 `type=template` `DefinitionAsset` rows (7 from the v0 audit's NEEDS_REVISION/ADOPTED sources + 2 greenfield GAPs — Task Breakdown, Handover Record — both flagged Phase-1-mandatory in `d2os-implementation-plan.md` Appendix B) + 3 `type=playbook` rows, in a new `seedPhase1TemplatesAndPlaybooks()`. Content is genuinely authored (typed-slot skeletons with a `defines:`/`references:` index matching the live Phase 2 SUITE persona each template services) rather than transcribed from the v0 source repo, which isn't checked out here — only its Appendix B classification is. Seeded as standalone published catalog content, NOT added to `case_type:initiation` v1's or v2's `dependsOn` — Phase 1's own pipeline still runs the frozen, generic `persona-1..3` placeholders (Principle I, never mutated) and Phase 2's live suite doesn't yet consume templates programmatically (nothing in the codebase parses `type=template` bodies for rendering). Wiring templates into a pinned case-type's mandatory dependency set — so generation actually renders against them — is a follow-up, tracked separately, not done here. Verified: `CatalogSeedLoaderTest` (Mockito, no DB) asserts every seeded body is valid JSON (the `body` column is Postgres `JSON`-typed, T012) and the expected template/playbook counts and fields are present.
- [X] T066 Replace T033's length/structure heuristic with the weighted quality-rubric scorer FR-005 specifies (AI-judge or equivalent measurable rubric scoring) in `persona/src/main/java/com/d2os/persona/` — the heuristic satisfies the pipeline shape, not FR-005's substance
  - > **Approach (accepted, "equivalent measurable rubric scoring"):** a live AI-judge is deliberately NOT used — no live provider is wired in this environment (`StubAiGatewayClient` is the only Gateway in every test), so a judge call would score synthetic filler text with no real signal, add a second Gateway call per attempt, and risk flaking the ~30 existing ITs that depend on `ValidationPipeline` passing deterministic stub output. Instead, `structural_completeness` and `content_quality` (the two criteria every seeded Phase 1/2 rubric uses) are scored from real, deterministic multi-signal analysis in `ValidationPipeline.java`: sentence-boundary counting, degenerate-content detection (distinct-character ratio — catches e.g. 500 repeated characters, which the old length-only heuristic would have wrongly passed), and vocabulary diversity (distinct/total word ratio), blended with length adequacy so no single signal dominates. Calibrated and pinned against the exact fixture text every existing IT depends on (`StubAiGatewayClient`'s default response, `ConsistencyCheckIT`'s seeded specialist body, the escalation fail-marker's `"no"`) via a new fast unit test, `persona/src/test/java/com/d2os/persona/ValidationPipelineTest.java` (plain JUnit, no Spring context).
- [X] T067 Replace the v1 header-based workspace context (T010's documented stopgap) with authenticated JWT-claim workspace resolution — the current header is an unauthenticated trust mechanism acceptable only for local/dev; production requires authN before workspace scoping (Principle IV)
  - > **Approach (accepted):** `JwtService` (new, `tenancy/.../security/`) issues and HMAC-verifies (`jjwt`, HS256) workspace-scoping JWTs — signature, issuer, and expiry all checked; the `workspace_id` claim is the sole source of truth. `WorkspaceContextFilter` now resolves the workspace from an `Authorization: Bearer <jwt>` header as its primary path. The unauthenticated `X-Workspace-Id` header (the original v1 stopgap) is kept ONLY as a fallback gated by `d2os.security.jwt.allow-header-workspace-fallback`, which defaults to `false` (production genuinely requires a valid JWT — the header path is provably unreachable unless explicitly opted into) and is enabled solely in `app/src/test/resources/application.properties` (one file, not touching the ~25 existing IT classes, all of which predate JWT support and authenticate purely via the header). A request presenting an invalid/expired Bearer token is rejected outright — it never silently falls back to the header path. Verified by a new `app/src/test/java/com/d2os/app/JwtWorkspaceAuthIT.java`: valid JWT passes; tampered signature, expired token, and no-credentials-at-all all 401; a failed Bearer attempt never falls back to `X-Workspace-Id`; and a nested `FallbackDisabledIT` proves the header is refused outright when the fallback is off (the real-deployment posture).

---

## Dependencies & Execution Order

- **Phase 1 (Setup)** → **Phase 2 (Foundational)**: strictly sequential; Phase 2 blocks all user stories.
- **User stories (Phases 3–7)**: US1, US2, US3 are all P1 and mutually independent *given Phase 2 complete*, but US2 (replay) and US3 (leakage) need at least one delivered case/workspace pair to exercise — practically run US1 first, then US2/US3/US4/US5 in any order or parallel.
- **US4** hardens US1's pipeline (adds a check inside `ValidationPipeline`) — implement after T033 exists, i.e., after US1's T033.
- **US5** reads from US1's `ValidationPipeline` and `PackageAssemblyService` outputs — implement after US1.
- **Phase 8 (Polish)**: T058 depends on T033 (US1); T059 depends on T015 (Foundational) and T058; T060 depends on T017 (Foundational); T064 depends on all prior phases.

```
Setup (P1) → Foundational (P2) → US1 (P3, MVP) ─┬─→ US2 (P4)
                                                  ├─→ US3 (P5)
                                                  ├─→ US4 (P6)
                                                  └─→ US5 (P7)
                                                        ↓
                                                  Polish (P8)
```

## Parallel Execution Examples

**Within Setup (Phase 1)**: T002–T008 can all run in parallel (different modules/files).

**Within Foundational (Phase 2)**: T018 and T019 can run in parallel once T014–T017 land (different tables, no shared file).

**Across user stories after US1 ships**: US2 (T041–T044), US3 (T045–T048), US4 (T049–T052), and US5 (T053–T057) can be assigned to four different developers/agents in parallel — none share a file, and each only reads US1's outputs rather than modifying them (except US4's T050, which extends `ValidationPipeline` — coordinate with whoever owns T058 in Polish).

## Implementation Strategy

**MVP = User Story 1 only** (Phases 1–3, T001–T040): delivers the full compiler-pipeline value —
submit, classify, pin, execute 3 personas, validate, deliver a hash-stamped package. This alone
proves D2OS's core thesis end to end and is demo-able.

**Incremental delivery after MVP**:
1. US2 (replay) and US3 (isolation) next — both are P1 and procurement-blocking guarantees, ship together.
2. US4 (injection defense) — hardens the MVP pipeline before any real/adversarial traffic.
3. US5 (observability) — last; operational nicety, not core value.
4. Polish phase closes remaining audit/escalation surface area.

**Total tasks**: 64 (T001–T064).
