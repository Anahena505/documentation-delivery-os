# Implementation Plan: Catalog Studio (Admin UI)

**Branch**: `006-catalog-studio` | **Date**: 2026-07-07 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/006-catalog-studio/spec.md`

## Summary

Phase 6 builds the first real UI of the platform: a server-rendered **Catalog Studio** (new
`studio` module — Thymeleaf + htmx, with JS islands for the dmn-js decision-table editor and
diff2html prompt-diff rendering; no SPA build chain) over the Phase 1 definition core and the
Phase 5 gate machinery. Authors create **drafts of all eight definition types** (typed-slot
editors for rubric/prompt, DMN table editor for rules); the publish lifecycle becomes
**Draft → InReview → Published** (V21 adds `InReview` to the status check) where publish runs
through a real Phase 5 **approval-gate instance** (generalized to polymorphic gate subjects, V22)
with prompt diffs as first-class review content and a second **architecture-board gate** for
MAJOR bumps. Lifecycle tooling: fork-with-provenance (`derived_from_id`), a deprecation **impact
report** (pinned active cases via `case_definition_snapshot`, dependents via edges, downstream
copies via subscriptions), and a computed compatibility matrix. **Copy-on-subscribe** (T4-d)
copies a Global-library version into the workspace as its own provenance-tracked row
(`copied_from_id` + `library_subscription`), insulating subscribers from later Global changes.
NFR-9 is proven by a 500-version seeded benchmark: `(key, version)` pin resolution ≤ 2 s.

## Technical Context

**Language/Version**: Java 21 (Gradle toolchain; build runs via Docker `gradle:8.10-jdk21`)

**Primary Dependencies**: Spring Boot 3.3.5, Thymeleaf + htmx (server-rendered studio; no JS
build chain), dmn-js (embedded DMN decision-table editor, static asset), diff2html + java-diff-utils
(prompt/content diffs — reusing Phase 5's DeltaReportService), Flowable 7.0.1 (publish gates run
the Phase 5 approval-gate subprocess), Spring Data JPA, Flyway

**Storage**: PostgreSQL 16 (RLS-enforced); V21 catalog changes (status CHECK swap + provenance
columns + `library_subscription` table), V22 governance generalization (polymorphic gate subject)

**Testing**: JUnit 5 + Testcontainers; existing suites re-run unchanged; new StudioAuthoringIT,
PublishGovernanceIT, LifecycleToolingIT (impact report/fork/matrix), CopyOnSubscribeIT (T4
supply-chain suite), ResolutionBenchmarkIT (NFR-9, tagged slow); MockMvc/HtmlUnit for studio
page flows

**Target Platform**: Linux server (single-region cloud), local dev via docker-compose

**Project Type**: Modular-monolith web service (adds a 13th module: `studio` — UI layer over
catalog + governance)

**Performance Goals**: `(key, version)` pin resolution ≤ 2 s at 500 seeded definition versions
(NFR-9 — expected orders of magnitude better via the existing unique index; the benchmark guards
regression); studio pages are server-rendered CRUD (no special targets beyond snappy-admin)

**Constraints**: Published versions immutable — the studio edits drafts only (the V3 DB trigger
stays the last line of defense); publish requires a passing D4 gate (Phase 5 GateInstance), MAJOR
additionally the architecture-board gate; every publish/deprecate/fork/subscribe is an audited
governance action; copy-on-subscribe copies rows, never live references (T4-d); Catalog Owner is
the single accountable publish owner (Q8) — role-enforced

**Scale/Scope**: 1 new module, 1 new table + column additions (V21) + gate-subject generalization
(V22), ~10 studio page flows, ~8 new API endpoints, 5 new IT suites, 500-version benchmark seed

## Constitution Check

*GATE: evaluated against Constitution v1.0.0 before Phase 0 research; re-checked after Phase 1 design.*

| # | Principle | Status | How this plan complies |
|---|---|---|---|
| I | Definition/Instance Immutability | ✅ PASS | The studio creates/edits **Draft** rows only; `InReview` freezes the draft under review; Published stays immutable (V3 trigger untouched and still enforcing). Semver + checksum enforcement at publish is the Phase 1 primitive, now surfaced with conflict UX (FR-006/018). Forks are new independent versions with `derived_from_id` provenance — runtime override remains structurally absent. |
| II | Reproducible, Bounded AI (NON-NEGOTIABLE) | ✅ PASS | No AI executes in this phase's machinery (authoring tooling). Prompt diffs are deterministic text diffs of stored versions. The phase *strengthens* II downstream: prompt changes become human-reviewed, versioned gate subjects before any persona can run them. |
| III | System of Record Integrity | ✅ PASS | Drafts, reviews, subscriptions, and gate links are relational rows + AuditEntry in the same transaction; the compatibility matrix and impact report are **computed views** over existing pins/edges/subscriptions — nothing derived is stored as truth. |
| IV | Workspace Isolation & Provenance | ✅ PASS | Copy-on-subscribe is Principle IV verbatim: the Global library (zero-UUID workspace) distributes by **copy with provenance** (`copied_from_id`, `library_subscription`), so a workspace's snapshot never changes underneath it. Studio pages and APIs are workspace-scoped; the Global library is read-only to workspaces. |
| V | Default-Deny Security & Auditable Gates | ✅ PASS | Publish is default-deny: no path to Published except through a passing D4 approval-gate instance (+ architecture board for MAJOR), each a recorded Decision. One deliberate carve-out: copy-on-subscribe (T025) creates a workspace `Published` row **without a new gate** because the Global source already passed its gate and checksum equality proves the copy is byte-identical — the subscribe itself is an audited event. Deprecation requires the impact report before confirmation (informed, audited act). Studio actions are role-gated (Catalog Owner per Q8). |

**Post-design re-check (after data-model + contracts)**: no violations introduced — V21 widens a
CHECK and adds nullable provenance columns (no mutation of published content); V22's polymorphic
gate subject backfills losslessly. **GATE: PASS.**

## Project Structure

### Documentation (this feature)

```text
specs/006-catalog-studio/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── api.yaml         # Phase 1 output (delta over Phase 5 api.yaml)
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created by /speckit-plan)
```

### Source Code (repository root)

New 13th module `studio` (UI layer); column/table work in catalog; gate-subject generalization in
governance:

```text
studio/                        # NEW MODULE: Catalog Studio UI (server-rendered)
├── src/main/java/com/d2os/studio/
│   ├── DraftController.java           # CRUD-as-draft per definition type (E6.1)
│   ├── editor/                        # typed-slot form models for rubric/prompt; DMN editor bridge
│   ├── PublishController.java         # submit-for-review, gate status, publish (E6.2)
│   ├── LifecycleController.java       # deprecation impact report, fork, compatibility matrix (E6.3)
│   ├── SubscriptionController.java    # Global library browse + copy-on-subscribe (E6.4)
│   └── ...
├── src/main/resources/templates/studio/   # Thymeleaf pages (+ htmx partials)
└── src/main/resources/static/studio/      # dmn-js, diff2html, htmx (vendored static assets)
catalog/
├── src/main/java/com/d2os/catalog/
│   ├── DraftService.java              # draft lifecycle incl. InReview freeze
│   ├── PublishService.java            # + gate-integrated publish, MAJOR detection, conflict surfacing
│   ├── ForkService.java               # fork-with-provenance (derived_from_id)
│   ├── DeprecationImpactService.java  # pins + edges + subscription downstream report
│   ├── CompatibilityMatrixService.java# computed matrix from definition body compat declarations
│   └── SubscriptionService.java       # copy-on-subscribe with provenance (T4-d)
├── src/main/resources/db/migration/V21__studio_lifecycle_subscriptions.sql
governance/
├── src/main/resources/db/migration/V22__gate_subject_polymorphic.sql
│                                      # subject_type/subject_id (+ backfill from artifact column)
└── ...                                # GateService accepts DEFINITION_VERSION subjects
app/
└── src/test/java/com/d2os/app/
    ├── StudioAuthoringIT.java         # US1: 8 types draftable; DMN + typed-slot editors persist
    ├── PublishGovernanceIT.java       # US2: InReview → D4 gate, prompt diff, semver/checksum, MAJOR board
    ├── LifecycleToolingIT.java        # US3: impact report incl. pinned cases; fork provenance; matrix
    ├── CopyOnSubscribeIT.java         # US4: copy not reference; insulation from Global deprecation
    └── ResolutionBenchmarkIT.java     # US5: 500 versions, pin resolution ≤ 2 s [tagged slow]
```

**Structure Decision**: the studio is a **presentation module** — all catalog semantics (draft,
publish, fork, subscribe, impact) live as services in `catalog` so they are API-testable without
the UI, and `studio` renders them. Publish governance reuses the Phase 5 `governance` module
(approval-gate subprocess + GateInstance) rather than inventing studio-local review state — V22
generalizes the gate subject so a definition version is as valid a gate subject as an artifact
revision. Migrations continue the ordered stream: **V21** (catalog), **V22** (governance).

## Complexity Tracking

> No constitution violations — table intentionally empty. (The 13th module is a presentation
> layer over existing BCs, keeping UI concerns out of domain modules.)

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| — | — | — |
