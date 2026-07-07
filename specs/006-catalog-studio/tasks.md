# Tasks: Catalog Studio (Admin UI)

**Input**: Design documents from `/specs/006-catalog-studio/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/api.yaml](contracts/api.yaml), [quickstart.md](quickstart.md)

**Tests**: Included — spec SC-001…SC-008 and the quickstart's five IT suites (StudioAuthoringIT, PublishGovernanceIT, LifecycleToolingIT, CopyOnSubscribeIT, ResolutionBenchmarkIT) plus the re-run Phase 1–5 regression are the acceptance evidence.

**Organization**: Grouped by user story (US1–US5, priority order from spec.md). Builds on the Phase 1–5 modular monolith and **adds the new `studio` presentation module (the 13th module)** — all catalog semantics stay as API-testable services in `catalog`; `studio` only renders them (Thymeleaf + htmx, JS islands). **REQUIRES Phase 5 built**: the publish gates reuse the Phase 5 approval-gate subprocess / `GateInstance` (subject generalized in V22) rather than inventing studio-local review state — implementation is blocked until Phase 5 is merged. Migrations continue the ordered stream at **V21 (catalog: status CHECK swap + provenance columns + `library_subscription` table + GIN index) and V22 (governance: polymorphic gate subject)**.

**Stack note**: Server-rendered inside the monolith — Thymeleaf + htmx with two vendored JS islands (**dmn-js** decision-table editor, **diff2html** diff rendering); no SPA framework and no Node build chain (research R1). Static assets are vendored and version-pinned under `studio/src/main/resources/static/studio/`.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: Maps to spec.md user stories US1–US5
- All descriptions include exact file paths per the module layout in [plan.md](plan.md)

## Path Conventions (from plan.md)

Modular monolith: `<module>/src/main/java/com/d2os/<module>/…`, migrations in `<module>/src/main/resources/db/migration/`, studio pages in `studio/src/main/resources/templates/studio/`, vendored assets in `studio/src/main/resources/static/studio/`, BPMN/DMN in `orchestration|governance/src/main/resources/`, integration tests in `app/src/test/java/com/d2os/app/`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Stand up the new `studio` module, vendor its JS islands, and register config — no business logic yet.

- [ ] T001 [P] Create `studio/build.gradle` (Spring Boot web + Thymeleaf + htmx starter, depends on `catalog`, `governance`, `casecore`; no Node/JS build chain — research R1) and add `include 'studio'` to `settings.gradle`, wiring `studio` as the 13th module into `:app`
- [ ] T002 [P] Vendor and version-pin the JS islands under `studio/src/main/resources/static/studio/`: `dmn-js` (DMN decision-table editor, E6.1), `diff2html` (server-diff rendering, FR-005), and `htmx` (partial updates) — plain script-tag assets, no bundler (research R1)
- [ ] T003 [P] Add Phase 6 config keys to `app/src/main/resources/application.yml`: `d2os.studio.roles.catalog-owner` and `d2os.studio.roles.architecture-board` (role-gating per Q8), `d2os.catalog.gate.subject-type: DEFINITION_VERSION`, and `d2os.catalog.benchmark.seed-versions: 500` (NFR-9 seed target)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Schema (V21/V22) + shared draft-lifecycle and gate-subject infrastructure every story depends on. MUST complete before any US phase. **All V21/V22 schema lands here; per-story logic follows.**

- [ ] T004 Author `catalog/src/main/resources/db/migration/V21__studio_lifecycle_subscriptions.sql`: widen the `definition_asset` status CHECK to `('Draft','InReview','Published','Deprecated')` (adds `InReview` — research R2); add `derived_from_id uuid NULL REFERENCES definition_asset(id)` (fork provenance — research R4, FR-012) and `copied_from_id uuid NULL REFERENCES definition_asset(id)` (copy-on-subscribe provenance — research R6, FR-015); create the `library_subscription` table (`workspace_id`, `source_definition_id`, `copied_definition_id`, `subscribed_by`, `created_at`; UNIQUE `(workspace_id, source_definition_id)`) with RLS policy + `d2os_app` grants (research R6); add a GIN index (`jsonb_path_ops`) on `case_definition_snapshot.entries` for exact-pin containment (research R5, SC-005)
- [ ] T005 Author `governance/src/main/resources/db/migration/V22__gate_subject_polymorphic.sql`: add `subject_type text NOT NULL DEFAULT 'ARTIFACT_REVISION'` and `subject_id uuid NULL` to `gate_instance`, backfill `subject_id` from `subject_artifact_revision_id`, and keep the artifact column as a deprecated read alias (research R3, T4-b)
- [ ] T006 Generalize `GateService` to accept `('DEFINITION_VERSION', definition_asset.id)` gate subjects (open/decide against the polymorphic column) in `governance/src/main/java/com/d2os/governance/GateService.java` — the studio's D4 publish gate is an ordinary approval-gate instance (research R3). Blocks US2.
- [ ] T007 Implement `catalog/src/main/java/com/d2os/catalog/DraftService.java`: create/load/update `Draft` rows (edit refused unless status `Draft`), the `InReview` freeze (content edits refused while a review is open), and content-hash pinning at submission (tamper guard verified at publish) — research R2, FR-001. Blocks US1 & US2.

**Checkpoint**: V21/V22 schema, polymorphic gate subject, and draft-lifecycle service ready — user story phases can begin.

---

## Phase 3: User Story 1 - Authors create every definition type as a governed draft in the studio (Priority: P1) 🎯 MVP

**Goal**: All eight definition types authorable as unpublished draft candidates through type-appropriate editors (DMN table for rules; typed-slot editors for rubric/prompt), none resolvable by any running case until published.

**Independent Test**: In the studio create a draft of each of the eight types, author a rule via the DMN table editor and a prompt via the typed-slot editor, save, reopen, and confirm each draft is a persisted unpublished candidate that does not affect any running case.

- [ ] T008 [US1] Implement `DraftController` — CRUD-as-draft per definition type (`POST /catalog/drafts`, `GET/PUT /catalog/drafts/{draftId}`; 409 on `(type,key,version)` conflict; 409 on edit while InReview) over `DraftService` in `studio/src/main/java/com/d2os/studio/DraftController.java` (FR-001, US1)
- [ ] T009 [P] [US1] Implement typed-slot form models + server-side slot validation for the rubric and prompt editors (structured fields validated before save, not free-form blobs) in `studio/src/main/java/com/d2os/studio/editor/` (FR-003)
- [ ] T010 [P] [US1] Implement the dmn-js editor bridge (serialize/deserialize authored DMN XML to the Rule draft body; server round-trips the table content) in `studio/src/main/java/com/d2os/studio/editor/DmnEditorBridge.java` (FR-002)
- [ ] T011 [P] [US1] Author the Thymeleaf studio pages + htmx partials for the eight-type draft list and the per-type editors (embedding the dmn-js island for rules, typed-slot forms for rubric/prompt) in `studio/src/main/resources/templates/studio/` (research R1)
- [ ] T012 [US1] Add `StudioAuthoringIT` in `app/src/test/java/com/d2os/app/StudioAuthoringIT.java`: create a draft of each of the 8 types (Rule via DMN XML, Prompt/Rubric via typed slots — slot validation rejects malformed slots), reload restores full content, drafts are not resolvable by any case (resolution filters `Published`), and MockMvc/HtmlUnit smoke on the Thymeleaf editor routes (SC-001)

**Checkpoint**: US1 independently testable — all eight types authorable as drafts with type-appropriate editors, none affecting a running case.

---

## Phase 4: User Story 2 - Publishing a version passes a D4 governance review with prompt diffs rendered (Priority: P1)

**Goal**: Draft → InReview → Published through a real Phase 5 approval-gate instance, with prompt diffs as first-class review content, checksum + semver enforcement with conflict surfacing, and a second architecture-board gate for MAJOR bumps.

**Independent Test**: Advance a prompt draft that changes a published prompt to InReview, confirm the reviewer sees a rendered text diff against the prior version, approve through D4, publish with computed checksum + enforced semver; then attempt a MAJOR publish and confirm it is held until the architecture-board gate passes.

- [ ] T013 [US2] Implement submit-for-review (`POST /catalog/drafts/{draftId}/submit-review`): flip `Draft → InReview`, pin content hash, and open a Phase 5 approval-gate instance with subject `('DEFINITION_VERSION', id)` (Catalog Owner role per Q8/FR-009) in `studio/src/main/java/com/d2os/studio/PublishController.java` (FR-004, research R3)
- [ ] T014 [P] [US2] Produce the prompt/content DeltaReport as first-class review content — reuse the Phase 5 `DeltaReportService` to diff prompt text against the prior published version for Prompt/Persona; canonical-JSON content diff fallback for non-prompt types — attached as the gate's `inputs_ref` in `catalog/src/main/java/com/d2os/catalog/PublishService.java` (FR-005, T4-c)
- [ ] T015 [P] [US2] Render the DeltaReport via the diff2html island in the review Thymeleaf page (first-class review content, not a raw payload) in `studio/src/main/resources/templates/studio/review.html` (FR-005)
- [ ] T016 [US2] Implement `PublishService.publish` (`catalog/src/main/java/com/d2os/catalog/PublishService.java`): require D4 gate PASS, verify the pinned content hash, compute + record checksum, enforce semver ordering against the prior published `(type,key)` version, surface duplicate `(type,key,version)` / checksum / hash-tamper conflicts (never overwrite the immutable published row), and write the `Published` flip + AuditEntry in one transaction (publish is an audited event) — FR-006/008/017/018, SC-003
- [ ] T017 [US2] Add the MAJOR-version second gate: detect a MAJOR semver diff against the prior published version and chain a second architecture-board-role approval gate — publish requires BOTH PASS — in `PublishService` / `PublishController` (FR-007, SC-004, research R3)
- [ ] T018 [US2] Wire the publish endpoint (`POST /catalog/drafts/{draftId}/publish`, 409 on gate-not-passed / semver-checksum conflict / hash mismatch) in `studio/src/main/java/com/d2os/studio/PublishController.java` (FR-006, US2)
- [ ] T019 [US2] Add `PublishGovernanceIT` in `app/src/test/java/com/d2os/app/PublishGovernanceIT.java`: submit-review opens D4 + renders the prompt diff; edit-while-InReview → 409, publish-before-gate → 409; D4 APPROVE (Catalog Owner) → Published with checksum + V3 trigger lock + a publish AuditEntry in the audit trail (FR-017); duplicate `(type,key,version)` / unordered semver / changed content-hash each → 409; MAJOR draft opens two gates and stays blocked until the architecture-board gate passes; non-prompt type gets the canonical content-diff fallback (SC-002, SC-003, SC-004)

**Checkpoint**: US2 independently testable — publish is default-deny through the Phase 5 gate, diffs render, conflicts are surfaced, MAJOR requires the board.

---

## Phase 5: User Story 3 - Authors see deprecation impact, compatibility, and fork provenance (Priority: P2)

**Goal**: Lifecycle tooling — a computed deprecation impact report (no pinned dependents omitted), report-gated deprecation, fork-with-provenance, and a computed compatibility matrix.

**Independent Test**: Deprecate a version an active case pins and confirm the impact report lists that case; open the compatibility matrix and confirm cross-type version compatibility; fork a definition and confirm the new draft records `derived_from_id` to its source.

- [ ] T020 [P] [US3] Implement `DeprecationImpactService` (computed, not stored — research R5): (1) pinned active cases via `case_definition_snapshot.entries` exact-pin JSONB containment (GIN-indexed), (2) definition-graph dependents whose body references the key, (3) downstream `library_subscription` copies — in `catalog/src/main/java/com/d2os/catalog/DeprecationImpactService.java` (FR-010, SC-005)
- [ ] T021 [P] [US3] Implement `ForkService.fork` (copy the source body into a new `Draft` row, record `derived_from_id` = source id, and write an AuditEntry in the same transaction — fork is an audited event per FR-017; forking a Deprecated source allowed, result is an independent version, never a runtime override) in `catalog/src/main/java/com/d2os/catalog/ForkService.java` (FR-012/017, SC-008, research R4)
- [ ] T022 [P] [US3] Implement `CompatibilityMatrixService` (computed — evaluate `compatible_with` range declarations in definition bodies against published versions; flag out-of-range pins) in `catalog/src/main/java/com/d2os/catalog/CompatibilityMatrixService.java` (FR-011, research R5)
- [ ] T023 [US3] Add the deprecate-with-report-id endpoint (`GET /catalog/definitions/{definitionId}/deprecation-impact`; `POST …/deprecate` requiring a freshly generated `impactReportId` — 409 without one — status flip + AuditEntry in one transaction) and the compatibility-matrix endpoint (`GET /catalog/compatibility-matrix`) in `studio/src/main/java/com/d2os/studio/LifecycleController.java` (FR-010/011/017)
- [ ] T024 [US3] Add `LifecycleToolingIT` in `app/src/test/java/com/d2os/app/LifecycleToolingIT.java`: a case pinning X v1 appears in X v1's impact report (zero omissions) alongside definition dependents + subscription copies; deprecate without a report id → 409, with it → Deprecated + audit while the pinned case keeps executing; fork X sets `derived_from_id` and writes a fork AuditEntry (forking a Deprecated source yields an independent draft — FR-017); the matrix flags an out-of-range pin (SC-005, SC-008)

**Checkpoint**: US3 independently testable — impact reports are complete, deprecation is informed, forks carry provenance, the matrix flags incompatible pins.

---

## Phase 6: User Story 4 - Workspaces get their own copy of shared definitions on subscribe (Priority: P2)

**Goal**: Copy-on-subscribe from the Global library — a subscribing workspace receives its own provenance-tracked copy (not a live reference), insulated from later Global changes/deprecations.

**Independent Test**: Subscribe to a Global library version, confirm the workspace holds its own copy with a provenance link to the Global source, then deprecate the Global source and confirm the copy resolves unchanged.

- [ ] T025 [P] [US4] Implement `SubscriptionService.subscribe(sourceId)` — copy the Global (zero-UUID workspace) source into the caller's workspace as a new `Published` row (same key/version/body/checksum; checksum equality is the copy-integrity proof), set `copied_from_id`, and write the `library_subscription` row + AuditEntry in one transaction; resolution prefers the workspace copy; no propagation from Global (insulation by construction) — in `catalog/src/main/java/com/d2os/catalog/SubscriptionService.java` (FR-013/014/015, T4-d, research R6)
- [ ] T026 [US4] Add the library endpoints (`GET /library/definitions` — read-only Global browse with per-workspace subscription state; `POST /library/definitions/{definitionId}/subscribe` — 201 with `checksumVerified`, 409 if already subscribed) and the provenance display (copied-from `(key, version)`) in `studio/src/main/java/com/d2os/studio/SubscriptionController.java` (FR-013/015, US4)
- [ ] T027 [P] [US4] Author the Global-library browse + provenance Thymeleaf pages (read-only Global rows, subscribe action, copied-from source `(key,version)` display) in `studio/src/main/resources/templates/studio/library.html` (FR-015)
- [ ] T028 [US4] Add `CopyOnSubscribeIT` in `app/src/test/java/com/d2os/app/CopyOnSubscribeIT.java`: subscribe from workspace A → own copy with `copied_from_id` + checksum-equality (T4-d proof) + `library_subscription` recorded; deprecate the Global source → A's copy still resolves unchanged (insulation); re-subscribe same source → 409; workspace B cannot see A's copy (SC-006)

**Checkpoint**: US4 independently testable — subscription yields an insulated, provenance-carrying copy, never a live reference.

---

## Phase 7: User Story 5 - Pin resolution stays fast at catalog scale (Priority: P3)

**Goal**: Prove NFR-9 — `(key, version)` pin resolution ≤ 2 s with 500 seeded definition versions.

**Independent Test**: Seed 500 definition versions and benchmark `(key, version)` pin resolution, confirming p95 and worst case stay within the ≤ 2 s bound.

- [ ] T029 [US5] Implement the 500-version seed harness (500 published versions across the 8 types with a realistic key/version distribution, seeded through the real seed-loader path) in `app/src/test/java/com/d2os/app/support/BenchmarkSeeder.java` (research R7, NFR-9)
- [ ] T030 [US5] Add `ResolutionBenchmarkIT` (`@Tag("slow")`) in `app/src/test/java/com/d2os/app/ResolutionBenchmarkIT.java`: run 1 000 mixed `(type, key, version)` resolutions + case-start snapshot pinning at the seeded scale and assert p95 **and** worst case ≤ 2 s (regression tripwire) (SC-007, NFR-9, research R7)

**Checkpoint**: US5 independently testable — pin resolution stays within bound at seeded scale.

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: Prove nothing regressed and enforce the presentation-only boundary.

- [ ] T031 [P] Add an ArchUnit rule that `catalog` domain services (DraftService, PublishService, ForkService, DeprecationImpactService, CompatibilityMatrixService, SubscriptionService) are UI-agnostic — no dependency on the `studio` module (studio is presentation-only; all catalog semantics stay API-testable) — in `test-support/src/main/java/com/d2os/testsupport/ArchitectureRules.java`
- [ ] T032 [P] Re-run the Phase 1–5 suites unchanged (gates, knowledge, routing, replay, leakage, audit chain) green with the `studio` module active — confirm no regression from V21/V22 or the new module
- [ ] T033 [P] Update `specs/006-catalog-studio/quickstart.md` success checklist and run the full `:app:test` suite green (Phase 1–5 suites + the five new Phase 6 suites; `ResolutionBenchmarkIT` runs under the nightly slow tag)

---

## Dependencies & Execution Order

- ⚠️ **Phase 5 build dependency (blocking)**: implementation cannot begin until Phase 5 (Governance & Review Gates) is built and merged — T006/T013/T014/T017 reuse the Phase 5 approval-gate subprocess, `GateInstance`, and `DeltaReportService` at runtime. This phase may be *planned* against Phase 5's gate contracts, but not *implemented* against them until Phase 5 exists.
- **Setup (T001–T003)** → **Foundational (T004–T007)** block everything.
- **US1 (T008–T012)** is the MVP and precedes the others (authors the drafts the rest publish, deprecate, fork, and subscribe against); depends on `DraftService` (T007).
- **US2 (T013–T019)** depends on US1 drafts, the polymorphic gate subject (T005/T006), and the Phase 5 `DeltaReportService`.
- **US3 (T020–T024)** depends on the V21 provenance columns + GIN index (T004) and on published versions from US2; the impact report needs pinned cases to exist.
- **US4 (T025–T028)** depends on the V21 `copied_from_id` + `library_subscription` (T004) and published Global-library versions from US2.
- **US5 (T029–T030)** depends only on the Phase 1 resolution path + the seed harness; independent of US2–US4.
- **Polish (T031–T033)** depends on all stories being present.

**Story independence**: US3, US4, US5 each deliver an isolable capability testable on top of US1+US2's published definitions. Given staffing, US4 (subscribe) and US5 (benchmark) can proceed in parallel with US3 once US2 has published versions.

## Parallel Execution Examples

- **Setup**: T001, T002, T003 all `[P]` — different files (build/settings, static assets, config).
- **Foundational schema**: T004 (catalog) and T005 (governance) are separate migrations in separate modules — author in parallel, then T006–T007.
- **US1**: T009 (typed-slot models), T010 (dmn-js bridge), T011 (Thymeleaf pages) are `[P]` — distinct files — after T008 establishes the draft controller contract.
- **US2**: T014 (DeltaReport production) and T015 (diff2html rendering) are `[P]` — service vs. template.
- **US3**: T020 (impact), T021 (fork), T022 (matrix) all `[P]` — three independent services — then T023 wires their endpoints.
- **US4**: T025 (service) and T027 (pages) are `[P]` — different files.
- **Polish**: T031, T032, T033 all `[P]` — independent concerns.

## Implementation Strategy

**MVP = US1 only** (Phase 1–3): all eight definition types authorable as governed drafts through type-appropriate editors, none affecting a running case. This is demonstrable authoring value on its own even before publish governance is wired.

**Incremental delivery**: US1 (author all 8 types as drafts) → US2 (publish governance with prompt diffs, semver/checksum, MAJOR board) → then P2 lifecycle tooling US3 (impact/fork/matrix) and US4 (copy-on-subscribe) → P3 US5 (scale benchmark) → Polish (regression + presentation-only ArchUnit boundary). Each phase is independently testable and leaves the system shippable.

---

**Total: 33 tasks** — Setup 3 · Foundational 4 · US1 5 · US2 7 · US3 5 · US4 4 · US5 2 · Polish 3.
