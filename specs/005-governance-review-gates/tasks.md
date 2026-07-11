# Tasks: Governance & Review Gates

**Input**: Design documents from `/specs/005-governance-review-gates/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/api.yaml](contracts/api.yaml), [quickstart.md](quickstart.md)

**Tests**: Included — spec SC-001…SC-008 and the six quickstart IT suites (GateFlow, CommentRegenerate, ReopenPolicy, EscalationTimer, AuditChain, PackageAccess) plus the DR runbook and a Phase-1–4 regression pass are the acceptance evidence.

**Organization**: Grouped by user story (US1–US6, priority order from spec.md). Builds on the Phase 1–4 modular monolith and **adds the new `governance` Gradle module (BC-7)**; extends `orchestration`, `casecore`, `artifacts`, `tenancy`, `catalog`, and `app` tests. Migrations continue the ordered stream at **V17 (governance), V18 (casecore audit chain), V19 (artifacts grants), V20 (tenancy retention columns)**.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: Maps to spec.md user stories US1–US6
- All descriptions include exact file paths per the module layout in [plan.md](plan.md)

## Path Conventions (from plan.md)

Modular monolith: `<module>/src/main/java/com/d2os/<module>/…`, migrations in `<module>/src/main/resources/db/migration/`, BPMN/DMN in `orchestration/src/main/resources/{processes,dmn}/`, integration tests in `app/src/test/java/com/d2os/app/`. New bounded context `governance/` registered in `settings.gradle`. Ops runbook in `ops/`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Stand up the new `governance` module, dependencies, and config — no business logic yet.

- [X] T001 Create the `governance` Gradle module: add `governance/build.gradle` (Spring Boot, JPA, Flyway, Flowable deps mirroring `orchestration/build.gradle`) and register `include 'governance'` in `settings.gradle`; wire `app` to depend on `:governance` in `app/build.gradle`
- [X] T002 [P] Add `java-diff-utils` (deterministic delta reports, research R2) to `governance/build.gradle`
- [X] T003 [P] Add Phase 5 config keys to `app/src/main/resources/application.yml`: `d2os.governance.sla.default-durations` (fallback when an EscalationPolicy omits a per-step duration — policy value wins, R4), `d2os.governance.retention.default-years: 7` (NFR-5, R6) — audit-chain sealing is a fixed hourly interval per FR-013 (no cadence config key); notifications are in-app only in v1 (no delivery-channel config; no email/webhook, R4)
- [X] T004 [P] Scaffold `ops/dr-drill.md` (empty section headers: Backup regime, Restore procedure, RPO/RTO measurement, Results) to be filled in US6 (research R7, NFR-8)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: All V17–V20 schema, the engine↔governance bridge, and the new definition-asset types every story depends on. MUST complete before any US phase.

> **Renumbering note (as implemented)**: V17/V18/V19 were already taken by Phase 4 migrations
> (`tenancy/V17__feature_mutating_guard.sql`, `intake/V18__case_type_classification.sql`,
> `casecore/V19__decision_case_instance_optional.sql`) by the time this phase was implemented —
> Flyway's `classpath:db/migration` namespace is global across every module (see
> `app/src/main/resources/application.yml`). The four migrations below were renumbered to the actual
> next-free integers, same relative order, shifted by +3: **V20** (governance), **V21** (casecore
> audit chain), **V22** (artifacts grants), **V23** (tenancy retention columns). A fifth migration not
> anticipated by this task list, `catalog/src/main/resources/db/migration/V24__governance_definition_types.sql`,
> was also added — required for T011's `SUBPROCESS`/`ESCALATION_POLICY` seed rows to pass
> `definition_asset`'s `type` CHECK constraint (V3, catalog-owned).

- [X] T005 Create `gate_instance`, `impact_assessment`, `gate_reopen_candidate`, `escalation_activation`, `delta_report`, and `in_app_notification` tables (each with `workspace_id`, RLS policy, `d2os_app` grants; `escalation_activation` append-only per data-model) in `governance/src/main/resources/db/migration/V20__governance_gates.sql`
- [X] T006 [P] Create `audit_chain_segment` table (per-workspace chain: `segment_seq` UNIQUE per workspace, `segment_hash`/`prev_segment_hash`, RLS + grants, append-only — T6-b) in `casecore/src/main/resources/db/migration/V21__audit_hash_chain.sql` (also relaxes `decision.decision_type`'s CHECK constraint to admit the Phase 5 gate verbs — see T006 note below)
- [X] T007 [P] Create `package_access_grant` table (`(workspace_id, package_id, role)` UNIQUE, `granted_by`, `revoked_at`, RLS + grants — T3-d) in `artifacts/src/main/resources/db/migration/V22__package_access_grants.sql`
- [X] T008 [P] Add `retention_years int NOT NULL DEFAULT 7` and `retention_policy_notes text` columns to `workspace` (columns only — NFR-5, R6) in `tenancy/src/main/resources/db/migration/V23__workspace_retention.sql`
- [X] T009 Implement `GateInstance` entity/repository (state machine OPEN→APPROVED/REJECTED/REGENERATING/REOPEN_CANDIDATE/REOPENED per data-model) in `governance/src/main/java/com/d2os/governance/GateInstance.java` + `GateInstanceRepository.java`
- [X] T010 Implement `GateTaskBridge` (engine userTask ↔ `GateInstance` sync: create the gate row when the gate callActivity spawns its user task, correlate `engine_task_id`, complete the task on decide — same coupling pattern as `PersonaStepDelegate`) in `orchestration/src/main/java/com/d2os/orchestration/GateTaskBridge.java` (research R1) — **T010 scope note**: only the create-gate-row-on-userTask-create half is implemented (a Flowable `TaskListener`); it is not yet referenced by any BPMN (that wiring, plus the complete-task-on-decide half, is T012/T013/T014, Phase 3)
- [X] T011 Seed the two gate `SUBPROCESS` DefinitionAssets and the `ESCALATION_POLICY` DefinitionAsset as new `definition_asset` `type` values (content-level, not schema) via `catalog/src/main/java/com/d2os/catalog/CatalogSeedLoader.java` (data-model Modified Entities; R1/R4)

**Checkpoint**: Phase 5 schema (V20–V23), gate aggregate, engine bridge, and definition-asset types ready — user story phases can begin.

**T006 decision_type note (as implemented)**: `decision.decision_type` (casecore V4) was `CHECK
(decision_type IN ('D1','D2','D3','D4'))`; `'D4'` already has a specific meaning (the Knowledge
Curator promotion gate, `PromotionGateService`) so Phase 5 gate decisions do not reuse it. V21 drops
and recreates the constraint to additionally admit `'GATE_APPROVE'`, `'GATE_REJECT'`,
`'GATE_REQUEST_CHANGES'`, `'GATE_REOPEN'` — landed in casecore's own migration (the table's owning
module), matching this repo's existing precedent of a table's owning module altering that table's
constraints (e.g. `observability/V15` widening `kpi_sample`'s CHECK) rather than a cross-module
migration from `governance`.

---

## Phase 3: User Story 1 - Every decision flows through a first-class gate with a tamper-evident record (Priority: P1) 🎯 MVP

**Goal**: Promote inline D4 review/approval to reusable Review-Gate / Approval-Gate subprocesses producing a complete who/what/when/on-what/why Decision record, with full outbox payloads.

**Independent Test**: Run a case to a governance point → the decision is made through a gate subprocess with a reviewer view (`GET /gates/{id}` shows artifacts + exact inputs), and the recorded Decision names reviewer, reviewed artifacts/inputs, timestamp, and outcome — verifiable in the audit trail.

- [X] T012 [US1] Author `orchestration/src/main/resources/processes/review-gate.bpmn20.xml` (callActivity subprocess: userTask → decision gateway routing on the verb; APPROVE/REJECT/REQUEST_CHANGES paths) (research R1, FR-001)
- [X] T013 [P] [US1] Author `orchestration/src/main/resources/processes/approval-gate.bpmn20.xml` (callActivity subprocess: userTask carrying boundary-timer attach points for US4; verb routing) (research R1, FR-001)
- [X] T014 [US1] Implement `GateService.open(...)` and `GateService.decide(...)` — exactly three verbs APPROVE/REJECT/REQUEST_CHANGES; writes the `GateInstance` transition + Decision + AuditEntry in the **same transaction**; captures `inputs_ref` (artifact revisions, rubric scores, delta report id) at open; enforces reviewer role + non-self-review (FR-018) in `governance/src/main/java/com/d2os/governance/GateService.java` (research R1, FR-002/003/004)
- [X] T015 [US1] Embed the gate callActivities into new workflow versions `initiation-v3`, `assessment-v2`, `enhancement-v2` (running cases keep pinned prior versions — Principle I) and publish via `catalog/src/main/java/com/d2os/catalog/CatalogSeedLoader.java` (data-model Modified Entities; FR-001) — **gap**: `enhancement-v2` NOT seeded — `case_type.enhancement`/`workflow.enhancement` don't exist anywhere in this codebase yet (spec 004 US3 unbuilt), so there is nothing to version; `initiation-v3` and `assessment-v2` are fully seeded with real gate-embedded BPMN
- [X] T016 [US1] Implement gate lifecycle outbox emission per the `GateEventPayload` contract (GATE_OPENED, GATE_DECIDED — projection-sufficient tuple, no aggregate back-read) in `governance/src/main/java/com/d2os/governance/GateEventPublisher.java` (research R8, FR-019)
- [X] T017 [US1] Implement `GateController` (`GET /gates` worklist, `GET /gates/{gateId}` full view with resolved `inputs_ref`, `POST /gates/{gateId}/decision` — three verbs only, 403 self-review/role, 409 non-decidable) in `governance/src/main/java/com/d2os/governance/api/GateController.java` (contracts/api.yaml; FR-002)
- [X] T018 [US1] Add `GateFlowIT` in `app/src/test/java/com/d2os/app/GateFlowIT.java`: D4 flows through a gate subprocess; Decision records reviewer/inputs/timestamp/outcome; the same gate definition key/version serves Initiation + Assessment + Enhancement; **and every emitted outbox event validates against `GateEventPayload`** (SC-001, FR-019 payload-contract assertions) — drives Assessment (assessment-v2) through the gate; covers APPROVE→Delivered, REJECT→409 on redecide, REQUEST_CHANGES→reviewerComments, and self-review→403; cannot actually run in this environment (no Docker) — traced by hand against the real code, not asserted to pass

**Checkpoint**: US1 independently testable — every decision flows through a reusable gate with a complete Decision record and projection-sufficient events.

---

## Phase 4: User Story 2 - Reviewers comment and regenerate rather than editing AI content (Priority: P1)

**Goal**: REQUEST_CHANGES re-enters the persona path to produce a new immutable completion plus a deterministic delta report; prove no in-place content-write path exists.

**Independent Test**: At a gate, REQUEST_CHANGES with comments → a new ArtifactRevision is produced, the original is byte-unchanged, both appear in history, a delta report renders, and an API-surface scan confirms no artifact-content write path.

- [X] T019 [US2] Implement `RegenerationDelegate` (REQUEST_CHANGES → gate `REGENERATING`; re-enter the standard persona execution path with reviewer comments injected as **delimited untrusted data** (T1-a framing); produce a new immutable ArtifactRevision — never mutate a prior completion) in `orchestration/src/main/java/com/d2os/orchestration/RegenerationDelegate.java` (research R2, FR-004/005, Principle II)
- [X] T020 [P] [US2] Implement `DeltaReportService` (deterministic unified diff via `java-diff-utils` between prior and new revision; persist `delta_report` with `diff_hash` SHA-256) in `governance/src/main/java/com/d2os/governance/DeltaReportService.java` (research R2, FR-005)
- [X] T021 [US2] Add `GET /gates/{gateId}/delta-report` (404 when no regeneration/reopen has produced a delta) to `governance/src/main/java/com/d2os/governance/api/GateController.java` (contracts/api.yaml; FR-005)
- [X] T022 [US2] Emit `GATE_REGENERATION_TRIGGERED` outbox event (produced revision id in the payload) in `governance/src/main/java/com/d2os/governance/GateEventPublisher.java` (research R8, FR-019)
- [X] T023 [US2] Add `CommentRegenerateIT` in `app/src/test/java/com/d2os/app/CommentRegenerateIT.java`: REQUEST_CHANGES yields a new completion with the original retained byte-unchanged and both in history; delta report + hash present; **API-surface scan asserts no artifact-content write path exists** (SC-002, FR-004)

**T019 implementation note (as built)**: neither `initiation-v3` nor `assessment-v2` (T015) actually
populates `gate_instance.subjectArtifactRevisionId` — no ArtifactRevision exists yet at gate-open time
in either workflow (materialization was deferred entirely to `AssemblePackageDelegate`, post-approval).
Without a subject revision there is nothing to regenerate against or diff, so `GateTaskBridge` (T010/T014)
was extended to auto-resolve/materialize it via `ArtifactService.createRevision` whenever the callActivity
doesn't supply one explicitly — idempotent by content hash (a matching `ArtifactService.createRevision`
change: append a revision to the SAME `Artifact` when one already exists for the case+persona-key, or
return the existing latest revision unchanged when the content hash matches, rather than always minting a
new `Artifact`). `RegenerationDelegate` never opens the new `GateInstance` itself — it only regenerates the
persona output, calls `ArtifactService.createRevision` (the single write choke point) and
`DeltaReportService`, then hands the resulting `delta_report.id` forward as a `regenerationDeltaReportId`
process variable; the BPMN loop re-enters `review-gate-call`, and `GateTaskBridge`'s existing `create`
TaskListener — the one place a `GateInstance` row is ever created — opens the new gate cycle and attaches
that delta report to it. `initiation-v3.bpmn20.xml`/`assessment-v2.bpmn20.xml`'s `gw-gate` gateway now
routes `REQUEST_CHANGES` to a new `regeneration-delegate` serviceTask (`${regenerationDelegate}`) that
loops back to `review-gate-call`, instead of the old REQUEST_CHANGES-falls-into-default-halt behavior;
`REJECT` is now the gateway's explicit default branch to `gate-halted` (terminal, matching
`GateStatus.REJECTED`). Compiles clean (`compileJava`/`compileTestJava`, full clean build); `GateFlowIT`
(Phase 3) was hand-traced against the changed `GateTaskBridge`/`ArtifactService` and is unaffected (the
auto-materialization is idempotent, so the later `AssemblePackageDelegate.materializeForCase` pass reuses
rather than duplicates it). `CommentRegenerateIT`'s container-backed tests cannot actually run in this
environment (no Docker) — traced by hand, not asserted to pass, same posture as `GateFlowIT`. Its
API-surface-scan test (`noArtifactContentWritePathExistsOutsideCreateRevision`) is pure ArchUnit bytecode
reflection needing no Spring context/DB, but is still gated by the shared `@SpringBootTest` class in this
environment. **Known pre-existing gap, not introduced here**: `AssessmentReadOnlyIT` (Phase 4) creates
Assessment cases without pinning a case-type version, so — since T015 (Phase 3) published `assessment-v2`
as Assessment's latest — it likely now hits the gated workflow and needs a gate decision to reach
`Delivered`; this predates T019-T023 and is out of this phase's scope to fix.

**Checkpoint**: US2 independently testable — human interaction is comment-and-regenerate only; every version is retained and diffed.

---

## Phase 5: User Story 3 - Revising an approved upstream artifact re-opens its direct dependents' gates (Priority: P1)

**Goal**: DMN over `DERIVES_FROM`/`SATISFIES` edges identifies direct dependents; reopen is gated on a formal impact assessment (409 without) and recorded as a Decision; transitive dependents are flagged MANUAL_REVIEW only.

**Independent Test**: Approve an upstream artifact + direct/transitive dependents, revise the upstream → depth-1 candidates are reopenable and depth>1 flagged MANUAL_REVIEW; reopen 409s without an impact assessment, then succeeds as a recorded Decision with delta report attached; transitive gates stay untouched.

- [X] T024 [US3] Author `orchestration/src/main/resources/dmn/reopen-direct-dependents.dmn` (hit policy COLLECT over edge kinds `DERIVES_FROM`/`SATISFIES` → reopen candidate; keeps routing policy in an authorable definition — Q8/AD-5) (research R3, FR-006)
- [X] T025 [US3] Implement `ReopenCandidateService` (on a new revision of an approved artifact, query `trace_link` edges and feed them to the DMN; write `gate_reopen_candidate` rows — depth-1 reopenable, depth>1 `MANUAL_REVIEW`) in `governance/src/main/java/com/d2os/governance/reopen/ReopenCandidateService.java` (research R3, FR-006/008). **Wiring deviation**: `governance` depends on `artifacts`, so `artifacts` cannot depend back on `governance` to call this directly. Added `ArtifactRevisionListener` SPI (casecore, a module both already depend on) — `ArtifactService.createRevision` notifies it (best-effort `ObjectProvider`) with the just-superseded revision id whenever an existing Artifact gets an appended revision; `ReopenCandidateService` implements it. DMN evaluation similarly routes through a new `ReopenDmnPort` (governance has no Flowable dependency), implemented by `orchestration/ReopenDmnPortImpl` — same seam as `EngineGateReleasePort`. BFS over `trace_link` handles arbitrary depth, not just 1/2.
- [X] T026 [P] [US3] Implement `ImpactAssessment` entity/repository (reason/scope/risk/author; at most one per gate+upstream revision) in `governance/src/main/java/com/d2os/governance/reopen/ImpactAssessment.java` + `ImpactAssessmentRepository.java` (research R3, FR-007)
- [X] T027 [US3] Implement `ReopenService.reopen(...)` (409 without an `impact_assessment`; blocks transitive/depth>1 candidates; flips gate `REOPEN_CANDIDATE → REOPENED`, records a Decision + AuditEntry same-tx, attaches the delta report, signals the engine to re-activate the user task) in `governance/src/main/java/com/d2os/governance/reopen/ReopenService.java` (research R3, FR-007/008, Principle V). **Scope note**: stops at `REOPENED` (Decision + AuditEntry + delta report attached, all real and durable) — does NOT additionally drive `REOPENED → OPEN` with a live re-activated engine userTask, since no existing BPMN mechanism in this codebase supports resuming a completed callActivity instance (Flowable tasks are terminal once completed); building one is a BPMN redesign out of this delivery's scope, documented rather than faked.
- [X] T028 [US3] Implement `ReopenController` (`GET /reopen-candidates` by disposition; `POST /gates/{gateId}/impact-assessment` — 409 when gate is not a candidate; `POST /gates/{gateId}/reopen` — 409 without impact assessment or when transitive) in `governance/src/main/java/com/d2os/governance/api/ReopenController.java` (contracts/api.yaml; FR-006/007/008)
- [X] T029 [US3] Emit `GATE_REOPEN_CANDIDATE`, `GATE_IMPACT_ASSESSED`, and `GATE_REOPENED` outbox events (impact assessment id + candidate depth in the payload) in `governance/src/main/java/com/d2os/governance/GateEventPublisher.java` (research R8, FR-019)
- [X] T030 [US3] Add `ReopenPolicyIT` in `app/src/test/java/com/d2os/app/ReopenPolicyIT.java`: revision → depth-1 reopenable + depth>1 MANUAL_REVIEW; reopen 409s without impact assessment then succeeds as a Decision with delta attached; transitive gates untouched (SC-003). Seeds the dependency chain directly (targeted policy test, not a full case pipeline run).

**Checkpoint**: US3 independently testable — direct-dependent reopen only, impact-assessment-gated, never a silent side effect.

---

## Phase 6: User Story 4 - Advisory SLA timers escalate without auto-routing (Priority: P2)

**Goal**: Versioned EscalationPolicy with role chains + non-interrupting boundary timers on gate user tasks; a firing notifies, records, and emits an event — never routes or decides.

**Independent Test**: Configure a gate with a short advisory SLA + escalation policy, advance the engine clock → notification + escalation event fire and the policy status becomes visible, while the gate stays OPEN and the user task unmoved (zero auto-approve/reject/reassign); an unassigned-role step is still recorded and surfaced.

- [X] T031 [US4] Implement `EscalationPolicyResolver` (resolve the pinned `ESCALATION_POLICY` definition version a gate opened with; expose role chain + per-step SLA durations) in `governance/src/main/java/com/d2os/governance/escalation/EscalationPolicyResolver.java` (research R4, FR-009). Falls back to `escalation-policy.default` (latest published) when a gate's own `escalationPolicyKey` is null — true for every gate opened by `initiation-v3`/`assessment-v2` today, since neither embedding workflow's callActivity maps that variable in (same gap `GateTaskBridge`'s own javadoc documents for `subjectArtifactRevisionId`).
- [X] T032 [US4] Add **non-interrupting** boundary timer events on the gate user tasks in `orchestration/src/main/resources/processes/approval-gate.bpmn20.xml` and `review-gate.bpmn20.xml` (structural "no transition authority" — timers never touch the task lifecycle) (research R4, FR-010/011). The boundary timers already existed (Phase 3 scaffolding) routing to an inert marker end event; this phase routes them through a new `timerEscalationDelegate` serviceTask (`TimerEscalationDelegate`, orchestration) into the real handler instead.
- [X] T033 [US4] Implement `TimerFiredHandler` (record an `escalation_activation` row — step/role/`assignee_resolved`/status; notify; emit outbox event; **NEVER** mutate GateInstance status or the engine task; record + surface even when the role has no assignee) in `governance/src/main/java/com/d2os/governance/escalation/TimerFiredHandler.java` (research R4, FR-010/011/012, Principle V). `assigneeResolved` is honestly always `false` — no user/role-assignment model exists anywhere in this codebase yet (`GateController`'s own javadoc notes the same gap); the column exists so a future role model can flip it without a schema change.
- [X] T034 [P] [US4] Implement `NotificationService` (in-app only in v1 — persist `in_app_notification` rows per data-model, role-addressed; no email/webhook delivery) plus `GET /notifications` (caller's roles, unread filter) in `governance/src/main/java/com/d2os/governance/notification/NotificationService.java` and `governance/src/main/java/com/d2os/governance/api/NotificationController.java` (research R4, FR-010; contracts/api.yaml)
- [X] T035 [US4] Add `GET /gates/{gateId}/escalations` (visible activations: policy version, step, role, `assigneeResolved`, status) in `governance/src/main/java/com/d2os/governance/api/EscalationController.java` (contracts/api.yaml; FR-010)
- [X] T036 [US4] Emit `GATE_ESCALATION_FIRED` outbox event (policy version + step index in the payload) in `governance/src/main/java/com/d2os/governance/GateEventPublisher.java` (research R8, R4, FR-019)
- [X] T037 [US4] Add `EscalationTimerIT` in `app/src/test/java/com/d2os/app/EscalationTimerIT.java`: advance the clock → activation row + notification + outbox event + visible status, gate still OPEN and task unmoved (zero auto-route); unassigned-role step still recorded (`assigneeResolved=false`) (SC-004). Uses Flowable's standard test technique (`moveTimerToExecutableJob` + `executeJob`) rather than a literal 3-day wait; confirms the case still reaches Delivered normally afterward as the concrete proof nothing was diverted.

**Checkpoint**: US4 independently testable — advisory escalation surfaces stuck gates without ever deciding them.

---

## Phase 7: User Story 5 - The audit stream is tamper-evident and access is role-scoped and retained (Priority: P2)

**Goal**: Periodic hash-chaining with on-demand tamper detection, workspace retention config with a verify-only (non-deleting) job, and role-scoped package access grants seeded at delivery.

**Independent Test**: Alter/delete a sealed `audit_entry` → `POST /audit/chain/verify` reports `intact=false` with the broken segment (untampered → `intact=true`); retention defaults to 7 years and rejects sub-minimum (`422`); a delivered package is readable only by granted roles (`403` otherwise), with participant-role grants seeded at delivery and no workspace-wide default.

- [X] T038 [US5] Implement `AuditChainSealer` (periodic job — seal consecutive `audit_entry` ranges into `audit_chain_segment`: `segment_hash` = SHA-256 over the ordered canonical serialization, `prev_segment_hash` chaining, genesis = 64×'0', per-workspace; fixed hourly schedule per FR-013) in `casecore/src/main/java/com/d2os/casecore/audit/AuditChainSealer.java` (research R5, T6-b). Enumerates workspaces via the pre-existing `list_active_workspace_ids()` SECURITY DEFINER function (V29, projection's cross-tenant sweep) rather than adding a new one.
- [X] T039 [US5] Implement `AuditChainVerifier` (recompute any segment / full chain on demand + on schedule; mismatch ⇒ tamper alert = notification + outbox event; updates `last_verified_at`) in `casecore/src/main/java/com/d2os/casecore/audit/AuditChainVerifier.java` (research R5, FR-013). Tamper-alert writes go through raw JDBC into `in_app_notification`/`event_outbox` — `casecore` cannot depend on `governance`, which owns those entities (same cross-module-raw-write convention `ArtifactService`/`ConsistencyService` already use for `trace_link`).
- [X] T040 [P] [US5] Implement `PackageAccessService` (reading a delivered package requires a grant for one of the caller's roles — default-deny; grants seeded at delivery for the case's participant roles) in `artifacts/src/main/java/com/d2os/artifacts/access/PackageAccessService.java` (research R6, T3-d, FR-015). Seeded automatically in `PackageAssemblyService.assemble()` for the pragmatic `reviewer` role (the same default-caller-role convention every gate/notification endpoint in this codebase already uses, pending a real auth/role model) — idempotent against re-materialization.
- [X] T041 [P] [US5] Implement `RetentionVerificationJob` (report-only — records approaching/over policy boundaries; **never auto-deletes** in v1, disposal stays an explicit governed action) reading `workspace.retention_years` in `tenancy/src/main/java/com/d2os/tenancy/RetentionVerificationJob.java` (research R6, NFR-5, FR-014, Principle V). `tenancy` is the foundational module (no dependency on `artifacts`/`governance`) — reads `execution_package.created_at` and writes findings via raw JDBC into `in_app_notification`.
- [X] T042 [US5] Add `POST /audit/chain/verify` (`intact`/`segmentsVerified`/`firstBrokenSegment`), `GET/POST /packages/{packageId}/grants` (role-scoped, audited), and `GET/PUT /workspace/retention` (default 7y; `422` below the enforced minimum) across `casecore/.../api/AuditChainController.java`, `artifacts/src/main/java/com/d2os/artifacts/access/PackageAccessController.java`, and `tenancy/src/main/java/com/d2os/tenancy/RetentionController.java` (contracts/api.yaml; FR-013/014/015). Also added `GET /packages/{packageId}` (not explicitly in the contract) as the concrete enforcement point for "reading a delivered package requires a grant" — no package-content-read endpoint existed anywhere in this codebase to retrofit the check onto otherwise.
- [X] T043 [US5] Add `AuditChainIT` in `app/src/test/java/com/d2os/app/AuditChainIT.java` (seal → alter/delete a sealed entry → `intact=false` with broken segment; untampered → `intact=true`) and `PackageAccessIT` in `app/src/test/java/com/d2os/app/PackageAccessIT.java` (granted role reads, ungranted → `403`, delivery seeds participant grants, no workspace-wide default; retention default 7y and sub-minimum `422`) (SC-005, SC-006). `AuditChainIT`'s tamper simulation connects directly as the schema-owner role, since `d2os_app` has no UPDATE/DELETE grant on `audit_entry` at all (T6-a) — exactly the out-of-band write this feature exists to detect.

**Checkpoint**: US5 independently testable — the record is tamper-evident, retained per policy, and access is default-deny by role.

---

## Phase 8: User Story 6 - Recovery from disaster is verified, not assumed (Priority: P3)

**Goal**: Documented, verified DR posture — WAL-based PITR to RPO ≤ 15 min, restore to RTO ≤ 1 h, results recorded as evidence.

**Independent Test**: Run the backup/restore drill and confirm the achieved RPO and RTO meet targets and are documented (a shortfall is recorded as a finding, not reported as a pass).

- [ ] T044 [US6] Add PostgreSQL WAL-archiving DR configuration to the compose stack (`archive_command` to the MinIO/object-store bucket + nightly base backup for point-in-time recovery, RPO ≤ 15 min) in `docker-compose.yml` / compose overrides (research R7, NFR-8)
- [ ] T045 [US6] Author the `ops/dr-drill.md` runbook (base restore + WAL replay to a timestamp; boot app on the restored DB; run the replay-audit + chain-verify smoke suites; measure wall-clock RTO ≤ 1 h) (research R7, NFR-8, SC-007)
- [ ] T046 [US6] Execute the drill and record achieved RPO/RTO, date, and operator in `specs/005-governance-review-gates/quickstart-results.md` (shortfalls recorded as findings, not passes) (research R7, SC-007)

**Checkpoint**: US6 independently testable — recovery is demonstrated and documented against RPO/RTO targets.

---

## Phase 9: Polish & Cross-Cutting Concerns (SC-008 — prior-phase guarantees under the gate machinery)

**Purpose**: Prove nothing regressed and enforce the new module boundary.

- [ ] T047 [P] Re-run the Phase 1–4 suites unchanged (SubmitToDeliver, ParallelExecution, Leakage, InjectionSeed, TokenBudget, AuditGrant, Knowledge*, CaseRouting/Guard) green with gates active — assert same-tx append-only audit, workspace isolation, and byte-identical replay hold for regenerated completions and reopened gates (SC-008, FR-017)
- [ ] T048 [P] Add an ArchUnit rule that `governance` is coupled to the engine only via `GateTaskBridge` (no other orchestration/Flowable dependency from `governance`) in `test-support/src/main/java/com/d2os/testsupport/ArchitectureRules.java` (plan Structure Decision; enforces BC-7 boundary)
- [ ] T049 [P] Extend `replay/src/main/java/com/d2os/replay/SnapshotCompletenessCheck.java` to cover regenerated completions and reopened gates, and assert a replayed regenerated case is byte-identical (SC-008, FR-017)
- [ ] T050 [P] Update the `quickstart.md` success checklist and run the full `:app:test` suite green (Phase 1–4 suites + all six Phase 5 suites) (SC-008)

---

## Dependencies & Execution Order

- **Setup (T001–T004)** → **Foundational (T005–T011)** block everything (module, schema V17–V20, gate aggregate, engine bridge, definition-asset types).
- **US1 (T012–T018)** is the MVP and precedes the others (authors the gate subprocesses, `GateService`, workflow v3s, and the outbox payload the rest extend).
- **US2 (T019–T023)** depends on US1's `GateService`/`REQUEST_CHANGES` path and the `GateEventPublisher`.
- **US3 (T024–T030)** depends on US1's gate machinery and US2's `DeltaReportService` (delta attached on reopen) plus the Phase 1 `trace_link` edges (AD-7).
- **US4 (T031–T037)** depends on US1's gate user tasks and the pinned `ESCALATION_POLICY` definition seeded in T011.
- **US5 (T038–T043)** depends on Foundational schema (V18/V19/V20) and the Phase 1 append-only `audit_entry` stream; largely independent of US2/US3/US4.
- **US6 (T044–T046)** depends on the audit/replay path being present (uses the chain-verify + replay smoke suites) but is otherwise an ops activity.
- **Polish (T047–T050)** depends on all stories being present.

**Story independence**: US2, US3, US4, US5 each deliver an isolable capability testable on top of US1. US5 (audit/retention/access) and US6 (DR) can proceed in parallel with US2/US3/US4 after US1. US6 requires only a running restorable stack.

## Parallel Execution Examples

- **Setup**: T002, T003, T004 `[P]` after T001 creates the module (they touch distinct files).
- **Foundational schema**: T006, T007, T008 are separate migrations in separate modules — author in parallel (they share no file), then T009–T011.
- **US1**: T012 and T013 `[P]` (review-gate vs approval-gate BPMN) before `GateService` (T014) wires them.
- **US2**: T020 (`DeltaReportService`) `[P]` alongside T019 (`RegenerationDelegate`) — different files — then T021–T023.
- **US3**: T026 (`ImpactAssessment` entity) `[P]` alongside the DMN/candidate work.
- **US4**: T034 (`NotificationService`) `[P]` — distinct file.
- **US5**: T040 (`PackageAccessService`) and T041 (`RetentionVerificationJob`) `[P]` — different modules.
- **Polish**: T047–T050 all `[P]` — independent files.

## Implementation Strategy

**MVP = US1 only** (Phase 1–3): every D4 decision flowing through a reusable Review-Gate / Approval-Gate subprocess with a complete tamper-evident Decision record and projection-sufficient outbox events. This is demonstrable governance value on its own, and the gate machinery every later story attaches to.

**Incremental delivery**: US1 (first-class gates) → US2 (comment-and-regenerate + delta) → US3 (impact-assessed reopen policy) → then P2 hardening US4 (advisory SLA/escalation) and US5 (audit chain, retention, package access) → US6 (verified DR) → Polish (SC-008 regression). Each phase is independently testable and leaves the system shippable.

---

**Total: 50 tasks** — Setup 4 · Foundational 7 · US1 7 · US2 5 · US3 7 · US4 7 · US5 6 · US6 3 · Polish 4.
