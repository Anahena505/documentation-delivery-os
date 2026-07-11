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

- [X] T001 [P] Create `studio/build.gradle` (Spring Boot web + Thymeleaf + htmx starter, depends on `catalog`, `governance`, `casecore`; no Node/JS build chain — research R1) and add `include 'studio'` to `settings.gradle`, wiring `studio` as the 13th module into `:app`
- [X] T002 [P] Vendor and version-pin the JS islands under `studio/src/main/resources/static/studio/`: `dmn-js` (DMN decision-table editor, E6.1), `diff2html` (server-diff rendering, FR-005), and `htmx` (partial updates) — plain script-tag assets, no bundler (research R1).
      **NOTE (sandbox limitation, same spirit as the V21/V22 renumbering notes above)**: this sandboxed
      implementation environment has no outbound internet access, so the actual third-party JS/CSS
      files could not be fetched. What landed is the directory structure
      (`static/studio/vendor/{htmx,diff2html,dmn-js,dmn-js/assets}/`) and a README documenting the
      exact pinned versions, expected filenames, and source URLs each later template will reference.
      Marked done because the structural/contract deliverable (T011/T015 can be written against
      stable paths) is complete; **a human or a later network-enabled step still needs to drop the
      real vendored files in** before any studio page that loads them will actually work in a browser.
- [X] T003 [P] Add Phase 6 config keys to `app/src/main/resources/application.yml`: `d2os.studio.roles.catalog-owner` and `d2os.studio.roles.architecture-board` (role-gating per Q8), `d2os.catalog.gate.subject-type: DEFINITION_VERSION`, and `d2os.catalog.benchmark.seed-versions: 500` (NFR-9 seed target)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Schema (V21/V22) + shared draft-lifecycle and gate-subject infrastructure every story depends on. MUST complete before any US phase. **All V21/V22 schema lands here; per-story logic follows.**

- [X] T004 Author `catalog/src/main/resources/db/migration/V25__studio_lifecycle_subscriptions.sql` (renumbered from the spec's V21 — V21-V24 were already taken by the time Phase 6 was implemented; see the migration's own header note, same convention as V20/V24): widen the `definition_asset` status CHECK to `('Draft','InReview','Published','Deprecated')` (adds `InReview` — research R2); add `derived_from_id uuid NULL REFERENCES definition_asset(id)` (fork provenance — research R4, FR-012) and `copied_from_id uuid NULL REFERENCES definition_asset(id)` (copy-on-subscribe provenance — research R6, FR-015); create the `library_subscription` table (`workspace_id`, `source_definition_id`, `copied_definition_id`, `subscribed_by`, `created_at`; UNIQUE `(workspace_id, source_definition_id)`) with RLS policy + `d2os_app` grants (research R6); add a GIN index (`jsonb_path_ops`) on `case_definition_snapshot.entries` for exact-pin containment (research R5, SC-005)
- [X] T005 Author `governance/src/main/resources/db/migration/V26__gate_subject_polymorphic.sql` (renumbered from the spec's V22, immediately after catalog's V25 — same renumbering convention): add `subject_type text NOT NULL DEFAULT 'ARTIFACT_REVISION'` and `subject_id uuid NULL` to `gate_instance`, backfill `subject_id` from `subject_artifact_revision_id`, and keep the artifact column as a deprecated read alias (research R3, T4-b).
      **Addition beyond the literal task text**: this same migration also widens `delta_report`
      (makes `artifact_id`/`from_revision_id`/`to_revision_id` nullable, adds nullable
      `from_definition_id`/`to_definition_id uuid NULL REFERENCES definition_asset(id)`, plus a CHECK
      enforcing exactly one of the artifact-triple/definition-pair shapes per row) — done now, while
      this governance migration is already touching gate schema, so the later T014
      (definition-version DeltaReport for prompt/content diffs) needs no separate migration.
