# Feature Specification: Catalog Studio (Admin UI)

**Feature Branch**: `006-catalog-studio`

**Created**: 2026-07-07

**Status**: Draft

**Input**: Phase 6 of the D2OS phased implementation plan — give catalog authors a first-class admin
studio for authoring, publishing, and governing the eight immutable definition types through a semver
publish workflow, render prompt diffs as first-class review content in the publish gate, produce
deprecation impact reports and a compatibility matrix, support fork-with-provenance authoring, and
distribute definitions across workspaces by copy-on-subscribe from a Global shared library — all while
holding pin-resolution performance at scale.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Authors create every definition type as a governed draft in the studio (Priority: P1)

A catalog author opens the studio and creates a working draft of any of the eight definition types —
CaseType, Workflow, Persona, Playbook, Template, Rule, Rubric, Prompt — using type-appropriate editors:
a DMN table editor for rule definitions, and rubric and prompt editors with typed slots so the author
fills structured fields rather than free-form blobs. Every draft is a candidate definition version that
has not yet been published, so nothing an author does in the studio can change what a running case
resolves until the draft is published through the governance flow.

**Why this priority**: Authoring is the studio's foundation — without a governed way to create drafts of
all eight types, none of the downstream publish, deprecation, or distribution flows have anything to act
on. This is the phase's baseline deliverable: all eight definition types authorable in the UI.

**Independent Test**: In the studio, create a draft of each of the eight definition types, author a rule
via the DMN table editor and a prompt via the typed-slot editor, save them, and confirm each draft is
persisted as an unpublished candidate version that does not affect any running case.

**Acceptance Scenarios**:

1. **Given** an authenticated catalog author, **When** they choose any of the eight definition types and
   create a new draft, **Then** the studio persists a draft candidate version of that type that is not yet
   published and is not resolvable by any running case.
2. **Given** a Rule definition draft, **When** the author edits it, **Then** the studio presents a DMN
   decision-table editor and saves the authored table as the draft's content.
3. **Given** a Rubric or Prompt definition draft, **When** the author edits it, **Then** the studio
   presents typed slots for the definition's structured fields and validates the slots before save.
4. **Given** a saved draft, **When** the author reopens it, **Then** the studio restores the full draft
   content for continued editing, and the draft remains outside the resolution path until published.

---

### User Story 2 - Publishing a version passes a D4 governance review with prompt diffs rendered (Priority: P1)

A catalog author moves a draft through the publish lifecycle: Draft → InReview → Published. Advancing to
Published requires passing the D4 governance review gate. When the version being published is a Prompt or
a Persona (which carries prompt text), the reviewer sees a rendered diff of the prompt text against the
prior published version as first-class review content — not buried in a raw payload. At publish, the
system computes a checksum and enforces semver against the prior version. A MAJOR-version publish
additionally requires an architecture-board gate before it can complete.

**Why this priority**: Publish governance is what makes the catalog trustworthy and immutable in
practice; it is the studio's reason to exist alongside authoring. Prompt diffs as first-class review
content and the semver/checksum enforcement are the guarantees that keep published definitions auditable
and reproducible, so this ships co-equal P1 with authoring.

**Independent Test**: Take a Prompt draft that changes an existing published prompt, advance it to
InReview, confirm the reviewer sees a rendered text diff against the prior version, approve through D4,
and confirm the version publishes with a computed checksum and a semver that is enforced against the
prior version; then attempt a MAJOR publish and confirm it is held until the architecture-board gate
passes.

**Acceptance Scenarios**:

1. **Given** a saved draft, **When** the author submits it for review, **Then** the definition version
   transitions Draft → InReview and cannot publish until the D4 review gate is satisfied.
2. **Given** a Prompt or Persona version in review, **When** the reviewer opens it, **Then** the studio
   renders a diff of the prompt text against the prior published version as first-class review content.
3. **Given** a version that passes D4 review, **When** it is published, **Then** the system computes and
   records its checksum and enforces semver ordering against the prior published version, rejecting a
   version whose semver or checksum conflicts with an existing published version.
4. **Given** a version whose version increment is MAJOR, **When** publish is attempted, **Then** the
   system requires an architecture-board gate in addition to D4 review and blocks publish until that gate
   passes.
5. **Given** the Catalog Owner as the accountable model-governance owner, **When** any of the eight types
   is published, **Then** the Draft → InReview → Published lifecycle and its D4 gate are owned by the
   Catalog Owner (a distinct Rules Steward role is introduced only once rule volume exceeds ~50 rules).

---

### User Story 3 - Authors see deprecation impact, compatibility, and fork provenance (Priority: P2)

