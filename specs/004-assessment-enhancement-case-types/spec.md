# Feature Specification: Assessment + Enhancement Case Types

**Feature Branch**: `004-assessment-enhancement-case-types`

**Created**: 2026-07-07

**Status**: Draft

**Input**: Phase 4 of the D2OS phased implementation plan — stand up the second and third case types (Assessment and Enhancement) alongside the existing Initiation type, route incoming problems to the correct case type from intake through a decision table with a human-confirm step, and prove that the catalog can carry entirely new case types as authored Definitions with zero database schema changes. Assessment is read-only and produces a findings + recommendation package only; Enhancement produces delta-documentation and impact analysis against a prior baseline; a single active mutating Case per Feature is enforced by an optimistic version check.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Intake routes a submission to the correct case type with human confirm (Priority: P1)

A requester submits a problem without stating which case type it is. Intake classifies the submission's
attributes against a decision table — is this a build-from-scratch request, an evaluation of something
that already exists, or a change to an existing baseline — and proposes the matching case type. Before the
Case is created, a human reviewer confirms or overrides the proposed classification, so a misread problem
never silently launches the wrong pipeline.

**Why this priority**: Routing is the gateway to everything else in this phase — the two new case types
are only reachable if intake can direct submissions to them. A submission that lands on the wrong case
type produces the wrong package, so getting classification right (with a human backstop) is the
foundational deliverable that unlocks User Stories 2 and 3.

**Independent Test**: Submit three problems — a from-scratch request, an evaluation request, and a change
request — and confirm intake proposes Initiation, Assessment, and Enhancement respectively, each pausing
for human confirmation before the Case is created.

**Acceptance Scenarios**:

1. **Given** a submission whose attributes clearly match an evaluation of an existing thing, **When** intake
   runs classification, **Then** the decision table proposes the Assessment case type and presents that
   proposal for human confirmation before any Case is created.
2. **Given** a submission whose attributes match a change against an existing baseline, **When** intake runs
   classification, **Then** the decision table proposes the Enhancement case type and presents it for
   human confirmation.
3. **Given** a proposed classification, **When** the human confirms it, **Then** the Case is created with
   the confirmed case type; **When** the human overrides it, **Then** the Case is created with the human's
   chosen type and the override is recorded as an auditable decision.
4. **Given** a submission whose attributes do not decisively match any single case type, **When** intake
   runs classification, **Then** the ambiguity is surfaced to the human rather than guessed, and the human
   confirm step resolves it.

---

### User Story 2 - An Assessment case runs end to end and mutates nothing (Priority: P1)

A requester asks the system to evaluate something that already exists. The Assessment case type runs its
personas against that subject and produces a findings + recommendation package — an evaluation of the
current state scored against rubrics, plus a recommendation — and nothing else. It authors no mutating
documentation artifacts, changes no prior baseline, and consumes no single-active-mutating-case slot,
because Assessment is read-only by construction.

**Why this priority**: Assessment is the first of the two new case types and the cleanest proof that the
catalog can carry a genuinely different pipeline shape. Its read-only nature is also what makes it exempt
from the concurrency guard (User Story 4), so establishing that boundary correctly is load-bearing for the
rest of the phase.

**Independent Test**: Run an Assessment case end to end and confirm the delivered package contains only
findings and a recommendation, that no mutating artifact or baseline change is written anywhere, and that
the Feature's mutating-case slot is untouched.

**Acceptance Scenarios**:

1. **Given** a confirmed Assessment case, **When** the pipeline runs end to end, **Then** it produces a
   findings + recommendation package scored against the Assessment rubrics and reaches Delivered.
2. **Given** an Assessment case in progress, **When** any step attempts to author a mutating documentation
   artifact or alter a prior baseline, **Then** the attempt is blocked by read-only enforcement and
   recorded, and the case still completes with its findings package.
3. **Given** an Assessment case running against a Feature, **When** it executes, **Then** it does not
   occupy the Feature's single-active-mutating-case slot and does not block a concurrent mutating case on
   the same Feature.

