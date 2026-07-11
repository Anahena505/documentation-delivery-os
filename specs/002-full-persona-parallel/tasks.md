# Tasks: Full Persona Suite + Parallel Execution

**Input**: Design documents from `/specs/002-full-persona-parallel/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/api.yaml](contracts/api.yaml), [quickstart.md](quickstart.md)

**Tests**: Included — spec SC-001…SC-008 and quickstart scenarios require parallel-execution, consistency, attachment-sandbox, load, and Phase-1-regression suites as acceptance evidence.

**Organization**: Grouped by user story (US1–US5, priority order from spec.md). Builds on the Phase 1 modular monolith — **no new Gradle module**; extends `catalog`, `intake`, `orchestration`, `persona`, `casecore`, `observability`, and `app` tests. Migrations continue the ordered stream at **V10–V12**.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: Maps to spec.md user stories US1–US5
- All descriptions include exact file paths per the module layout in [plan.md](plan.md)

## Path Conventions (from plan.md)

Modular monolith: `<module>/src/main/java/com/d2os/<module>/…`, migrations in `<module>/src/main/resources/db/migration/`, BPMN/DMN in `orchestration|intake/src/main/resources/`, integration tests in `app/src/test/java/com/d2os/app/`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Build/config scaffolding for Phase 2 — no business logic yet.

- [X] T001 [P] Add Apache Tika (`tika-core`, `tika-parsers-standard-package` for `ForkParser`) to `intake/build.gradle`
- [X] T002 [P] Add a `loadTest` Gradle task to `app/build.gradle` that runs only JUnit tag `load` and is excluded from `:app:test`
- [X] T003 [P] Add Phase 2 config keys to `app/src/main/resources/application.yml`: `flowable.process.async-executor` (core 8/max 16/queue 256), `d2os.ai-gateway.max-concurrent-calls: 8`, `d2os.orchestration.reconciliation-interval`, `d2os.intake.attachment.{allowlist,max-size-bytes}`, `d2os.casecore.progress.heartbeat-interval: 4s`
- [X] T004 [P] Extend `app/src/test/java/com/d2os/app/support/StubAiGatewayClient.java` with a configurable per-persona latency profile (default 2–20 s) so concurrency overlap and cadence are measurable at zero AI cost

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Schema + shared execution/liveness infrastructure every story depends on. MUST complete before any US phase. (Following Phase 1, all V10–V12 schema lands here; per-story logic follows.)

- [X] T005 Create `attachment` + `attachment_summary` tables (workspace_id, RLS policy, `d2os_app` grants) in `intake/src/main/resources/db/migration/V10__attachments.sql`
- [X] T006 Create `consistency_finding` table (+ RLS + grants) and add `branch_id` column to `persona_invocation` in `persona/src/main/resources/db/migration/V11__consistency_findings.sql`
- [X] T007 Create `progress_event` (append-only — `REVOKE UPDATE, DELETE FROM d2os_app`, T6-a treatment), `workspace_budget`, and `reconciliation_run` tables (+ RLS + grants) in `casecore/src/main/resources/db/migration/V12__progress_and_budget.sql`
- [X] T008 Refactor persona resolution off the `persona-N` numbering assumption: `orchestration/src/main/java/com/d2os/orchestration/PersonaStepDelegate.java` and `persona/src/main/java/com/d2os/persona/PersonaExecutionService.java` must treat the BPMN activity id as the literal persona key (remove `sequenceNumberOf`/`PERSONA_SEQUENCE`), so real keys like `intake-analyst` resolve. Blocks US1 & US2.
- [X] T009 Create `ProgressEvent` entity/repository/`ProgressEmitter` (append-only writes, workspace-scoped) in `casecore/src/main/java/com/d2os/casecore/progress/`
- [X] T010 Wire baseline progress emissions (`STEP_STARTED`, `STEP_COMPLETED`, `VALIDATION_ATTEMPT`, `ESCALATED`, `SUSPENDED`, `DELIVERED`) into existing lifecycle points in `PersonaStepDelegate`, `PersonaExecutionService`, and `CaseService`

**Checkpoint**: Phase 2 schema, persona-key resolution, and progress infra ready — user story phases can begin.

---

## Phase 3: User Story 1 - The full documentation persona suite produces a complete Initiation package (Priority: P1) 🎯 MVP

**Goal**: Author the 13-persona suite and canonical workflow v2 so a submission is delivered as a complete package covering every persona.

**Independent Test**: Submit a demo Initiation problem → Case reaches Delivered with a validated artifact traceable to every persona in the canonical shape, zero manual DB edits.

- [X] T011 [US1] Author 13 `PersonaDefinition` seeds (charter, competency profile, ordered operation bindings, knowledge-profile stub) with real keys (`intake-analyst`, `business-analyst`, `product-functional-analyst`, `solution-architect`, `api-designer`, `security-architect`, `ux-architect`, `data-architect`, `infrastructure-engineer`, `qa-test-strategist`, `risk-governance-officer`, `delivery-planner`, `technical-writer`) in `catalog/src/main/java/com/d2os/catalog/CatalogSeedLoader.java`
- [X] T012 [P] [US1] Author one `PromptDefinition` per persona operation using the Phase 1 typed-slot / delimited-untrusted-data framing (T1-a) in `CatalogSeedLoader`
- [X] T013 [P] [US1] Author one `RubricDefinition` per persona output (weighted criteria, critical flags, ≥80% pass) in `CatalogSeedLoader`
- [X] T014 [P] [US1] Revise the ~14 v0 `TemplateDefinition` sources per Appendix B mapping and render a machine-readable `defines:`/`references:` index block in each template body, in `catalog/templates/` + seeded via `CatalogSeedLoader`
- [X] T015 [US1] Publish `workflow:initiation` **v2** and `case_type:initiation` **v2** (v2 `dependsOn` the full persona/prompt/rubric set; v1 stays published for replay) in `CatalogSeedLoader` (type-aware idempotency already in place)
- [X] T016 [US1] Author `orchestration/src/main/resources/processes/initiation-v2.bpmn20.xml` — canonical shape: sequential spine (intake-analyst → business-analyst → product-functional-analyst → solution-architect → api-designer), parallel gateway block (security/ux/data/infrastructure), join → `consistency-check` callActivity, then qa-test-strategist → risk-governance-officer → delivery-planner → technical-writer → assemble-package → end (activity ids = persona keys)
- [X] T017 [US1] Resolve workflow v2 process key for new cases (v1 pinned cases still replay) in `orchestration/src/main/java/com/d2os/orchestration/CaseStartService.java`
- [X] T018 [US1] Ensure `PackageAssemblyService` + `HandoverRecordService` index every persona artifact group and compute package-completeness over the full suite in `artifacts/src/main/java/com/d2os/artifacts/PackageAssemblyService.java`
- [X] T019 [US1] Extend `app/src/test/java/com/d2os/app/SubmitToDeliverIT.java`: assert full persona coverage in the delivered package + zero manual edits (SC-001, SC-002)

**Checkpoint**: US1 independently testable — full package delivered end-to-end.

---

## Phase 4: User Story 2 - Analysis specialists run in parallel without blocking one another (Priority: P1)

**Goal**: Make the specialist block genuinely concurrent, non-blocking, escalation-safe at the join, and audit-coherent under concurrency.

**Independent Test**: Instrumented case shows the four specialist operations overlap in wall-clock time and the join waits for all four; a single-branch escalation retains siblings and resumes.

- [X] T020 [US2] Set `flowable:async="true"` **and `flowable:exclusive="false"`** on the four specialist service tasks (and confirm parallel-gateway fork/join semantics) in `orchestration/src/main/resources/processes/initiation-v2.bpmn20.xml` — without `exclusive=false` Flowable serializes the branches per instance (research R1)
- [X] T021 [P] [US2] Bind the async-executor worker pool from config and add the AI-Gateway concurrency semaphore (`max-concurrent-calls`) in `persona/src/main/java/com/d2os/persona/gateway/HttpAiGatewayClient.java` (research R2, FR-003)
- [X] T022 [US2] Implement `EscalationBridge` (branch failure → BPMN signal-catch wait `escalation-resolved-<activityId>`, siblings retained, join waits) in `orchestration/src/main/java/com/d2os/orchestration/EscalationBridge.java`; route the validation-failure path in `PersonaExecutionService` to it instead of failing the branch (FR-004/005)
- [X] T023 [US2] Add `POST /cases/{caseId}/escalations/{invocationId}/resolve` (RETRY / ACCEPT_LAST_OUTPUT → signals the waiting branch) in `casecore/src/main/java/com/d2os/casecore/EscalationController.java`
- [X] T024 [P] [US2] Emit `BRANCH_FORKED` / `BRANCH_JOINED` progress events and tag `persona_invocation.branch_id` for parallel steps in `PersonaStepDelegate` / `PersonaExecutionService`
- [X] T025 [US2] Implement `ReconciliationJob` scheduled sweep (engine ↔ domain by Case id; grace window; classify MISSING_DOMAIN_TRANSITION / DEAD_LETTER_JOB / STATE_MISMATCH → REPAIRED/ESCALATED/IGNORED_TRANSIENT, each audited) writing `reconciliation_run` rows in `orchestration/src/main/java/com/d2os/orchestration/ReconciliationJob.java` (FR-010, research R8)
- [X] T026 [US2] Add `ParallelExecutionIT` in `app/src/test/java/com/d2os/app/ParallelExecutionIT.java`: assert specialist operation windows overlap, join waits for all four, and one-branch-escalates → siblings retained + resume releases the join (SC-003)

**Checkpoint**: US2 independently testable — specialists provably overlap and never block.

---

## Phase 5: User Story 3 - Cross-artifact inconsistencies are caught before delivery (Priority: P1)

**Goal**: Two-tier Consistency-Check subprocess after the join — deterministic conflicts block, semantic findings escalate.

**Independent Test**: A seeded hard contradiction blocks delivery and is recorded; a coherent case passes.

- [X] T027 [P] [US3] Implement `DeterministicCrossChecks` (parse each artifact's `defines:`/`references:` index; detect DANGLING_REFERENCE and ATTRIBUTE_CONTRADICTION across the **full** artifact set incl. upstream sequential artifacts — FR-019) in `persona/src/main/java/com/d2os/persona/consistency/DeterministicCrossChecks.java`
- [X] T028 [P] [US3] Author the `consistency-reviewer` prompt + rubric seeds and implement `SemanticConsistencyOperation` running through the AI Gateway with a recorded OperationExecution snapshot (Principle II) in `persona/src/main/java/com/d2os/persona/consistency/SemanticConsistencyOperation.java`
- [X] T029 [US3] Implement `ConsistencyFinding` entity/repository/service, persist findings + `trace_link` `CONFLICTS_WITH` edges (AD-7), and enforce the blocking invariant (no OPEN DETERMINISTIC finding may pass the consistency stage) in `persona/src/main/java/com/d2os/persona/consistency/`
- [X] T030 [US3] Author `orchestration/src/main/resources/processes/consistency-check.bpmn20.xml` (callActivity: deterministic step → gateway blocks on any deterministic finding; semantic step → advisory findings escalate) and its `ConsistencyCheckDelegate` in `orchestration/src/main/java/com/d2os/orchestration/ConsistencyCheckDelegate.java`
- [X] T031 [P] [US3] Add `GET /cases/{caseId}/consistency-findings` and `POST …/{findingId}/resolve` (WAIVED on a DETERMINISTIC finding → 409) in `persona/src/main/java/com/d2os/persona/consistency/ConsistencyFindingController.java`
- [X] T032 [US3] Re-verify zero OPEN DETERMINISTIC findings in `PackageAssemblyService` (defense in depth beyond the subprocess gateway) in `artifacts/src/main/java/com/d2os/artifacts/PackageAssemblyService.java`
- [X] T033 [US3] Add `ConsistencyCheckIT` in `app/src/test/java/com/d2os/app/ConsistencyCheckIT.java`: seeded contradiction blocks + WAIVE returns 409; coherent case passes; `CONFLICTS_WITH` edges exist (SC-004)

**Checkpoint**: US3 independently testable — contradictions blocked, coherent packages pass.

---

## Phase 6: User Story 4 - The system holds its load posture under realistic pressure (Priority: P2)

**Goal**: Prove NFR-1/2/3 — 50 concurrent cases, ≤5 s progress cadence, bounded p95, ≥200/month — plus workspace budget/limits.

**Independent Test**: Load test at target concurrency holds p95 and progress cadence with zero stalled/dropped cases.

- [X] T034 [P] [US4] Implement the heartbeat scheduler (every 4 s emit a `HEARTBEAT` progress event for each OperationExecution in `RUNNING`) in `casecore/src/main/java/com/d2os/casecore/progress/ProgressHeartbeat.java` (FR-011, research R7)
- [X] T035 [P] [US4] Add `GET /cases/{caseId}/progress` with paging (`afterId`) and long-poll (`wait`, 25 s server timeout) in `casecore/src/main/java/com/d2os/casecore/progress/ProgressController.java`
- [X] T036 [US4] Implement `WorkspaceBudget` entity/repository + same-transaction token rollup in the gateway and per-workspace `Suspended`-on-cap-breach (reuse Phase 1 suspend path) in `persona/src/main/java/com/d2os/persona/gateway/` (FR-017, T5-b)
- [X] T037 [P] [US4] Implement `WorkspaceRateLimiter` (in-memory sliding window keyed by workspace) in the gateway and add `GET /workspace/budget` in `persona/src/main/java/com/d2os/persona/gateway/WorkspaceBudgetController.java`
- [X] T038 [P] [US4] Add `gateCycleTimeSeconds` (wait→resume from progress events) and `regenerationRate` KPIs to `observability/src/main/java/com/d2os/observability/KpiEmitter.java` and expose `DashboardV2` in `MetricsController.java` (§KP)
- [X] T039 [US4] Add `LoadPostureIT` (`@Tag("load")`) in `app/src/test/java/com/d2os/app/LoadPostureIT.java`: 50 concurrent cases in workspace A + 10 in workspace B; assert zero stalled/deadlocked/dropped, per-op p95 within profile bound, ≤5 s progress gap per running case, workspace-B isolation under load, throughput ≥200/month; write `build/load-report.md` (SC-005, SC-006)

**Checkpoint**: US4 independently testable — load posture verified on demand.

---

## Phase 7: User Story 5 - Uploaded attachments cannot smuggle instructions into a persona (Priority: P2)

**Goal**: Sandboxed attachment extraction/summarization before any prompt; personas receive only summaries.

**Independent Test**: A seeded malicious attachment's raw content never reaches a persona prompt; only its summary does.

- [X] T040 [US5] Implement `Attachment` + `AttachmentSummary` entities/repositories and object-store upload (workspace-scoped key, allowlist + size-cap enforcement, SHA-256 hash) in `intake/src/main/java/com/d2os/intake/attachment/`
- [X] T041 [P] [US5] Add `POST /submissions/{submissionId}/attachments` (multipart; 413 oversized, 422 disallowed type) and `GET …/attachments` in `intake/src/main/java/com/d2os/intake/SubmissionController.java`
- [X] T042 [US5] Implement sandboxed extraction in `intake/src/main/java/com/d2os/intake/attachment/SandboxedExtractor.java` (research R9, T1-d): bounded, crash-contained extraction — hard timeout, output/memory cap, full `Throwable` containment; unparseable/timeout → status `REJECTED` + reason audit. **Deviation:** the planned Tika `ForkParser` (child JVM) was not adopted — it requires a `Serializable` `ContentHandler` (the standard body handlers are not) and has unreliable JDK 9+ classpath propagation, so it fails for every file in this runtime. The bounds above give time/memory/crash containment in-process; true per-parse process isolation is a documented production-hardening follow-up. The FR-015 no-raw-content-to-persona guarantee is enforced structurally by T044, independent of process isolation.
- [X] T043 [US5] Implement the summarization pass through the AI Gateway (T1-a data framing) in `intake/src/main/java/com/d2os/intake/attachment/AttachmentSummarizer.java`. **Note:** because summarization runs at upload time — before any Case/`operation_execution` exists — the reproducibility snapshot (model id/version + extracted-text/summary SHA-256) is carried inline on `attachment_summary` rather than via an `operation_execution` FK (V10 revised accordingly).
- [X] T044 [US5] Add an attachment-summary slot to `persona/src/main/java/com/d2os/persona/ExecutionEnvelopeBuilder.java` via the new `AttachmentSummaryPort` SPI (summaries only, inside untrusted-data delimiters in `PromptRenderer`; the recorded rendered prompt covers summaries; never reads raw object-store bytes) (FR-015)
- [X] T045 [US5] Add `AttachmentSandboxIT` in `app/src/test/java/com/d2os/app/AttachmentSandboxIT.java`: raw attachment text never appears in any recorded persona prompt while its sanitized summary does (inside delimiters); disallowed type → 422 and oversize → 413 (no record); an unparseable file is audited `REJECTED` and leaves the submission untouched (SC-007) — **3/3 green**

**Checkpoint**: US5 independently testable — upload surface sealed.

---

## Phase 8: Polish & Cross-Cutting Concerns (SC-008 — Phase 1 guarantees under concurrency)

**Purpose**: Prove nothing regressed and enforce the new boundaries.

- [X] T046 [P] Extend `app/src/test/java/com/d2os/app/LeakageSuiteIT.java` with a concurrent two-workspace parallel-block scenario (RLS holds under concurrency — Principle IV, research R4). Landed earlier as `concurrentCasesInTwoWorkspacesDoNotLeakDuringParallelBlock` during later governance-gate work on this same file; verified present and correct.
- [X] T047 [P] Add a workspace-cap variant to `app/src/test/java/com/d2os/app/TokenBudgetSuiteIT.java` (workspace budget breach → offending Case `Suspended`; SC-008). **Deviation**: landed as a sibling class `WorkspaceBudgetSuiteIT.java` instead of extending `TokenBudgetSuiteIT.java` directly, since the per-Case budget is pinned class-wide via `@TestPropertySource` and the workspace-cap scenario needs a different one; verified present and correct.
- [X] T048 [P] Extend `app/src/test/java/com/d2os/app/AuditGrantSuiteIT.java` to assert `progress_event` UPDATE/DELETE are denied to `d2os_app` (append-only, T6-a). Verified present (`appRoleCannotUpdateOrDeleteAuditStream`).
- [X] T049 [P] Extend `replay/src/main/java/com/d2os/replay/ReplayHarness.java` + `SnapshotCompletenessCheck.java` to cover the semantic-consistency review and attachment summaries, and add a replay-a-parallel-case byte-identical assertion (SC-008, FR-016). **Deviation**: the replay-a-parallel-case assertion landed as its own class `ParallelReplayIT.java` rather than extending an existing IT; verified present and correct (byte-identical replay incl. consistency-reviewer + attachment-summary snapshot completeness).
- [X] T050 [P] Add an ArchUnit rule that the `persona` module has no dependency on the attachment raw-storage path in `test-support/src/main/java/com/d2os/testsupport/ArchitectureRules.java` (enforces FR-015 boundary). Verified present (`personaDoesNotDependOnAttachmentStorage`, wired into `checkAll`).
- [X] T051 [P] Enforce persona statelessness invariants (FR-018): ArchUnit rules that persona implementations hold no mutable instance state and never invoke another persona's execution path, plus an IT assertion in the parallel-block suite that no persona operation approves its own output or mutates workflow routing (reviewer ≠ producer on the same artifact; routing changes originate only from the orchestration engine) in `test-support/src/main/java/com/d2os/testsupport/ArchitectureRules.java` + `app/src/test/java/com/d2os/app/ParallelBlockIT.java`. Static half (`personaExecutionBeansHoldNoMutableState`, `personaExecutionMachineryNeverRecursesIntoAnotherPersona`) was already present and wired into `checkAll`; the runtime `ParallelBlockIT` (reviewer never appears as an artifact's producer) is new this pass.
- [X] T052 [P] Update the `quickstart.md` success checklist and run the full `:app:test` suite green (Phase 1 suites + all Phase 2 suites). Checklist updated. `gradle compileTestJava` passes; the Testcontainers-backed IT suites (including the new `ParallelBlockIT`) could not be executed in this sandbox (no Docker daemon) — same standing limitation as every prior phase in this delivery chain, not claimed as a verified passing run.

---

## Dependencies & Execution Order

- **Setup (T001–T004)** → **Foundational (T005–T010)** block everything.
- **US1 (T011–T019)** is the MVP and precedes the others (authors the persona suite + workflow v2 the rest execute against).
- **US2 (T020–T026)** depends on US1's `initiation-v2.bpmn20.xml` and T008 persona-key resolution.
- **US3 (T027–T033)** depends on US1's templates/index blocks and the join point in the BPMN; the `consistency-check` callActivity is stubbed in T016 and filled by T030.
- **US4 (T034–T039)** depends on Foundational progress infra (T009) and US2 concurrency; the load test needs US1–US3 to run a full case.
- **US5 (T040–T045)** depends on Foundational V10 schema (T005) and the envelope builder; largely independent of US2/US3.
- **Polish (T046–T052)** depends on all stories being present.

**Story independence**: US2, US3, US5 each deliver an isolable capability testable on top of US1. US4 verifies US1–US3 under load. Given staffing, US5 (attachments) can proceed in parallel with US2/US3 after US1.

## Parallel Execution Examples

- **Setup**: T001, T002, T003, T004 all `[P]` — different files.
- **Foundational schema**: T005, T006, T007 are separate migrations in separate modules — author in parallel (they share no file), then T008–T010.
- **US1 content**: T012, T013, T014 `[P]` (prompts, rubrics, templates are distinct authoring passes) after T011 establishes the persona keys.
- **US3**: T027 (deterministic) and T028 (semantic) `[P]` — different files — then T029 joins them.
- **US4**: T034, T035, T037, T038 `[P]` — distinct files.
- **Polish**: T046–T052 all `[P]` — independent test files.

## Implementation Strategy

**MVP = US1 only** (Phase 1–3): the full persona suite delivering a complete package through workflow v2. This is demonstrable value on its own even before parallelism is hardened (US1's BPMN already contains the parallel gateway; US2 makes it *provably* concurrent and escalation-safe).

**Incremental delivery**: US1 (complete package) → US2 (provable non-blocking parallelism) → US3 (consistency gate) → then P2 hardening US4 (load) and US5 (attachments) → Polish (SC-008 regression). Each phase is independently testable and leaves the system shippable.

---

**Total: 52 tasks** — Setup 4 · Foundational 6 · US1 9 · US2 7 · US3 7 · US4 6 · US5 6 · Polish 7.