A catalog author needs lifecycle tooling before retiring or deriving definitions. Deprecating a
definition version produces an impact report that surfaces everything still depending on it — including
active cases pinned to that exact version — so nothing is deprecated blindly. A compatibility-matrix view
shows which definition versions are compatible with which across types. When authoring a new definition
derived from an existing one, the studio records a fork-with-provenance link (`derived_from_id`), so the
lineage is captured without ever allowing a runtime override of an immutable definition.

**Why this priority**: These tools make the catalog safely evolvable — deprecation and forking are how the
library grows without breaking running work — but they operate on definitions the P1 stories already make
authorable and publishable, so they layer on top rather than gating the core loop.

**Independent Test**: Deprecate a definition version that an active case pins and confirm the impact
report lists that case; open the compatibility matrix and confirm it reflects cross-type version
compatibility; fork a definition and confirm the new draft records a `derived_from_id` provenance link to
its source.

**Acceptance Scenarios**:

1. **Given** a published definition version, **When** the author initiates deprecation, **Then** the
   studio produces an impact report listing every dependent — including active cases pinned to that exact
   `(key, version)` — before the deprecation is confirmed.
2. **Given** definitions across multiple types, **When** the author opens the compatibility matrix,
   **Then** the studio displays which versions are compatible with which, so incompatible pins are visible.
3. **Given** an existing definition, **When** the author forks it into a new draft, **Then** the studio
   records a `derived_from_id` provenance link from the new draft to its source and treats the new draft
   as its own independent version (never a runtime override of the source).

---

### User Story 4 - Workspaces get their own copy of shared definitions on subscribe (Priority: P2)

A workspace subscribes to a Global shared library and, on subscribe, receives its own provenance-tracked
copy of a definition version rather than a live shared reference. This copy-on-subscribe property is the
supply-chain-safety guarantee: a subscribing workspace is insulated from later changes or deprecations to
the Global source, because it holds a copy, and the copy records where it came from.

**Why this priority**: Library distribution is how definitions spread across the organization, and the
copy (not reference) semantics are the supply-chain safety property that makes subscription safe. It is
P2 because it builds on the authored, published definitions of P1 and the provenance discipline of US3.

**Independent Test**: From a subscribing workspace, subscribe to a Global library definition version,
confirm the workspace now holds its own copy with a provenance link to the Global source, then deprecate
the Global source and confirm the workspace's copy is unaffected.

**Acceptance Scenarios**:

1. **Given** a Global shared library definition version, **When** a workspace subscribes to it, **Then**
   the workspace receives its own copy of that version, not a live shared reference, and the copy records
   provenance to the Global source.
2. **Given** a workspace holding a copied definition, **When** the Global source version is later
   deprecated or changed, **Then** the workspace's copy continues to resolve unchanged, insulated from the
   Global source's lifecycle.
3. **Given** a copied definition, **When** its provenance is inspected, **Then** the studio shows the
   Global source `(key, version)` it was copied from.

---

### User Story 5 - Pin resolution stays fast at catalog scale (Priority: P3)

An operator seeds the catalog to realistic scale — 500 definition versions — and confirms that resolving
a definition by its `(key, version)` pin stays within its performance bound, so a large, mature catalog
does not slow down case execution that depends on pin resolution.

**Why this priority**: The scale check verifies the catalog remains operable as it grows, but it validates
the machinery rather than producing a new authoring capability, so it is P3 — necessary before scale-out
but not on the critical authoring path.

**Independent Test**: Seed 500 definition versions and benchmark `(key, version)` pin resolution,
confirming p-level resolution time stays within its ≤ 2 s bound.

**Acceptance Scenarios**:

1. **Given** a catalog seeded with 500 definition versions, **When** a `(key, version)` pin is resolved,
   **Then** resolution completes within its ≤ 2 s bound.
2. **Given** the seeded catalog under the benchmark, **When** resolution is measured across many pins,
   **Then** resolution time holds within bound and does not degrade as the version count grows to the
   seeded scale.

---

### Edge Cases

- An author attempts to publish a version whose semver or checksum conflicts with an already-published
  version → publish is rejected with the conflict surfaced, and the immutable published version is never
  overwritten.
- An author attempts to deprecate a definition version still pinned by active cases → the deprecation
  impact report surfaces those active cases before confirmation, so the deprecation is a deliberate, not
  blind, act.
- A MAJOR-version publish is attempted without the architecture-board gate → publish is blocked until the
  architecture-board gate passes, in addition to the standard D4 review.
- A workspace has copied-on-subscribe a Global version that is later deprecated at the Global source → the
  workspace's copy is unaffected and continues to resolve, and its provenance still records the (now
  deprecated) Global source.
- A fork is attempted from a definition that is itself deprecated → the fork is allowed and records
  provenance, but the resulting draft is treated as a fresh independent version, not a resurrection of the
  deprecated source.