---

### User Story 3 - An Enhancement case produces delta-docs and impact analysis against a prior baseline (Priority: P1)

A requester asks to change something the system has already documented. The Enhancement case type
references the prior baseline artifacts through the existing trace-link relationships, produces
delta-documentation describing only what changes, and produces an impact analysis explaining what the
change affects downstream. The delivered package is anchored to the baseline it enhances, so a reviewer can
see exactly what is new relative to what came before.

**Why this priority**: Enhancement is the third case type and the one that exercises cross-case
referencing — it must reuse the trace-link edges established in Phase 1 rather than duplicating a baseline.
It completes the "three case types executable" phase exit and, being mutating, is the case that the
concurrency guard in User Story 4 actually protects.

**Independent Test**: Run an Enhancement case against a Feature that already has a delivered baseline and
confirm the package contains delta-docs plus an impact analysis, each trace-linked to the specific baseline
artifacts they enhance.

**Acceptance Scenarios**:

1. **Given** a Feature with a delivered baseline package, **When** an Enhancement case runs, **Then** it
   references the baseline artifacts through trace links rather than re-authoring them, and produces
   delta-documentation describing only the changes.
2. **Given** the Enhancement case, **When** it completes, **Then** the delivered package includes an impact
   analysis identifying which baseline artifacts and downstream consumers the change affects.
3. **Given** each delta artifact, **When** the package is inspected, **Then** every delta and impact entry
   is trace-linked to the specific baseline artifact it derives from, so provenance to the baseline is
   explicit and auditable.

---

### User Story 4 - Only one mutating case may be active per Feature (Priority: P2)

Two people try to launch changes against the same Feature at nearly the same time. The system permits only
one active mutating Case per Feature: at Case-creation time it checks the Feature aggregate's version, and
the second creation attempt fails the check and is rejected as a conflict, telling the user a mutating case
is already active. Assessment cases, being read-only, are exempt and may always run — including alongside a
mutating case on the same Feature.

**Why this priority**: The guard prevents two mutating pipelines from racing on one Feature's baseline, but
it protects correctness rather than delivering the core value of the new case types. It is P2 because the
case types are demonstrable without contention, and two simultaneous mutating cases on one Feature is a
rare user error, not routine load.

**Independent Test**: Attempt to create two mutating cases (Initiation or Enhancement) on one Feature
concurrently and confirm exactly one succeeds and the other is rejected as a conflict; then confirm an
Assessment case on that same Feature is admitted regardless.

**Acceptance Scenarios**:

1. **Given** a Feature with no active mutating case, **When** a mutating case is created, **Then** it
   succeeds and the Feature aggregate's version advances to reflect the active mutating case.
2. **Given** a Feature that already has an active mutating case, **When** a second mutating case is created,
   **Then** the optimistic version check fails and the second creation is rejected as a conflict with a
   clear message, rather than being held in a lock or silently queued.
3. **Given** a Feature with an active mutating case, **When** an Assessment (read-only) case is created on
   the same Feature, **Then** it is admitted and runs, because Assessment is exempt from the guard.
4. **Given** the active mutating case reaches a terminal state, **When** it completes, **Then** the
   Feature's mutating-case slot is released so a subsequent mutating case may be created.

---

### User Story 5 - Conditional artifacts and zero-schema-change extension (Priority: P3)

Some problems carry attributes that demand extra artifacts — for example, a problem that involves personal
data requires a data-protection impact assessment. A conditional-artifact decision table adds those
required artifacts to the case's expected set based on the submission's attributes. Separately, and
crucially, both new case types are added to the platform purely as authored catalog Definitions
(case-type, workflow, template, rule, and rubric records) with no new database tables — proving the catalog
extends by authoring, not by schema migration.

