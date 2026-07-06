# Feature Specification: Catalog Spine + Initiation Case Type

**Feature Branch**: `001-catalog-initiation-case`

**Created**: 2026-07-06

**Status**: Draft

**Input**: Phase 1 of the D2OS phased implementation plan — the end-to-end thin slice that takes a structured problem submission through a governed, audited, replayable pipeline of AI personas and delivers a hash-stamped, versioned Execution Package with a Handover Record.

## Clarifications

### Session 2026-07-06

- Q: What does replay "reconstruction matches the delivered output" mean given AI non-determinism? → A: Recorded-output replay — replay verifies the stored snapshot resolves to the exact recorded output that was delivered (byte-identical against what was stored), not a fresh model call.
- Q: How many validation retries before a persona output escalates to a human? → A: 2 automated revise attempts after the original (3 total generations), then escalate.
- Q: What rubric score counts as a first-pass validation success? → A: Weighted rubric score ≥ 80% AND no criterion marked critical fails.
- Q: What is the minimum required content of the Handover Record? → A: Full provenance set — package contents index, source submission reference, definition-version snapshot, per-artifact integrity hashes, decision/approval log, and named receiving-team owner + next action.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Submit a problem and receive a delivered Execution Package (Priority: P1)

A requester (workspace member) fills in a structured Problem Form describing the work they
need documented. The system classifies the submission, opens a Case, runs it end to end
through the Initiation pipeline of three AI personas, and delivers a complete, versioned
Execution Package containing the generated documentation artifacts plus a Handover Record —
without anyone manually editing the underlying data.

**Why this priority**: This is the product's reason to exist — turning a structured submission
into a finished, auditable documentation package. Every other story is a guarantee *about* this
flow; this story *is* the flow. If only this is built, D2OS already delivers its core value.

**Independent Test**: Submit a valid demo problem, confirm the Case transitions
Submitted → … → Delivered with zero manual database edits, and confirm the delivered package
contains the expected artifacts and a Handover Record.

**Acceptance Scenarios**:

1. **Given** a valid structured problem submission, **When** the requester submits it, **Then**
   the system classifies it, opens a Case, and pins the exact definition versions in use for the
   life of that Case.
2. **Given** a Case has been planned, **When** the Initiation pipeline runs, **Then** three AI
   personas execute in sequence and each produces artifacts that pass validation before the next
   persona begins.
3. **Given** all pipeline steps have completed, **When** the Case reaches delivery, **Then** the
   requester receives an Execution Package with a verifiable integrity stamp and a Handover
   Record, and the Case is marked Delivered.

---

### User Story 2 - Every AI output is reproducible on replay (Priority: P1)

An auditor or owner needs to prove that a delivered package was produced from specific,
recorded inputs. They run a replay against a completed Case and the system reconstructs each AI
output from the stored snapshots (prompt version, model version, injected knowledge, inputs) and
shows that the reconstruction matches what was delivered.

**Why this priority**: Reproducibility and auditability are the non-negotiable governance
premise of D2OS (Constitution Principle II). A pipeline that produces artifacts but cannot prove
how is not an acceptable v1 — this guarantee is co-equal with delivery itself.

**Independent Test**: Take a completed Case, run the replay harness, and confirm every AI-drafted
artifact can be reconstructed from stored snapshots with outputs diffed against the originals.

**Acceptance Scenarios**:

1. **Given** a completed Case, **When** an auditor runs a replay, **Then** every AI output is
   regenerated from recorded snapshots and reported as matching or differing.
2. **Given** an AI step executed, **When** its record is inspected, **Then** it shows the prompt
   version, model identity/version, injected knowledge, and inputs used for that specific step.
3. **Given** a state transition occurred, **When** the audit trail is inspected, **Then** there
   is a tamper-evident record of what changed, when, and under what inputs.

---

### User Story 3 - Tenant isolation is guaranteed (Priority: P1)

Two workspaces use the system concurrently. No data, artifact, submission, or knowledge from one
workspace is ever visible to another. A requester in Workspace A cannot read or reference
anything belonging to Workspace B, and automated tests continuously prove this.

**Why this priority**: Workspace isolation is a hard boundary (Constitution Principle IV) and a
procurement-blocking requirement for the regulated customers D2OS targets. A leak is a
disqualifying failure, so this must ship in the first slice, not later.

**Independent Test**: Run the cross-tenant leakage test suite against a two-workspace fixture and
confirm every cross-workspace access attempt is denied.