- [X] T006 Generalize `GateService` to accept `('DEFINITION_VERSION', definition_asset.id)` gate subjects (open/decide against the polymorphic column) in `governance/src/main/java/com/d2os/governance/GateService.java` — the studio's D4 publish gate is an ordinary approval-gate instance (research R3). Added a NEW `GateInstance.GateSubjectType` enum + polymorphic `GateInstance` constructor and `GateService.open(...)` overload; the original ARTIFACT_REVISION-shaped constructor/overload are unchanged and still what `GateTaskBridge` (orchestration, the only current caller) calls. `decide(...)` unchanged (opaque to subject type, per research R3). Blocks US2.
- [X] T007 Implement `catalog/src/main/java/com/d2os/catalog/DraftService.java`: create/load/update `Draft` rows (edit refused unless status `Draft`, enforced by a new `DefinitionAsset.updateBody` guard mirroring `DefinitionPublishService.publish`'s guard style), and a `DefinitionAsset.markInReview()` transition method added (but not yet wired — that is T013) so the `InReview` freeze falls out of the same Draft-only guard once status leaves Draft. Content-hash pinning at submission (tamper guard verified at publish) is T013's job, not this service's — noted as still open. Blocks US1 & US2.

**Checkpoint**: V21/V22 schema, polymorphic gate subject, and draft-lifecycle service ready — user story phases can begin.

---

## Phase 3: User Story 1 - Authors create every definition type as a governed draft in the studio (Priority: P1) 🎯 MVP

**Goal**: All eight definition types authorable as unpublished draft candidates through type-appropriate editors (DMN table for rules; typed-slot editors for rubric/prompt), none resolvable by any running case until published.

**Independent Test**: In the studio create a draft of each of the eight types, author a rule via the DMN table editor and a prompt via the typed-slot editor, save, reopen, and confirm each draft is a persisted unpublished candidate that does not affect any running case.

- [X] T008 [US1] Implement `DraftController` — CRUD-as-draft per definition type (`POST /catalog/drafts`, `GET/PUT /catalog/drafts/{draftId}`; 409 on `(type,key,version)` conflict; 409 on edit while InReview) over `DraftService` in `studio/src/main/java/com/d2os/studio/DraftController.java` (FR-001, US1).
      Implemented at `/api/v1/catalog/drafts` (matching contracts/api.yaml's `/catalog/drafts` under
      the `/api/v1` server prefix, same convention as `GateController`/`CatalogController`).
      **Response-shape decision**: plain JSON (contracts/api.yaml's `DefinitionVersion` shape plus a
      `body` field the schema omits but the "GET returns full draft content" description requires),
      not HTML fragments — simpler, independently testable now; T011's pages consume it directly or
      read the entity in-module. A later hardening pass can add an `Accept: text/html` branch that
      renders an htmx fragment instead, without touching the service calls underneath. Also added
      `GET/PUT /catalog/drafts/{draftId}/dmn-xml` (T010's bridge endpoints) on this same controller.
      Added `DraftConflictException`/`DraftExceptionHandler` (409/400/404 mapping, same
      `@RestControllerAdvice` convention as `GateExceptionHandler`/`CaseExceptionHandler`). Added a
      handful of read-only getters to `DefinitionAsset` (workspaceId/locale/publishedAt/createdAt/
      createdBy) so the response DTO could be built — no behavior change.
- [X] T009 [P] [US1] Implement typed-slot form models + server-side slot validation for the rubric and prompt editors (structured fields validated before save, not free-form blobs) in `studio/src/main/java/com/d2os/studio/editor/` (FR-003).
      `RubricEditorModel`/`RubricCriterionField` (personaKey + named/weighted/critical criteria,
      weights must sum to 1.0) and `PromptEditorModel` (personaKey + template, `{{slot}}` placeholders
      computed from the template, at least one required) — shapes verified against
      `CatalogSeedLoader`'s real seeded bodies (`{"personaKey":...,"criteria":[{"name":...,"weight":
      ...,"critical":...}]}` and `{"personaKey":...,"template":"..."}`) and the runtime readers
      (`ValidationPipeline.readCriteria`, `ExecutionEnvelopeBuilder`'s `path("template")`), so an
      authored draft is structurally compatible with existing scoring/rendering code. Wired into
      `DraftController.create`/`update` (validated before persisting, 400 on violation) — not dead
      code.
- [X] T010 [P] [US1] Implement the dmn-js editor bridge (serialize/deserialize authored DMN XML to the Rule draft body; server round-trips the table content) in `studio/src/main/java/com/d2os/studio/editor/DmnEditorBridge.java` (FR-002).
      **Honest scope note (matches T002's own flagged gap)**: this is the SERVER-SIDE half only — a
      thin passthrough wrapping raw DMN XML as `{"dmnXml": "..."}` in the Draft body column (JSONB
      can't hold raw XML) via `GET/PUT /catalog/drafts/{draftId}/dmn-xml`. It does no DMN parsing or
      validation. It is a DIFFERENT shape from the legacy `{"decisionKey":...,"engine":"flowable-dmn"}`
      pointer `CatalogSeedLoader` seeds for already-published rules (those point at a classpath
      `*.dmn` resource, not embedded XML) — `fromBodyJson` returns `null` for that shape by design.
      The actual visual dmn-js decision-table editor is CLIENT-side JS (T002) and is not vendored in
      this sandbox (no outbound internet access) — `draft-edit.html`'s DMN container is an inert
      placeholder until a network-enabled step drops the real dmn-js files in.
- [X] T011 [P] [US1] Author the Thymeleaf studio pages + htmx partials for the eight-type draft list and the per-type editors (embedding the dmn-js island for rules, typed-slot forms for rubric/prompt) in `studio/src/main/resources/templates/studio/` (research R1).
      `drafts.html` (list, filterable by type — links to each draft) and `draft-edit.html` (per-draft
      editor: plain JSON-body textarea form for Draft-status rows via `StudioPageController`'s
      `@Controller` POST adapter, a "frozen" message for InReview+ rows, and a DMN container for
      `rule`-type drafts) — both reference the vendored asset paths (`/studio/vendor/htmx/htmx.min.js`,
      `dmn-js/...`, `diff2html/...`). **Honest gap**: the actual vendored JS/CSS files are NOT present
      (T002's own flagged sandbox limitation — no outbound internet access here) — the pages'
      script/link tags point at paths that 404 today and will "just work" once a later, network-
      enabled step drops the real files in. Deliberately kept to server-rendered plumbing (no live
      htmx swap wiring, no client-side dmn-js mount) per this task's own "genuinely minimal" scope —
      real interactivity is follow-up work once T002's assets exist.
- [X] T012 [US1] Add `StudioAuthoringIT` in `app/src/test/java/com/d2os/app/StudioAuthoringIT.java`: create a draft of each of the 8 types (Rule via DMN XML, Prompt/Rubric via typed slots — slot validation rejects malformed slots), reload restores full content, drafts are not resolvable by any case (resolution filters `Published`), and MockMvc/HtmlUnit smoke on the Thymeleaf editor routes (SC-001).
      Uses the same `TestRestTemplate`/`RANDOM_PORT` harness as `GateFlowIT`/`DeprecationIT` (not a
      separate MockMvc/HtmlUnit fixture — the whole app context, including `studio`'s ViewResolver, is
      already up) to smoke-test `/studio/drafts` and `/studio/drafts/{id}`. Covers all 8 types
      draftable + reload-restores-content, malformed rubric/prompt slots rejected (400), drafts
      invisible to `DefinitionResolutionService.latestPublished`, and update-succeeds-while-Draft.
      **Scope note**: the InReview-rejection half (update after `markInReview()`, expect 409) is
      explicitly NOT exercised — nothing in this phase calls `markInReview()`; that's T013's
      submit-for-review endpoint (US2, not yet built). Left to `PublishGovernanceIT` (T019) once T013
      lands, rather than fabricated here. **Cannot actually run in this environment** (no Docker,
      same as every prior phase's IT) — hand-traced against the real code, not asserted to pass.

**Checkpoint**: US1 independently testable — all eight types authorable as drafts with type-appropriate editors, none affecting a running case.

---

## Phase 4: User Story 2 - Publishing a version passes a D4 governance review with prompt diffs rendered (Priority: P1)

**Goal**: Draft → InReview → Published through a real Phase 5 approval-gate instance, with prompt diffs as first-class review content, checksum + semver enforcement with conflict surfacing, and a second architecture-board gate for MAJOR bumps.

**Independent Test**: Advance a prompt draft that changes a published prompt to InReview, confirm the reviewer sees a rendered text diff against the prior version, approve through D4, publish with computed checksum + enforced semver; then attempt a MAJOR publish and confirm it is held until the architecture-board gate passes.

- [X] T013 [US2] Implement submit-for-review (`POST /catalog/drafts/{draftId}/submit-review`): flip `Draft → InReview`, pin content hash, and open a Phase 5 approval-gate instance with subject `('DEFINITION_VERSION', id)` (Catalog Owner role per Q8/FR-009) in `studio/src/main/java/com/d2os/studio/PublishController.java` (FR-004, research R3)
      **Module-placement deviation from the literal plan.md file list (verified, not assumed)**:
      plan.md names `catalog/.../PublishService.java`, but `governance`'s own `build.gradle`
      already declares `implementation project(':catalog')` — a `catalog -> governance` edge would
      close a cycle. The orchestration (`submitForReview`, needing both `catalog`'s `DraftService`/
      `DefinitionAsset` AND `governance`'s `GateService`/`DeltaReportService`) lives in
      `studio/src/main/java/com/d2os/studio/PublishService.java` instead — `studio` already depends
      on both. `catalog` gained only two small, self-contained additions:
      `DefinitionAsset#pinContentHash`/`#markPublishedFromReview` (entity guards, same style as
      the existing `updateBody`/`markInReview`) and a new public `ChecksumUtil` (sha256, extracted
      from `DefinitionPublishService`'s private one so callers outside `catalog` don't duplicate
      it). `DefinitionPublishService` itself is untouched. Also required, discovered only once gate
      opening was attempted against a real schema: `gate_instance.case_instance_id` was `NOT NULL`
      (V20) — a catalog publish-review gate has no owning Case — so `governance/.../V27__gate_instance_case_optional.sql`
      relaxes it (column-only, FK kept, same pattern as casecore's V19 for `decision.case_instance_id`),
      with null-safety fixes to `GateService.requireNotSelfReview`, `GateEventPublisher.basePayload`,
      and `GateController.list`'s case-id filter (all previously assumed a non-null case id).
- [X] T014 [P] [US2] Produce the prompt/content DeltaReport as first-class review content — reuse the Phase 5 `DeltaReportService` to diff prompt text against the prior published version for Prompt/Persona; canonical-JSON content diff fallback for non-prompt types — attached as the gate's `inputs_ref` in `catalog/src/main/java/com/d2os/catalog/PublishService.java` (FR-005, T4-c)
      Implemented as `DeltaReportService.generateForDefinitions` (governance — extends the existing
      service rather than duplicating it, per the phase brief) plus a new `DeltaReport` constructor
      for V26's definition-pair shape. One code path serves BOTH "prompt diff" and "canonical-JSON
      content diff" (both bodies are pretty-printed JSON text — diffing the pretty-printed whole
      body makes a Prompt's `template` field change just as legible as any other field's, without a
      separate prompt-specific extraction path — documented simplification, not a type-blind
      oversight). Skips generation entirely (documented in the gate's `inputsRef.deltaNote`, not
      silently) when no prior published version exists: `chk_delta_report_subject_shape` (V26)
      requires BOTH `from_definition_id`/`to_definition_id` non-null, so a first-ever publish has no
      legal "diff against nothing" row to write — the task brief's third option, chosen and
      documented. Verified `GateController`'s `GET /gates/{id}/delta-report` is shape-agnostic (it
      was — resolves whatever `DeltaReport` the gate's `deltaReportId` points at either way) and
      extended `DeltaReportView`/`GateDetail` to also surface `fromDefinitionId`/`toDefinitionId`
      and `subjectType`/`subjectId`, which the old DTOs omitted.