**Why this priority**: Conditional artifacts and the zero-schema-change proof both harden and validate the
extension model rather than delivering a case type directly, so they are the lowest priority of the phase.
But the zero-schema-change property is a headline proof point — it is the evidence that future case types
cost catalog authoring, not engineering — so it must be demonstrated, not merely asserted.

**Independent Test**: Submit a problem flagged as involving personal data and confirm a data-protection
impact assessment is added to the required artifact set; separately, confirm that adding both new case
types introduced zero new database tables and that they are defined entirely as catalog Definitions.

**Acceptance Scenarios**:

1. **Given** a submission whose attributes indicate personal data is involved, **When** the
   conditional-artifact decision table runs, **Then** a data-protection impact assessment is added to the
   case's required artifact set, and the case cannot deliver without it.
2. **Given** a submission with no attribute triggering a conditional artifact, **When** the same table
   runs, **Then** no extra artifact is added and the case's required set is the base set for its type.
3. **Given** the two new case types are installed, **When** the database schema before and after is
   compared, **Then** no new tables were added — both case types exist solely as catalog Definitions
   (case-type, workflow, templates, rules, rubrics).

---

### Edge Cases

- Two mutating-case creation attempts land on one Feature at nearly the same instant → the optimistic
  version check admits exactly one; the second sees the advanced version and is rejected as a conflict, not
  blocked on a lock or silently dropped.
- An Assessment case attempts to write a mutating artifact or change a baseline → read-only enforcement
  blocks the write, records the blocked attempt, and the case still completes with its findings package.
- An Enhancement case references a baseline artifact that was later deprecated → the reference resolves to
  the specific baseline version through the trace link and the deprecation is surfaced in the impact
  analysis, rather than the case failing or silently enhancing a stale artifact.
- Intake classification is ambiguous between two case types → the ambiguity is surfaced to the human confirm
  step, which resolves it, rather than the system guessing a type.
- A submission triggers a conditional artifact (e.g., personal data present) but the required artifact is
  never produced → the case is blocked from delivery until the conditional artifact exists.
- An Enhancement case is requested against a Feature that has no delivered baseline → the case is rejected
  or redirected at intake, because there is nothing to enhance.
- A human overrides intake's proposed case type → the Case is created with the human's chosen type and the
  override is recorded as an auditable decision with the original proposal.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST provide an Assessment case type and an Enhancement case type as first-class case
  types executable alongside the existing Initiation case type, each with its own workflow, templates,
  rules, and rubrics authored in the catalog.
- **FR-002**: System MUST classify an incoming submission's attributes against a case-type decision table at
  intake and propose the matching case type (Initiation, Assessment, or Enhancement).
- **FR-003**: System MUST require a human confirm step on the proposed case-type classification before a
  Case is created, allowing the human to confirm or override, and MUST record an override as an auditable
  decision alongside the original proposal.
- **FR-004**: System MUST surface an ambiguous or non-decisive classification to the human confirm step
  rather than guessing a case type.
- **FR-005**: System MUST run the Assessment case type to produce a findings + recommendation package only —
  an evaluation of the subject's current state scored against Assessment rubrics plus a recommendation — and
  MUST NOT author mutating documentation artifacts or alter any prior baseline.
- **FR-006**: System MUST enforce read-only behavior for Assessment cases, blocking and recording any
  attempt to author a mutating artifact or change a baseline, while still allowing the case to complete with
  its findings package.
- **FR-007**: System MUST treat Assessment cases as exempt from the single-active-mutating-case guard, so an
  Assessment case never occupies a Feature's mutating-case slot and may run alongside a mutating case on the
  same Feature.
- **FR-008**: System MUST run the Enhancement case type to produce delta-documentation describing only the
  changes plus an impact analysis of what the change affects, anchored to a prior baseline.
- **FR-009**: System MUST reference the prior baseline's artifacts from an Enhancement case through the
  existing trace-link relationships rather than re-authoring the baseline, and MUST trace-link every delta
  and impact entry to the specific baseline artifact it derives from.