**Acceptance Scenarios**:

1. **Given** data owned by Workspace B, **When** a Workspace A user attempts to access it, **Then**
   access is denied and the attempt leaves no partial disclosure.
2. **Given** submissions and artifacts at rest, **When** they are stored, **Then** they are
   encrypted and scoped to their owning workspace.

---

### User Story 4 - Malicious submissions cannot hijack the pipeline (Priority: P2)

A submission contains text crafted to look like instructions to the AI (a prompt-injection
attempt). The system treats all submission text strictly as data, and an output check detects and
blocks any sign that injected instructions influenced a persona's output before that output can
advance through the pipeline.

**Why this priority**: Problem-submission text is the primary injection surface (Constitution
Principle II / AD-12). It is P2 rather than P1 only because it hardens the P1 flow rather than
delivering it — but it must be present for the flow to be trustworthy in v1.

**Independent Test**: Submit a seeded malicious problem designed to subvert a persona and confirm
the injection-symptom check blocks it from advancing.

**Acceptance Scenarios**:

1. **Given** a submission containing injection-style instructions, **When** it enters the pipeline,
   **Then** its text is handled as data and never executed as instructions.
2. **Given** a persona output shows symptoms of successful injection, **When** it is validated,
   **Then** the output is blocked from advancing and the event is recorded.

---

### User Story 5 - Cost and quality are observable (Priority: P3)

An owner watches a minimal dashboard showing, per Case, whether AI outputs passed validation on
first attempt, how complete the delivered package is, and how much the Case cost in AI usage. A
Case that exceeds its allotted cost budget is suspended rather than allowed to run away.

**Why this priority**: Instrumentation and a cost ceiling make the system operable and safe to run
repeatedly, but the core value is delivered without them. They are the first observability layer,
hence P3.

**Independent Test**: Run several Cases and confirm the three metrics emit to the dashboard, and
that a Case breaching its cost budget is suspended.

**Acceptance Scenarios**:

1. **Given** Cases have run, **When** the dashboard is viewed, **Then** first-pass validation rate,
   package completeness, and per-Case cost are shown.
2. **Given** a Case reaches its cost ceiling, **When** the next AI step would run, **Then** the Case
   is suspended instead.

---

### Edge Cases

- A persona's output repeatedly fails validation → the pipeline retries within a bounded limit and
  then escalates rather than looping indefinitely.
- The AI pipeline hits a wait/asynchronous boundary → the Case moves to a waiting state and resumes
  correctly when work is available, without losing position.
- A submission cannot be confidently classified → a human confirmation step resolves the
  classification before the Case proceeds.
- A Case exceeds its cost budget mid-run → it is suspended, not silently truncated.
- Concurrent attempts to open a second mutating Case on the same Feature → prevented by a
  version/timestamp conflict check, not a blocking lock.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST accept a structured problem submission via a Problem Form and persist it
  scoped to the submitting workspace.
- **FR-002**: System MUST classify each submission (with a human confirmation step when
  classification is not confident) before opening a Case.
- **FR-003**: System MUST open a Case that, at planning time, freezes the exact versions of every
  definition it uses so the Case never changes underneath itself for its lifetime.
- **FR-004**: System MUST execute the Initiation pipeline as an ordered sequence of three AI
  personas, where each persona's output must pass validation before the next begins.
- **FR-005**: System MUST validate each AI output for structural correctness and quality (rubric
  scoring). An output passes first-pass validation when its weighted rubric score is ≥ 80% AND no
  criterion marked critical fails. A failing output MUST be re-driven up to 2 automated revise
  attempts after the original (3 total generations); if it still fails, the Case MUST escalate to a
  human rather than retry further.
- **FR-006**: System MUST record, for every AI step, a snapshot of the prompt version, model
  identity/version, injected knowledge, and inputs used, sufficient to reproduce that output.
- **FR-007**: System MUST record every Case state transition and decision as a tamper-evident,
  append-only audit entry written in the same transaction as the change it describes.
- **FR-008**: System MUST assemble delivered artifacts into an Execution Package with a verifiable
  integrity stamp and MUST include a Handover Record. The Handover Record MUST contain, at minimum:
  a package contents index, a reference to the source submission, the definition-version snapshot,
  per-artifact integrity hashes, the decision/approval log, and a named receiving-team owner with a
  stated next action.