- A reviewer opens a non-prompt-bearing definition (e.g., a Workflow or Template) in review → the D4 gate
  still applies, and the diff view falls back to a structural/content diff appropriate to that type rather
  than a prompt-text diff.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST let a catalog author create a draft candidate version of each of the eight
  definition types (CaseType, Workflow, Persona, Playbook, Template, Rule, Rubric, Prompt) in the studio,
  where a draft is not published and not resolvable by any running case.
- **FR-002**: System MUST provide a DMN decision-table editor for authoring Rule definition content within
  the studio.
- **FR-003**: System MUST provide rubric and prompt editors with typed slots so authors fill structured,
  validated fields rather than unstructured free-form content.
- **FR-004**: System MUST support the publish lifecycle Draft → InReview → Published for every definition
  type, where advancing to Published requires passing the D4 governance review gate.
- **FR-005**: System MUST render a diff of prompt text against the prior published version as first-class
  review content when a Prompt or Persona version is in review.
- **FR-006**: System MUST compute and record a checksum at publish and MUST enforce semver ordering
  against the prior published version, rejecting any publish whose semver or checksum conflicts with an
  existing published version.
- **FR-007**: System MUST require an architecture-board gate, in addition to the D4 review, before a
  MAJOR-version publish can complete.
- **FR-008**: System MUST treat published definition versions as immutable — never overwriting, editing,
  or allowing runtime override of a published version.
- **FR-009**: System MUST assign ownership of the Draft → InReview → Published lifecycle and its D4 gate
  (including DMN/rule authoring) to the Catalog Owner as the single accountable model-governance owner in
  v1; a distinct Rules Steward role is introduced only once rule volume exceeds ~50 rules.
- **FR-010**: System MUST produce a deprecation impact report when a definition version is being
  deprecated, listing every dependent on that version — including active cases pinned to that exact
  `(key, version)` — before the deprecation is confirmed.
- **FR-011**: System MUST provide a compatibility-matrix view showing which definition versions are
  compatible with which across definition types.
- **FR-012**: System MUST record a `derived_from_id` provenance link when an author forks a new definition
  from an existing one, and MUST treat the fork as an independent version rather than an override of its
  source.
- **FR-013**: System MUST, when a workspace subscribes to a Global shared library definition version,
  create a provenance-tracked copy of that version owned by the subscribing workspace, not a live shared
  reference.
- **FR-014**: System MUST insulate a workspace's copied definition from later changes or deprecations to
  its Global source version, so the copy continues to resolve unchanged.
- **FR-015**: System MUST record and display, for any copied-on-subscribe definition, the Global source
  `(key, version)` it was copied from.
- **FR-016**: System MUST resolve a `(key, version)` pin within a ≤ 2 s bound when the catalog holds 500
  definition versions.
- **FR-017**: System MUST record every publish, deprecation, fork, and subscribe action as an auditable
  event, consistent with the catalog's immutability and provenance guarantees.
- **FR-018**: System MUST surface publish, semver, and checksum conflicts to the author at the point of
  the conflicting action rather than failing silently or overwriting the immutable published version.

### Key Entities *(include if feature involves data)*

- **Definition (draft / published version)**: An instance of one of the eight definition types, existing
  as an unpublished draft candidate or an immutable published version identified by `(key, version)` with
  a checksum. Drafts are editable in the studio; published versions are immutable.
- **Publish lifecycle state**: The Draft → InReview → Published state of a definition version, gated by the
  D4 governance review and, for MAJOR versions, the architecture-board gate.
- **Prompt diff (review content)**: The rendered text diff between a Prompt/Persona version under review
  and its prior published version, surfaced as first-class content in the D4 review.
- **Deprecation impact report**: The generated report listing all dependents of a version being deprecated,
  including active cases pinned to the exact `(key, version)`.
- **Compatibility matrix**: The cross-type view of which definition versions are compatible with which.
- **Provenance link (`derived_from_id`)**: The fork-with-provenance link from a derived definition draft to
  the source it was forked from.
- **Global library subscription / copy**: The workspace-owned, provenance-tracked copy of a Global shared
  library definition version, produced on subscribe, recording the Global source `(key, version)`.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: All eight definition types can be authored as drafts and published through the studio, with
  100% of types covered by type-appropriate editors (DMN table for rules; typed-slot editors for rubrics
  and prompts).
- **SC-002**: 100% of publishes require a passing D4 review, and every Prompt/Persona publish renders a
  prompt-text diff against the prior version as review content before it can complete.
- **SC-003**: Every publish computes a checksum and enforces semver; a publish that conflicts in semver or
  checksum with an existing published version is rejected 100% of the time.