- **FR-010**: System MUST reject or redirect an Enhancement case requested against a Feature that has no
  delivered baseline, because there is nothing to enhance.
- **FR-011**: System MUST resolve a trace-linked baseline reference to its specific baseline version even
  when that artifact has since been deprecated, and MUST surface the deprecation in the impact analysis
  rather than failing the case or silently enhancing a stale artifact.
- **FR-012**: System MUST enforce at most one active mutating Case per Feature by checking the Feature
  aggregate's version at Case-creation time (optimistic concurrency), and MUST reject a second mutating-case
  creation as a conflict with a clear message rather than holding a lock or silently queueing it.
- **FR-013**: System MUST release the Feature's mutating-case slot when its active mutating case reaches a
  terminal state, so a subsequent mutating case may then be created.
- **FR-014**: System MUST run a conditional-artifact decision table over the submission's attributes that
  adds required artifacts to a case's expected set (for example, requiring a data-protection impact
  assessment when personal data is involved).
- **FR-015**: System MUST block a case from delivery until every conditionally required artifact for that
  case has been produced.
- **FR-016**: System MUST add the Assessment and Enhancement case types purely as catalog Definitions
  (case-type, workflow, template, rule, and rubric records) with no new database tables, proving
  zero-schema-change extension of the catalog.
- **FR-017**: System MUST preserve all prior-phase guarantees unchanged for the new case types — per-step
  reproducibility, append-only audit in the same transaction as the change, workspace isolation, the
  injection-symptom output check, per-Case cost budget, and byte-identical replay of every AI output.
- **FR-018**: System MUST make the full persona suite from Phase 2 available to execute the Assessment and
  Enhancement workflows, with those personas consuming injected knowledge as established in Phase 3.
- **FR-019**: System MUST record the confirmed case type, any classification override, and any conditional
  artifact additions as auditable Case metadata, so the routing decision that produced a Case is
  reconstructable.

### Key Entities *(include if feature involves data)*

- **Case type (Definition)**: An immutable versioned catalog Definition describing a pipeline shape;
  Assessment and Enhancement are added as new Definitions alongside Initiation, each binding a workflow,
  templates, rules, and rubrics — with no backing schema change.
- **Assessment findings + recommendation package**: The read-only deliverable of an Assessment case; an
  evaluation of a subject's current state scored against rubrics plus a recommendation, containing no
  mutating artifacts.
- **Enhancement delta-doc + impact analysis**: The deliverable of an Enhancement case; documentation of only
  what changes relative to a baseline, plus an analysis of downstream impact, each trace-linked to the
  baseline it derives from.
- **Baseline reference (trace link)**: The existing trace-link edge relationship connecting an Enhancement's
  delta and impact entries to the specific prior baseline artifacts they enhance; reused, not newly
  schema'd.
- **Case-type decision table**: The intake decision table that classifies a submission's attributes to a
  proposed case type, feeding the human confirm step.
- **Conditional-artifact decision table**: The decision table that adds required artifacts to a case's
  expected set based on submission attributes (e.g., a data-protection impact assessment when personal data
  is present).
- **Feature aggregate (versioned)**: The Feature entity carrying a version/timestamp used for the optimistic
  single-active-mutating-case check at Case creation; a mutating case advances it and a terminal case
  releases the slot.
- **Assessment rule / rubric set**: The read-only-enforcement rule and the Assessment scoring rubrics
  authored in the catalog for the Assessment case type.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: All three case types (Initiation, Assessment, Enhancement) run end to end to Delivered from a
  confirmed classification, demonstrating the full case-type set is executable.
- **SC-002**: Intake classifies a from-scratch, an evaluation, and a change submission to Initiation,
  Assessment, and Enhancement respectively, each pausing for human confirmation before Case creation, with
  100% of confirmations and overrides recorded as auditable decisions.
- **SC-003**: A delivered Assessment package contains only findings and a recommendation, with zero mutating
  artifacts written and zero baseline changes made — verified by inspecting the case's produced artifacts
  and the Feature's baseline.
