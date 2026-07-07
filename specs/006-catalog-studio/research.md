# Research: Catalog Studio (Admin UI)

**Feature**: 006-catalog-studio · **Date**: 2026-07-07
All NEEDS CLARIFICATION items from the Technical Context are resolved below.

## R1. UI stack — server-rendered Thymeleaf + htmx with JS islands

**Decision**: The studio is server-rendered inside the monolith (new `studio` module): Thymeleaf
templates + htmx for partial updates, with two vendored static-asset JS islands — **dmn-js** for
the DMN decision-table editor (E6.1) and **diff2html** for rendering the deterministic diffs
produced server-side. No SPA framework, no Node build chain; static assets are vendored and
version-pinned in the repo.

**Rationale**: AS-1 (1–3 devs) — a React/Vue SPA adds a second toolchain, a second deploy
artifact, and an API-versioning surface for an internal admin tool with ~10 CRUD-ish flows.
Server-rendered pages reuse the existing Spring security/RLS session context (workspace binding
per request) with zero token plumbing. dmn-js is the only genuinely rich-client need and works as
a plain script-tag island; the plan's "UI scope volatile / LOW-MEDIUM confidence" flag argues for
the stack that is cheapest to change.

**Alternatives considered**: React SPA — rejected (toolchain + auth surface cost for an internal
tool; nothing in the studio needs client-side state beyond the two islands); plain REST-only
(no UI, defer to API clients) — rejected (the phase's exit criterion is explicitly an authoring
UI with rendered diffs).

## R2. Draft/InReview lifecycle on the existing definition_asset

**Decision**: V21 widens the status CHECK to `('Draft','InReview','Published','Deprecated')`.
Drafts are ordinary `definition_asset` rows in status `Draft` (already supported since V3 —
`CatalogSeedLoader` just published immediately). `InReview` freezes the draft: `DraftService`
refuses content edits while a review is open (app-level, plus the review gate pins the draft's
content hash at submission so any tamper is detected at publish). Publish flips
`InReview → Published` in the same transaction that stamps `checksum` + `published_at` after the
gate passes; the V3 immutability trigger then locks the row exactly as today.

**Rationale**: E1.1 already built the supertype with a status lifecycle; §17 names
Draft → InReview → Published as the catalog governance flow — the only schema gap is the missing
`InReview` enum value. Reusing the row (not a separate `draft` table) keeps `(type, key, version)`
uniqueness doing the semver-conflict work for free (FR-006).

**Alternatives considered**: separate draft table copied into `definition_asset` at publish —
rejected (duplicate schema, two identity spaces, and conflict checks would need reimplementing);
storing drafts as JSON blobs in the studio module — rejected (drafts must be workspace-scoped,
RLS'd rows like everything else, Principle III/IV).

## R3. Publish gates — reuse Phase 5 GateInstance with a polymorphic subject (T4-b)

**Decision**: V22 generalizes `gate_instance`: add `subject_type text NOT NULL DEFAULT
'ARTIFACT_REVISION'` and `subject_id uuid NULL`, backfill from
`subject_artifact_revision_id`, and keep the old column as a deprecated read alias until Phase 7+.
Submitting a draft for review starts the Phase 5 **approval-gate subprocess** with subject
`('DEFINITION_VERSION', definition_asset.id)`; the D4 decision (Catalog Owner role per Q8) is a
normal GateInstance decision with Decision + AuditEntry. **MAJOR-version publishes** (semver diff
computed against the prior published version of the same `(type, key)`) chain a **second**
approval-gate instance bound to the `architecture-board` role; publish requires both PASS.
Prompt/Persona reviews attach a DeltaReport (Phase 5 service) diffing prompt text against the
prior published version as the gate's first-class review content (T4-c); non-prompt types fall
back to a canonical-JSON content diff.

**Rationale**: This is the stated reason Phase 5 blocks Phase 6 — E6.2 *encodes* E5.1's machinery
rather than inventing parallel review state. One gate mechanism means escalation policies, SLA
visibility, audit shape, and the Phase 7 gate-event payloads all apply to catalog publishes for
free. The polymorphic subject is the minimal generalization (two columns + backfill, lossless).

**Alternatives considered**: studio-local review status field on the draft — rejected (a second,
weaker gate implementation is exactly what the dependency map warns against); making the
architecture board a step *inside* one gate policy — rejected (it is a distinct authority with its
own decision record; two gates keep both Decisions first-class).

## R4. Fork-with-provenance and the `derived_from_id` column (AD-3)

**Decision**: V21 adds `derived_from_id uuid NULL REFERENCES definition_asset(id)`. `ForkService`
copies a source version's body into a new `Draft` row (same or new key, author's choice; version
reset per semver rules) recording `derived_from_id` = source row id. The existing text columns
(`derived_from_key/version`, used for v0-template provenance) remain for import lineage; the id
column is the authoritative in-catalog fork link the spec names (FR-012). Forking a `Deprecated`
source is allowed and recorded (edge case in spec); the fork is an independent version, and
runtime override remains impossible (nothing resolves through fork links).