- **FR-009**: System MUST reconstruct every AI output of a completed Case from its stored snapshot
  on replay and confirm the reconstruction is byte-identical to the recorded delivered output.
  Replay verifies against the stored recorded output (not a fresh model call), so a match is exact;
  any difference is a reproducibility failure.
- **FR-010**: System MUST enforce hard workspace isolation on all data, artifacts, submissions, and
  knowledge, and MUST continuously verify isolation via an automated leakage test.
- **FR-011**: System MUST treat all problem-submission text strictly as data, never as
  instructions, and MUST block persona outputs that show symptoms of prompt injection from
  advancing.
- **FR-012**: System MUST enforce a per-Case AI cost budget, suspending a Case that would exceed it.
- **FR-013**: System MUST encrypt submissions and artifacts at rest.
- **FR-014**: System MUST route AI persona execution exclusively through a single provider-agnostic
  gateway that logs every call.
- **FR-015**: System MUST emit, at minimum, first-pass validation rate, package completeness, and
  per-Case cost, and present them on a minimal dashboard.
- **FR-016**: System MUST prevent a second concurrently-mutating Case on the same Feature via an
  optimistic version/timestamp conflict check.
- **FR-017**: Personas MUST be stateless — they never call one another, never approve their own
  output, and never write to the knowledge layer directly.

### Key Entities *(include if feature involves data)*

- **Definition (versioned asset)**: An immutable, versioned unit of the catalog (case type,
  workflow, persona, playbook, template, rule, rubric, prompt). Carries a language/locale
  dimension. Once published, never mutated — only superseded by a new version.
- **Workspace / Project / Version / Feature**: The tenancy and organizational hierarchy; the
  workspace is the hard isolation boundary and everything is scoped to it.
- **Problem Submission**: The structured input from a requester; always treated as data.
- **Case (instance)**: A running execution of a case type against a submission; pins a frozen
  snapshot of definition versions at planning time and carries a lifecycle state.
- **Case Definition Snapshot**: The frozen set of definition versions bound to a Case.
- **Persona / Operation / Activity / Action records (runtime)**: The audited runtime trace of the
  pipeline; each carries provenance to the definitions that produced it.
- **Artifact (+ Revision)**: A generated documentation output and its versioned revisions, stored
  with an integrity hash.
- **Execution Package**: The assembled, integrity-stamped deliverable containing the artifacts.
- **Handover Record**: The record enabling an external team to act on the package without
  ambiguity; minimally carries a contents index, source-submission reference, definition-version
  snapshot, per-artifact integrity hashes, decision/approval log, and a named owner + next action.
- **Decision / Audit Entry / Event**: The tamper-evident governance and audit trail.
- **Trace link / Dependency (edges)**: Generalized relationships between artifacts and definitions
  that make the pipeline traceable and (later) projectable to a graph.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A demonstration submission proceeds from Submitted to Delivered with zero manual data
  edits.
- **SC-002**: 100% of AI outputs in a completed Case are reconstructable from stored snapshots on
  replay and are byte-identical to the recorded delivered outputs.
- **SC-003**: 100% of cross-workspace access attempts in the leakage test are denied (zero leaks).
- **SC-004**: A seeded malicious submission is blocked from advancing 100% of the time.
- **SC-005**: The delivered package's integrity stamp verifies successfully, and the package
  contains a Handover Record.
- **SC-006**: The three operational metrics (first-pass validation rate, package completeness,
  per-Case cost) are emitting and visible on the dashboard.
- **SC-007**: A Case that exceeds its cost budget is suspended in 100% of cases rather than
  continuing.

## Assumptions

- Scope is the **Initiation** case type only, executed as a **single-threaded, sequential**
  three-persona pipeline; the full persona suite and parallel execution are out of scope (later
  phases).
- The catalog is **seeded from the pre-audited v0 template library**; templates are revised or
  authored (two known gaps: Task Breakdown, Handover Record) rather than created wholesale.
- **Content is single-language** in v1, but a language/locale dimension exists on definitions from
  the start so localization is not a later retro-fit.
- The runtime persists no separate action-execution row; an action item references its action
  definition directly (definition-time-only action granularity).
- Deployment is **single-region cloud with logical per-workspace isolation**; on-prem is out of
  scope.
- The team is **1–3 developers plus an architect/owner**, with no parallel workstreams assumed for
  this phase.
- AI access is **provider-agnostic through a gateway**; specific model/provider choice is not fixed
  by this spec.