- **SC-004**: A delivered Enhancement package contains delta-docs plus an impact analysis, and 100% of its
  delta and impact entries are trace-linked to the specific baseline artifacts they derive from.
- **SC-005**: Of two concurrent mutating-case creation attempts on one Feature, exactly one succeeds and the
  other is rejected as a conflict; and an Assessment case on the same Feature is admitted 100% of the time.
- **SC-006**: A submission flagged as involving personal data results in a data-protection impact assessment
  being added to its required artifact set, and the case cannot be delivered without it.
- **SC-007**: Adding both new case types introduces zero new database tables — confirmed by comparing the
  schema before and after — with both case types existing solely as catalog Definitions.
- **SC-008**: Every prior-phase success criterion (zero-manual-edit delivery, byte-identical replay,
  zero cross-workspace leaks, integrity-stamped package, cost-budget enforcement) continues to pass
  unchanged for the two new case types.

## Assumptions

- The one-active-case-per-Feature rule (Q2) is **resolved**: in v1 it means one active **mutating** Case per
  Feature, enforced by an **optimistic** version/timestamp check on the Feature aggregate at Case-creation
  time — not a pessimistic row lock held across the Case lifecycle. Assessment cases are read-only and
  therefore **exempt**. Two mutating cases on one Feature is treated as a rare user error surfaced as a
  conflict, not as routine contention to be queued.
- **Assessment is read-only by construction**: it evaluates an existing subject and produces a findings +
  recommendation package only, never mutating artifacts or baselines. This is what justifies its exemption
  from the concurrency guard.
- **Enhancement anchors to a delivered baseline** and references prior artifacts through the existing
  trace-link edges from Phase 1; it does not duplicate the baseline, and an Enhancement without a baseline
  is rejected at intake.
- The **§16 zero-schema-change constraint** is a hard target: both case types are authored purely as catalog
  Definitions and add no database tables. This phase's catalog additions are approximately two case-type,
  two workflow, eight template, two rule, and four rubric Definitions.
- Intake routing uses **two decision tables** — a case-type classification table and a conditional-artifact
  table — both feeding a **human confirm** step; the human is the authority of record on the final case
  type, and the machine proposal is advisory.
- Content remains **single-language** in v1, consistent with prior phases; the locale dimension on
  Definitions is carried forward, not extended.
- Persona behavior is unchanged from Phase 2: personas remain stateless, never call one another, never
  approve their own output, never write knowledge directly, and never alter workflow routing — regardless
  of case type.
- Team and deployment assumptions are unchanged from prior phases (small team, single-region cloud, logical
  per-workspace isolation).

## Dependencies

- **Phase 3 — Knowledge Layer (spec `003-knowledge-layer`)**: **hard prerequisite**, being authored in this
  same batch. The Assessment and Enhancement rubrics assume knowledge injection is available to the personas
  that execute the new case types (§18 order). This phase is unblocked once Phase 3's spec exists.
  STATUS: specified (in this batch).
- **Phase 1 — Catalog Spine + Initiation Case Type (spec `001-catalog-initiation-case`)**: reused for the
  trace-link / edge tables (E1.7) that Enhancement uses for baseline referencing, the DMN decision engine
  and intake classify step (E1.3 / E1.5) that routing builds on, and the Case / Feature aggregate that the
  optimistic single-active-mutating-case guard (Q2) checks against. STATUS: specified.
- **Phase 2 — Full Persona Suite + Parallel Execution (spec `002-full-persona-parallel`)**: the full persona
  suite executes the new case types' workflows. STATUS: specified.
- **No forward or unspecified dependency**: all upstream phases (1, 2, 3) are specified — Phase 3 within this
  same batch — so this phase has no unresolved cross-phase blocker once Phase 3's spec is in place.
- **Q2 is RESOLVED (research-backed)**: the optimistic-concurrency ruling above governs FR-012 and User
  Story 4; no open concurrency question remains for this phase.