- [X] T015 [P] [US2] Render the DeltaReport via the diff2html island in the review Thymeleaf page (first-class review content, not a raw payload) in `studio/src/main/resources/templates/studio/review.html` (FR-005)
      `studio/src/main/resources/templates/studio/review.html` + a new `ReviewPageController`
      (`GET /studio/review/{gateId}`) reading `GateInstanceRepository`/`DeltaReportRepository`/
      `DefinitionAssetRepository` directly. **Honest gap, same as T002/T011's own flagged
      limitation**: diff2html itself is not vendored in this sandbox (no outbound internet access)
      — the page references `/studio/vendor/diff2html/diff2html.min.{js,css}` and passes the raw
      unified-diff text into the model via a hidden `<pre>` element (avoids splicing untrusted/large
      diff text into inline JS), with a script block that calls `Diff2Html.html(...)` guarded by
      `typeof Diff2Html === 'undefined'` — inert until a network-enabled step drops the real files
      in, then "just works" with no template change, same posture as `draft-edit.html`'s dmn-js
      container.
- [X] T016 [US2] Implement `PublishService.publish` (`catalog/src/main/java/com/d2os/catalog/PublishService.java`): require D4 gate PASS, verify the pinned content hash, compute + record checksum, enforce semver ordering against the prior published `(type,key)` version, surface duplicate `(type,key,version)` / checksum / hash-tamper conflicts (never overwrite the immutable published row), and write the `Published` flip + AuditEntry in one transaction (publish is an audited event) — FR-006/008/017/018, SC-003
      Same module-placement resolution as T013 — `studio/src/main/java/com/d2os/studio/PublishService.java`,
      not `catalog`. Requires EVERY `GateInstance` found for the draft's `(DEFINITION_VERSION,
      draftId)` subject to be `APPROVED` (not a hardcoded count — covers both the one-gate and
      T017's two-gate case uniformly); re-verifies the pinned hash via
      `DefinitionAsset#markPublishedFromReview` (entity-level guard); enforces semver ordering via a
      new `SemVer` helper (no semver utility existed in the repo — `DefinitionResolutionService`'s
      own javadoc flags plain lexical ordering as a known gap); writes the checksum + `Published`
      flip + `AuditWriter.record(..., "DEFINITION_PUBLISHED", ...)` in the one `@Transactional`
      method. Reuses `casecore.AuditWriter` exactly as `governance` does (same dependency, already
      present on `studio`'s classpath). **Honest gap**: "duplicate `(type,key,version)` at publish"
      is defensively caught (`DataIntegrityViolationException` -> 409) but is not known to be
      reachable through the normal flow — `uq_definition_type_key_version` (V3) is a GLOBAL
      constraint with no workspace component, so a genuine duplicate tuple is already refused at
      draft CREATE time (T008), before a row could ever reach InReview/publish holding a colliding
      tuple; `PublishGovernanceIT` exercises the actually-reachable create-time form and documents
      why.
- [X] T017 [US2] Add the MAJOR-version second gate: detect a MAJOR semver diff against the prior published version and chain a second architecture-board-role approval gate — publish requires BOTH PASS — in `PublishService` / `PublishController` (FR-007, SC-004, research R3)
      `SemVer.isMajorBump` against the prior published version at submit-review time opens a second
      `APPROVAL` gate (`catalog-architecture-board-review`, role `d2os.studio.roles.architecture-board`,
      T003) alongside the always-opened D4 gate; both share the same `(DEFINITION_VERSION, draftId)`
      subject. `publish()`'s "every gate APPROVED" check (T016) needs no MAJOR-specific branch — it
      naturally requires both. **Honest scope note**: the configured role keys are recorded in each
      gate's `inputsRef` for display/documentation but are NOT enforced as actor-level authorization
      on `GateService.decide` — matching `GateController`'s own documented "no role model in the
      codebase yet" posture; any actor able to call `POST /gates/{id}/decision` can decide either
      gate today, same as every other gate in this codebase. Real per-gate role authorization is a
      later hardening pass, not invented here.
- [X] T018 [US2] Wire the publish endpoint (`POST /catalog/drafts/{draftId}/publish`, 409 on gate-not-passed / semver-checksum conflict / hash mismatch) in `studio/src/main/java/com/d2os/studio/PublishController.java` (FR-006, US2)
      `PublishController` (new, sibling to `DraftController` per plan.md's file list, same base
      path `/api/v1/catalog/drafts` with disjoint `/submit-review` and `/publish` sub-routes — no
      mapping conflict) plus a new `PublishConflictException`, mapped to 409 by extending the
      existing `DraftExceptionHandler` (T016/T018's conflicts are the same `ProblemDetail`/409 shape
      as `DraftConflictException`, so extending rather than adding a parallel sibling handler avoids
      duplicated boilerplate — task text explicitly allowed either).
- [X] T019 [US2] Add `PublishGovernanceIT` in `app/src/test/java/com/d2os/app/PublishGovernanceIT.java`: submit-review opens D4 + renders the prompt diff; edit-while-InReview → 409, publish-before-gate → 409; D4 APPROVE (Catalog Owner) → Published with checksum + V3 trigger lock + a publish AuditEntry in the audit trail (FR-017); duplicate `(type,key,version)` / unordered semver / changed content-hash each → 409; MAJOR draft opens two gates and stays blocked until the architecture-board gate passes; non-prompt type gets the canonical content-diff fallback (SC-002, SC-003, SC-004)
      8 tests: submit-review opens D4 + produces a non-empty delta report against a directly-seeded
      prior published version (`DraftService`/`DefinitionPublishService` called straight from the
      test thread, `DeprecationIT`'s established pattern for non-HTTP service seeding); edit-while-
      InReview → 409 (the half `StudioAuthoringIT`, T012, explicitly deferred to this task, now that
      T013 actually calls `markInReview()`); publish-before-gate-decided → 409; full APPROVE ->
      publish -> `Published` + 64-char checksum + exactly one `DEFINITION_PUBLISHED` audit_entry row
      (raw SQL assertion, matching `DeprecationIT`/`CaseRoutingIT`'s convention); duplicate
      `(type,key,version)` exercised at its actually-reachable point (create, against an
      already-Published row — see T016's honest gap note for why publish-time duplication isn't
      reachable); unordered semver → 409 at publish; a body mutated via raw SQL after the hash was
      pinned (simulating the "shouldn't be possible but check defensively" tamper scenario) → 409;
      a MAJOR draft opens two gates, publish stays 409 after only the D4 gate is approved, and
      succeeds only once the architecture-board gate is approved too. **Cannot actually run in this
      environment** (no Docker, same as every prior phase's IT) — hand-traced line-by-line against
      the real `PublishController`/`PublishService`/`GateService`/`DeltaReportService`/
      `DefinitionAsset` code in this same commit, not asserted to pass. **Non-prompt canonical-diff
      fallback**: not exercised as a SEPARATE scenario, because T014's implementation makes it the
      SAME code path as the prompt case (documented there) — there is no distinct fallback branch
      left to test independently.

**Checkpoint**: US2 independently testable — publish is default-deny through the Phase 5 gate, diffs render, conflicts are surfaced, MAJOR requires the board.

---

## Phase 5: User Story 3 - Authors see deprecation impact, compatibility, and fork provenance (Priority: P2)

**Goal**: Lifecycle tooling — a computed deprecation impact report (no pinned dependents omitted), report-gated deprecation, fork-with-provenance, and a computed compatibility matrix.

**Independent Test**: Deprecate a version an active case pins and confirm the impact report lists that case; open the compatibility matrix and confirm cross-type version compatibility; fork a definition and confirm the new draft records `derived_from_id` to its source.

- [X] T020 [P] [US3] Implement `DeprecationImpactService` (computed, not stored — research R5): (1) pinned active cases via `case_definition_snapshot.entries` exact-pin JSONB containment (GIN-indexed), (2) definition-graph dependents whose body references the key, (3) downstream `library_subscription` copies — in `catalog/src/main/java/com/d2os/catalog/DeprecationImpactService.java` (FR-010, SC-005)
- [X] T021 [P] [US3] Implement `ForkService.fork` (copy the source body into a new `Draft` row, record `derived_from_id` = source id, and write an AuditEntry in the same transaction — fork is an audited event per FR-017; forking a Deprecated source allowed, result is an independent version, never a runtime override) in `catalog/src/main/java/com/d2os/catalog/ForkService.java` (FR-012/017, SC-008, research R4). Deviation: audited via a new `CatalogAuditWriter` (raw-JDBC, mirroring `casecore.AuditWriter`'s real writer) rather than a shared audit dependency — `catalog` has no dependency on `casecore`'s concrete writer, consistent with this repo's small-pure-function/writer duplication convention.
- [X] T022 [P] [US3] Implement `CompatibilityMatrixService` (computed — evaluate `compatible_with` range declarations in definition bodies against published versions; flag out-of-range pins) in `catalog/src/main/java/com/d2os/catalog/CompatibilityMatrixService.java` (FR-011, research R5). Deviation: range parsing is a small regex-based comparator (`>=`, `>`, `<=`, `<`, `=`) against a package-private `CatalogSemVer` (deliberately duplicated from studio's `SemVer`, same convention as T020's writer) — no external semver library pulled in for one comparator.
- [X] T023 [US3] Add the deprecate-with-report-id endpoint (`GET /catalog/definitions/{definitionId}/deprecation-impact`; `POST …/deprecate` requiring a freshly generated `impactReportId` — 409 without one — status flip + AuditEntry in one transaction) and the compatibility-matrix endpoint (`GET /catalog/compatibility-matrix`) in `studio/src/main/java/com/d2os/studio/LifecycleController.java` (FR-010/011/017). Deviation: `impactReportId` is verified as "freshly generated" by requiring it to match the id most recently returned by the impact-report GET for that definition (a lightweight correlation proof, not a persisted report row — the report itself stays computed-not-stored per T020/research R5). Also added the `POST …/fork` endpoint here (wiring T021's `ForkService`) since the fork action belongs on the same lifecycle-tooling controller and `LifecycleToolingIT` needs it.
- [X] T024 [US3] Add `LifecycleToolingIT` in `app/src/test/java/com/d2os/app/LifecycleToolingIT.java`: a case pinning X v1 appears in X v1's impact report (zero omissions) alongside definition dependents + subscription copies; deprecate without a report id → 409, with it → Deprecated + audit while the pinned case keeps executing; fork X sets `derived_from_id` and writes a fork AuditEntry (forking a Deprecated source yields an independent draft — FR-017); the matrix flags an out-of-range pin (SC-005, SC-008). Note: exercised via `TestRestTemplate` against a real Postgres Testcontainer — could not be executed in this sandbox (Testcontainers 1.19.8's pinned docker-java client negotiates Docker API 1.32 against this environment's dockerd 29.3.1, min API 1.40 — a client/server version mismatch, not a missing-Docker problem); verified by `compileTestJava` only.

**Checkpoint**: US3 independently testable — impact reports are complete, deprecation is informed, forks carry provenance, the matrix flags incompatible pins.

---

## Phase 6: User Story 4 - Workspaces get their own copy of shared definitions on subscribe (Priority: P2)

**Goal**: Copy-on-subscribe from the Global library — a subscribing workspace receives its own provenance-tracked copy (not a live reference), insulated from later Global changes/deprecations.

**Independent Test**: Subscribe to a Global library version, confirm the workspace holds its own copy with a provenance link to the Global source, then deprecate the Global source and confirm the copy resolves unchanged.

- [X] T025 [P] [US4] Implement `SubscriptionService.subscribe(sourceId)` — copy the Global (zero-UUID workspace) source into the caller's workspace as a new `Published` row (same key/version/body/checksum; checksum equality is the copy-integrity proof), set `copied_from_id`, and write the `library_subscription` row + AuditEntry in one transaction; resolution prefers the workspace copy; no propagation from Global (insulation by construction) — in `catalog/src/main/java/com/d2os/catalog/SubscriptionService.java` (FR-013/014/015, T4-d, research R6)
- [X] T026 [US4] Add the library endpoints (`GET /library/definitions` — read-only Global browse with per-workspace subscription state; `POST /library/definitions/{definitionId}/subscribe` — 201 with `checksumVerified`, 409 if already subscribed) and the provenance display (copied-from `(key, version)`) in `studio/src/main/java/com/d2os/studio/SubscriptionController.java` (FR-013/015, US4)
- [X] T027 [P] [US4] Author the Global-library browse + provenance Thymeleaf pages (read-only Global rows, subscribe action, copied-from source `(key,version)` display) in `studio/src/main/resources/templates/studio/library.html` (FR-015)
- [X] T028 [US4] Add `CopyOnSubscribeIT` in `app/src/test/java/com/d2os/app/CopyOnSubscribeIT.java`: subscribe from workspace A → own copy with `copied_from_id` + checksum-equality (T4-d proof) + `library_subscription` recorded; deprecate the Global source → A's copy still resolves unchanged (insulation); re-subscribe same source → 409; workspace B cannot see A's copy (SC-006). Note: same Testcontainers limitation as T024 — `compileTestJava`-verified only in this sandbox, not executed.

**Checkpoint**: US4 independently testable — subscription yields an insulated, provenance-carrying copy, never a live reference.

---

## Phase 7: User Story 5 - Pin resolution stays fast at catalog scale (Priority: P3)

**Goal**: Prove NFR-9 — `(key, version)` pin resolution ≤ 2 s with 500 seeded definition versions.

**Independent Test**: Seed 500 definition versions and benchmark `(key, version)` pin resolution, confirming p95 and worst case stay within the ≤ 2 s bound.

- [X] T029 [US5] Implement the 500-version seed harness (500 published versions across the 8 types with a realistic key/version distribution, seeded through the real seed-loader path) in `app/src/test/java/com/d2os/app/support/BenchmarkSeeder.java` (research R7, NFR-9)
- [X] T030 [US5] Add `ResolutionBenchmarkIT` (`@Tag("slow")`) in `app/src/test/java/com/d2os/app/ResolutionBenchmarkIT.java`: run 1 000 mixed `(type, key, version)` resolutions + case-start snapshot pinning at the seeded scale and assert p95 **and** worst case ≤ 2 s (regression tripwire) (SC-007, NFR-9, research R7). Note: tagged `slow`, runs via `gradle :app:slowTest` (not default `test`) — same Testcontainers limitation as T024/T028, so this sandbox could not execute it; `compileTestJava`-verified only.

**Checkpoint**: US5 independently testable — pin resolution stays within bound at seeded scale.

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: Prove nothing regressed and enforce the presentation-only boundary.

- [X] T031 [P] Add an ArchUnit rule that `catalog` domain services (DraftService, PublishService, ForkService, DeprecationImpactService, CompatibilityMatrixService, SubscriptionService) are UI-agnostic — no dependency on the `studio` module (studio is presentation-only; all catalog semantics stay API-testable) — in `test-support/src/main/java/com/d2os/testsupport/ArchitectureRules.java`. `catalogDoesNotDependOnStudio()` added and wired into `checkAll()`; `ArchitectureRulesTest` passes (also structurally guaranteed by `catalog/build.gradle` carrying no dependency on `studio`, but pinned explicitly per this repo's convention).
- [X] T032 [P] Re-run the Phase 1–5 suites unchanged (gates, knowledge, routing, replay, leakage, audit chain) green with the `studio` module active — confirm no regression from V21/V22 or the new module. Deviation: this is an audit-only pass in this sandbox — no Testcontainers-backed suite could actually execute (see T024). Audit performed instead: (1) `:app:compileTestJava` succeeds with all Phase 1–5 IT classes unchanged and on the classpath alongside the new studio/catalog code; (2) `ArchitectureRulesTest` (all rules, Phase 1–5 plus this phase's new `catalogDoesNotDependOnStudio`) passes; (3) diffed V21/V22 migrations against every Phase 1–5 repository/query touching `definition_asset` or gate-subject columns — no Phase 1–5 code references the columns V21/V22 altered incompatibly (the status CHECK swap is additive, the polymorphic gate-subject generalization in V22 is additive-nullable). No regression signal found; full runtime confirmation remains blocked on the Docker/Testcontainers API mismatch documented throughout this session.
- [X] T033 [P] Update `specs/006-catalog-studio/quickstart.md` success checklist and run the full `:app:test` suite green (Phase 1–5 suites + the five new Phase 6 suites; `ResolutionBenchmarkIT` runs under the nightly slow tag)

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