- **SC-004**: 100% of MAJOR-version publishes are blocked until the architecture-board gate passes, in
  addition to D4 review.
- **SC-005**: Deprecating a definition version pinned by an active case surfaces that case in the impact
  report 100% of the time, with zero pinned dependents omitted.
- **SC-006**: A copy-on-subscribe demonstration across workspaces shows a subscribing workspace receiving
  its own copy with recorded provenance, and that copy resolving unchanged after the Global source version
  is deprecated.
- **SC-007**: With 500 seeded definition versions, `(key, version)` pin resolution stays within its ≤ 2 s
  bound (NFR-9 benchmark green).
- **SC-008**: Every forked definition records a `derived_from_id` provenance link to its source, with zero
  forks lacking provenance and zero runtime overrides of any published definition.

## Assumptions

- The studio is **authoring and governance tooling only** — it creates drafts and moves them through the
  publish lifecycle, but it never mutates a published (immutable) definition and never overrides a
  definition at runtime. Immutability and semver are guarantees inherited from the Phase 1 definition
  core, not re-implemented here.
- Per the Q8 ruling, the **Catalog Owner is the single accountable model-governance owner** in v1 and owns
  DMN/rule authoring and the Draft → InReview → Published lifecycle; a distinct **Rules Steward** role is
  introduced only once rule volume exceeds **~50 rules**. This spec assumes v1 rule volume is below that
  threshold, so no separate Rules Steward role is provisioned.
- "D4" refers to the governance review/approval gate a publish must pass. The **gate machinery itself
  (Review-Gate / Approval-Gate subprocesses, the D4 decision flow, EscalationPolicy) is built in Phase 5**;
  Phase 6 encodes that machinery in the publish-governance UI rather than inventing new gate mechanics.
  See Dependencies — this is a blocking upstream dependency.
- The **MAJOR-version architecture-board gate** is an additional approval step layered on the D4 review; its
  exact composition and routing are governed by the Phase 5 gate machinery.
- **Copy-on-subscribe** semantics are deliberate: subscription yields a copy, not a live reference, to
  preserve the T4 supply-chain-safety property. Content remains single-language in v1, carrying forward
  the locale dimension already present on Definitions rather than extending it.
- The **NFR-9 benchmark** (500 versions, `(key, version)` pin resolution ≤ 2 s) is the standing scale
  target; the 500-version seed is a representative scale for the benchmark, not a hard catalog ceiling.
- UI scope is acknowledged as **volatile** (source plan confidence LOW-MEDIUM); this spec fixes the
  required capabilities and their acceptance behavior while leaving specific interaction and layout choices
  to planning.
- Team and deployment assumptions are unchanged from earlier phases (small team, single-region cloud,
  logical per-workspace isolation).

## Dependencies

- ⚠️ **BLOCKING for implementation — Phase 5 (Governance & Review Gates) must be built first.** Phase 6's
  entry criterion is "Phase 5 exit." Phase 5 builds the first-class Review-Gate / Approval-Gate
  subprocesses, the D4 decision flow, and EscalationPolicy, and **Phase 5 blocks Phase 6 because the
  publish-governance UI (E6.2) encodes the gate machinery built in E5.1.** *Update 2026-07-07*: Phase 5 is
  now **specified and planned** (spec `005-governance-review-gates` + its plan/design artifacts exist), so
  this phase may be planned against Phase 5's gate contracts (GateInstance, approval-gate subprocess,
  Decision records) — but **implementation remains blocked until Phase 5 is built**, because the
  Draft → InReview → Published D4 publish flow and the MAJOR-version architecture-board gate in E6.2 reuse
  that machinery at runtime.
- **Phase 1 (Catalog Spine — definition core, immutability, semver; E1.1)** — the studio authors, publishes,
  and resolves the immutable, semver-versioned definition model established in Phase 1. Checksum
  computation, semver enforcement, and `(key, version)` pin resolution are Phase 1 primitives the studio
  builds on. This dependency is **satisfied** (spec 001 exists).
- **All eight definition types must be stable (transitive, Phases 1–5).** The studio surfaces and edits
  every definition type — CaseType, Workflow, Persona, Playbook, Template, Rule, Rubric, Prompt — and the
  DMN tables introduced across Phases 1–5. Because it depends on the whole definition model being stable, it
  transitively depends on the definition-bearing work of the intervening phases (including Phase 5's gate
  and DMN-related definitions). Any change to a definition type's shape after this spec would ripple into
  the studio's editors.
- **Q8 is resolved** (Catalog Owner as sole model-governance owner; Rules Steward only past ~50 rules), so
  DMN/rule authoring ownership is settled and is not a blocking open question for this phase.