**Rationale**: AD-3/TR-matrix places fork-with-provenance in E6.3; an id-based FK gives exact
row-level lineage where the Phase-0 text columns can only name external origins. Keeping both is
additive, not parallel structure — they answer different provenance questions.

**Alternatives considered**: reusing the text columns for in-catalog forks — rejected (ambiguous
against v0-import lineage and unenforceable referentially); a trace_link edge — considered but
rejected (edges relate *instances/artifacts*; definition lineage is a catalog-intrinsic property
that should survive without the artifacts module).

## R5. Deprecation impact report + compatibility matrix — computed, not stored

**Decision**: `DeprecationImpactService` computes the report at request time from three sources:
(1) **pinned active cases** — non-terminal `case_instance` whose `case_definition_snapshot.entries`
contains the exact `{type, key, version}` (JSONB containment query, GIN-indexed in V21);
(2) **definition-graph dependents** — published definitions whose body references the key
(workflow→persona/playbook bindings etc., resolved by the existing resolution service);
(3) **downstream copies** — `library_subscription` rows pointing at the version (Global-library
case). Confirming deprecation requires the report to have been generated (server-enforced:
confirm carries the report id) and writes the status flip + AuditEntry. The **compatibility
matrix** is likewise computed: definitions declare `compatible_with` ranges in their body
(content-level convention); `CompatibilityMatrixService` evaluates declared ranges against
published versions and renders the cross-type grid, flagging pins outside declared ranges.

**Rationale**: Both are views over existing truth (pins, bodies, subscriptions) — storing them
would create derived state to keep consistent (Principle III). The JSONB containment query on
snapshots is exact (pins are exact versions by AD-4), so SC-005's "zero pinned dependents
omitted" is a query-correctness property, testable directly.

**Alternatives considered**: materialized impact tables maintained by triggers — rejected
(derived truth, drift risk, no need at this scale); requiring compat declarations in a new schema
table — rejected (body-level convention keeps it a content concern authored like everything else).

## R6. Copy-on-subscribe from the Global library (AD-10, T4-d)

**Decision**: The Global library is the zero-UUID workspace's published definitions (already
readable by all workspaces via the V3 RLS policy). V21 adds `copied_from_id uuid NULL REFERENCES
definition_asset(id)` and a `library_subscription` table (workspace, source definition id,
copied definition id, subscribed_by/at). `SubscriptionService.subscribe(sourceId)` **copies** the
source version into the calling workspace as a new `Published` row (same key/version/body/checksum
— checksum equality is the copy-integrity proof) with `copied_from_id` provenance, in one
transaction with the subscription row + AuditEntry. Resolution within the workspace prefers the
workspace's own copy over the Global row. Later Global deprecation/changes never touch copies
(no propagation mechanism exists — insulation by construction; FR-014).

**Rationale**: AD-10/T4-d verbatim — distribution by provenance-carrying copy so a workspace's
snapshot never changes underneath it (Principle IV). Copying the checksum makes supply-chain
verification a one-line equality check in the T4 suite.

**Alternatives considered**: live references to Global rows — rejected (the exact supply-chain
failure T4-d exists to prevent); copy-on-first-resolution (lazy) — rejected (subscription is the
governed, audited act; lazy copies would materialize rows outside any decision).

## R7. NFR-9 resolution benchmark

**Decision**: `ResolutionBenchmarkIT` (tagged slow, CI-nightly): seed 500 published definition
versions across the 8 types (realistic key/version distribution), then measure the existing
`(type, key, version)` resolution service — 1 000 mixed resolutions, assert p95 **and** worst
case ≤ 2 s (expected: single-digit ms via `uq_definition_type_key_version`; the benchmark is a
regression tripwire, not an optimization task). Also benchmarks snapshot pinning at case start
(the resolution-heavy path) at the same seeded scale.

**Rationale**: NFR-9's bound is generous for an indexed unique lookup; the value of the benchmark
is catching an accidental O(n) (e.g., a future in-memory scan or an unindexed JSONB filter in
resolution). Seeding through the real seed-loader path keeps the benchmark honest.

**Alternatives considered**: micro-benchmark harness (JMH) — rejected (Testcontainers IT measures
the real query path incl. RLS; JMH precision is unnecessary against a 2 s bound).
