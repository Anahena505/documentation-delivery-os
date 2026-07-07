# Feature Specification: Full Persona Suite + Parallel Execution

**Feature Branch**: `002-full-persona-parallel`

**Created**: 2026-07-07

**Status**: Draft

**Input**: Phase 2 of the D2OS phased implementation plan — bring the full documentation persona suite operational on the Initiation case type, run the analysis specialists concurrently through a parallel workflow block, reconcile their outputs through a cross-artifact consistency check, and establish the system's load posture (concurrency, throughput, latency) under realistic multi-case pressure.

## Clarifications

### Session 2026-07-07

- Q: When one persona in the parallel block fails validation (after its bounded revise attempts) while its siblings succeed, what happens to the parallel block? → A: The failed persona escalates to a human as in Phase 1; its sibling outputs are retained, and the join waits for the escalation to resolve before the Consistency-Check runs — no sibling work is discarded and the block does not fail as a whole.
- Q: What counts as a consistency "conflict" that the Consistency-Check subprocess must surface? → A: Two tiers — deterministic cross-checks (a referenced entity, endpoint, or requirement id in one artifact has no definition in the artifact that owns it; contradictory stated values for the same named attribute) always block; a semantic consistency review (an AI operation scoring cross-artifact coherence against a rubric) produces advisory findings that are recorded and gate-visible but escalate rather than hard-block.
- Q: What is the concurrency unit for the parallel block — OS threads, or engine jobs? → A: Independent asynchronous engine jobs drawn from a bounded worker pool; each parallel persona runs as its own job so a slow or waiting persona never blocks its siblings, and the pool size caps simultaneous AI calls.
- Q: For the NFR-1 "≥ 50 concurrent active cases without degradation" target, what does "without degradation" mean measurably? → A: At 50 concurrent active cases in one workspace, per-operation p95 latency stays within the NFR-3 bound (≤ 10 min excluding human waits) and user-visible progress events stay ≤ 5 s apart; no case stalls, deadlocks, or is dropped.
- Q: Uploaded attachments on a submission — must Phase 2 process their content, and how? → A: Yes. Attachment content passes through a sandboxed extraction/summarization pass that runs before any persona prompt is built; personas receive only the sanitized summary, never raw attachment bytes interpolated into a prompt (extends AD-12's data-not-instructions boundary to uploads).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - The full documentation persona suite produces a complete Initiation package (Priority: P1)

A requester submits an Initiation problem exactly as in Phase 1, but now the pipeline runs the complete
canonical Initiation shape: after the early sequential personas establish the business and architecture
baseline, the four analysis specialists (Security, UX, Data, Infrastructure) run concurrently, their
outputs are reconciled by a consistency check, and the delivery-planning persona assembles the final
package. The delivered Execution Package therefore contains the full breadth of documentation artifacts
the §8 persona roster is responsible for — not the three-persona thin slice.

**Why this priority**: This is the phase's headline value — turning the Phase 1 skeleton into the real
product. A single specialist persona missing or misbehaving means the delivered package is incomplete,
so the full suite executing correctly *is* the deliverable of this phase.

**Independent Test**: Submit a demo Initiation problem and confirm the Case reaches Delivered with an
Execution Package that contains an artifact from every persona in the canonical Initiation shape, each
having passed its own rubric before advancing.

**Acceptance Scenarios**:

1. **Given** a valid Initiation submission, **When** the pipeline runs end to end, **Then** every persona
   in the canonical Initiation shape executes, each produces its contracted artifact(s), and each
   artifact passes its persona-specific rubric before the pipeline advances past it.
2. **Given** the delivered package, **When** its contents index is inspected, **Then** it lists an
   artifact traceable to each persona (business analysis, architecture, security, UX, data, infrastructure,
   delivery plan, and the supporting documentation set), with no persona silently skipped.
3. **Given** any single persona output fails its rubric after the bounded revise attempts, **When** that
   happens, **Then** the Case escalates to a human for that persona rather than delivering an incomplete
   package, consistent with the Phase 1 escalation behavior.

---

### User Story 2 - Analysis specialists run in parallel without blocking one another (Priority: P1)

The Security, UX, Data, and Infrastructure personas have no dependency on each other's output — they all
consume the same upstream architecture baseline. The system runs them concurrently rather than in a slow
sequence, so a Case that would take four sequential specialist passes instead completes them in
overlapping time, and a specialist that stalls or enters a waiting state does not hold up its siblings.

**Why this priority**: Parallel execution is the defining capability of this phase (the phase is named for
it) and the prerequisite for the load posture in User Story 4. Without it the specialists serialize and
the throughput/latency targets are unreachable. It is co-equal P1 with the suite itself.

**Independent Test**: Run an Initiation case instrumented to record per-operation start/end times and
confirm the four specialist operations overlap in wall-clock time (are not strictly sequential), and that
the workflow join waits for all four before the consistency check begins.

**Acceptance Scenarios**:

1. **Given** the architecture baseline is complete, **When** the parallel block opens, **Then** the four
   specialist personas begin as independent concurrent units of work drawn from a bounded worker pool.
2. **Given** the four specialists are running, **When** one of them is slow or enters a waiting state,
   **Then** the other three continue and complete independently — no specialist blocks a sibling.
3. **Given** all four specialist outputs are ready, **When** the workflow join is reached, **Then** the
   pipeline proceeds to the Consistency-Check only after every branch has produced a validated output (or
   escalated), and no branch's output is discarded at the join.
4. **Given** the worker pool is at capacity, **When** more parallel operations are ready than the pool can
   run at once, **Then** the excess operations queue and run as capacity frees, and the Case still
   completes without loss.

---

### User Story 3 - Cross-artifact inconsistencies are caught before delivery (Priority: P1)

Because multiple personas now author artifacts that reference one another (an API endpoint named in the
security review, a data entity assumed by the infrastructure plan), the system runs a Consistency-Check
subprocess after the parallel join. Deterministic contradictions block the package; softer semantic
incoherence is surfaced as advisory findings for a human to weigh. A package cannot be delivered while a
hard, machine-detectable contradiction between its artifacts remains.

**Why this priority**: Parallel authoring creates a new failure mode Phase 1 never had — mutually
inconsistent artifacts produced independently. Catching that is what makes the parallel package
trustworthy, so it ships in the same phase as parallelism, not later.

**Independent Test**: Feed the consistency subprocess a case whose artifacts contain a seeded hard
contradiction (an entity referenced but never defined) and confirm it blocks delivery and records the
conflict; feed it a coherent case and confirm it passes.

**Acceptance Scenarios**:

1. **Given** artifacts from the parallel block, **When** the Consistency-Check runs, **Then** it performs
   deterministic cross-checks and a rubric-scored semantic coherence review, recording every finding as an
   auditable entry.
2. **Given** a deterministic contradiction between two artifacts, **When** it is detected, **Then** the
   package is blocked from advancing and the conflict is recorded with references to both artifacts.
3. **Given** only advisory semantic findings (no hard contradiction), **When** the check completes,
   **Then** the findings are recorded and made visible for human decision, and the pipeline escalates
   rather than silently proceeding or hard-blocking.

---

### User Story 4 - The system holds its load posture under realistic pressure (Priority: P2)

An operator needs confidence the platform survives real usage: many Cases active at once, a sustainable
monthly throughput, and bounded per-operation latency with a steady stream of progress updates so a
running Case never looks frozen. A load test drives the system to its target concurrency and confirms it
holds the stated bounds without stalls, dropped cases, or runaway latency.

**Why this priority**: The load posture makes the parallel system operable and safe to sell, but the core
value (a correct, consistent package) is delivered by US1–US3. It is P2 because it verifies the parallel
machinery rather than producing the deliverable.

**Independent Test**: Run a load test at the target concurrency for the workspace and confirm per-operation
p95 latency and progress-event cadence stay within bounds with zero stalled or dropped cases.

**Acceptance Scenarios**:

1. **Given** the target of at least 50 concurrent active Cases in one workspace, **When** the load test
   runs, **Then** per-operation p95 latency stays within its bound, progress events stay within their
   cadence, and no Case stalls, deadlocks, or is dropped.
2. **Given** a running Case, **When** it is executing any operation, **Then** user-visible progress events
   are emitted no more than 5 seconds apart so the Case is never perceived as frozen.
3. **Given** sustained submission over time, **When** throughput is measured, **Then** the workspace
   sustains at least the target monthly case volume without degradation.

---

### User Story 5 - Uploaded attachments cannot smuggle instructions into a persona (Priority: P2)

A submission arrives with file attachments whose contents could carry text crafted to look like
instructions to the AI. Before any persona prompt is constructed, attachment content is processed in a
sandboxed extraction/summarization pass, and personas receive only the sanitized summary — never raw
attachment bytes interpolated into a prompt. This extends the Phase 1 "submission text is data, not
instructions" boundary to the new upload surface.

**Why this priority**: Attachments are a new injection surface opened by richer intake in this phase. It is
P2 because it hardens the flow rather than delivering it, but it must be present for uploads to be
trustworthy — mirroring the Phase 1 injection-check priority.

**Independent Test**: Submit a case with a seeded malicious attachment and confirm its raw content never
reaches a persona prompt, only a sanitized summary does, and that any injection symptom is still caught by
the existing output check.

**Acceptance Scenarios**:

1. **Given** a submission with an attachment, **When** the pipeline prepares persona inputs, **Then** the
   attachment passes through a sandboxed extraction/summarization step before any prompt is built.
2. **Given** an attachment containing injection-style text, **When** a persona runs, **Then** the persona's
   prompt contains only the sanitized summary as data, never the raw attachment content as instructions.

---

### Edge Cases

- One parallel branch escalates to a human while its siblings succeed → siblings' validated outputs are
  retained, the join waits for the escalation to resolve, and no completed work is thrown away.
- Two parallel branches both fail validation → each escalates independently; the Case does not deliver
  until both are resolved.
- A parallel branch enters a wait state (needs human input) → the Case reflects the wait without blocking
  the sibling branches, and resumes that branch correctly when input arrives.
- The worker pool is saturated by concurrent Cases → new ready operations queue rather than being dropped,
  and no Case is starved indefinitely.
- The engine's own history and the domain Case state momentarily disagree during a parallel run → a
  reconciliation step brings them back into agreement keyed by Case identity, so the audit trail stays
  coherent.
- A consistency conflict is detected between an artifact from the parallel block and an earlier sequential
  artifact → it is still caught and blocks delivery, not only conflicts within the parallel set.
- An attachment cannot be parsed or is oversized → it is rejected or flagged at intake rather than passed
  through raw, and the Case records why.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST make the full documentation persona suite required by the canonical Initiation
  case type operational, each persona carrying its own charter, operation bindings, knowledge profile,
  prompt set, and validation rubric, so the delivered package covers the full breadth of Initiation
  documentation rather than the Phase 1 three-persona subset.
- **FR-002**: System MUST execute the four analysis specialist personas (security, user experience, data,
  infrastructure) concurrently after the architecture baseline is established, as independent units of
  work, such that a slow or waiting specialist does not block its siblings.
- **FR-003**: System MUST draw concurrent persona operations from a bounded worker pool, queueing excess
  ready operations when the pool is at capacity, so simultaneous AI calls are capped and no ready operation
  is dropped.
- **FR-004**: System MUST synchronize the parallel branches at a join that proceeds only when every branch
  has produced a validated output or escalated, without discarding any branch's output.
- **FR-005**: When a persona in the parallel block fails validation after its bounded revise attempts, the
  System MUST escalate that branch to a human (consistent with Phase 1 escalation) while retaining sibling
  outputs, rather than failing the whole block.
- **FR-006**: System MUST run a Consistency-Check subprocess after the parallel join that performs
  deterministic cross-artifact checks AND a rubric-scored semantic coherence review over the case's
  artifact set.
- **FR-007**: System MUST block a package from advancing when a deterministic cross-artifact contradiction
  is detected, and MUST record the conflict as an auditable entry referencing the conflicting artifacts.
- **FR-008**: System MUST record semantic consistency findings that are not hard contradictions as advisory,
  gate-visible entries and escalate for human decision rather than silently proceeding or hard-blocking.
- **FR-009**: System MUST reflect a parallel branch's wait state in the Case without blocking sibling
  branches, and MUST resume that branch correctly when the awaited work becomes available.
- **FR-010**: System MUST reconcile the workflow engine's execution history with domain Case state, keyed
  by Case identity, so that concurrent execution does not leave the audit trail inconsistent.
- **FR-011**: System MUST emit user-visible progress events no more than 5 seconds apart while a Case is
  executing an operation, including during parallel execution.
- **FR-012**: System MUST sustain at least 50 concurrent active Cases in a single workspace with
  per-operation p95 latency within its bound and no Case stalled, deadlocked, or dropped.
- **FR-013**: System MUST sustain at least the target monthly case throughput per workspace without
  degradation.
- **FR-014**: System MUST keep a single OperationExecution (excluding human waits) within its p95 latency
  bound under the target concurrent load.
- **FR-015**: System MUST process uploaded attachment content through a sandboxed extraction/summarization
  pass before any persona prompt is constructed, and MUST supply personas only the sanitized summary, never
  raw attachment content interpolated as instructions.
- **FR-016**: System MUST preserve all Phase 1 guarantees unchanged under parallel execution — per-step
  reproducibility snapshots, append-only audit in the same transaction as the change, workspace isolation,
  the injection-symptom output check, per-Case cost budget suspension, and byte-identical replay of every
  AI output.
- **FR-017**: System MUST enforce a per-workspace usage limit and roll per-Case cost up to a workspace-level
  budget view, so concurrent Cases in a workspace cannot collectively evade the cost ceiling.
- **FR-018**: Personas MUST remain stateless under concurrency — never calling one another, never approving
  their own output, never writing to the knowledge layer directly, and never altering workflow routing —
  regardless of running in parallel.
- **FR-019**: System MUST detect a consistency conflict between a parallel-block artifact and an earlier
  sequential artifact, not only conflicts within the parallel set.

### Key Entities *(include if feature involves data)*

- **Persona (expanded suite)**: The full set of documentation personas the Initiation case type invokes;
  each is an immutable versioned Definition with a charter, ordered operation bindings, knowledge profile,
  typed prompt set, and validation rubric. Phase 1's three placeholder personas are superseded by the real
  authored suite.
- **Parallel block / branch**: The workflow region in which the four analysis specialists execute
  concurrently; each branch is an independent unit of work with its own validated output.
- **Worker pool / engine job**: The bounded concurrency mechanism; each concurrent persona operation is a
  job the pool schedules, capping simultaneous AI calls.
- **Consistency-Check subprocess**: The reusable post-join step that reconciles the artifact set; produces
  deterministic (blocking) conflicts and semantic (advisory) findings, each an auditable record.
- **Consistency finding / conflict**: A recorded cross-artifact discrepancy with references to the involved
  artifacts and a tier (deterministic-blocking vs. semantic-advisory).
- **Attachment (sandboxed summary)**: The sanitized, extracted representation of an uploaded file that
  personas may consume as data; the raw upload is never interpolated into a prompt.
- **Reconciliation record**: The audit-coherence link keyed by Case identity that keeps engine history and
  domain Case state in agreement during concurrent execution.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A demonstration Initiation case runs the full canonical shape — including the parallel
  specialist block and the consistency join — from Submitted to Delivered with zero manual data edits.
- **SC-002**: The delivered package contains a validated artifact traceable to every persona in the
  canonical Initiation shape (100% persona coverage; zero silently skipped).
- **SC-003**: In an instrumented run, the four analysis specialists' operations overlap in wall-clock time
  (demonstrably not strictly sequential), and the join waits for all four.
- **SC-004**: A seeded hard cross-artifact contradiction is blocked from delivery 100% of the time, and a
  coherent case passes the consistency check.
- **SC-005**: At 50 concurrent active Cases in one workspace, per-operation p95 latency stays within its
  ≤ 10-minute bound (excluding human waits) and zero Cases stall, deadlock, or drop.
- **SC-006**: User-visible progress events are emitted no more than 5 seconds apart during execution,
  including throughout the parallel block.
- **SC-007**: A seeded malicious attachment's raw content never reaches a persona prompt; only its
  sanitized summary does, and the injection-symptom check still blocks any injected output.
- **SC-008**: Every Phase 1 success criterion (zero-manual-edit delivery, byte-identical replay,
  zero cross-workspace leaks, integrity-stamped package with Handover Record, cost-budget suspension)
  continues to pass unchanged under parallel execution.

## Assumptions

- Scope remains the **Initiation** case type only. The Assessment and Enhancement case types, governed
  review/approval gates as first-class subprocesses, and the knowledge layer are later phases (4, 5, 3
  respectively). The Review-Gate / Approval-Gate steps in the canonical Initiation shape continue to use
  the Phase 1 gate treatment; their first-class subprocess implementation is Phase 5.
- The **Knowledge Curator** persona's knowledge-writing and curation lifecycle is **out of scope** here and
  lands in Phase 3; Phase 2 covers the documentation-authoring personas required to execute the canonical
  Initiation shape. Personas continue to propose drafts only and never write knowledge directly.
- Persona content is authored by **revising the pre-audited v0 template/persona sources** where they exist
  and authoring the known gaps, rather than creating wholesale — continuing the Phase 1 catalog-governance
  and provenance discipline.
- Content remains **single-language** in v1; the locale dimension already present on Definitions is
  carried forward, not extended.
- The **concurrency unit** is asynchronous engine jobs from a bounded pool, not raw OS threads; pool size
  is a tuning parameter set to satisfy the load targets without exhausting AI-provider or database limits.
- Load targets are the standing non-functional targets: **≥ 50 concurrent active Cases per workspace**,
  **≥ 200 cases/month per workspace throughput**, **single OperationExecution ≤ 10 min p95** excluding
  human waits, and **progress events ≤ 5 s apart**.
- The team and deployment assumptions are unchanged from Phase 1 (small team, single-region cloud, logical
  per-workspace isolation), though larger teams could parallelize this phase with later ones.
- Attachment extraction covers common document formats; exotic or unparseable formats are rejected at
  intake rather than passed through, and binary execution of attachment content is never performed.
