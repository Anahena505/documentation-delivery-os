# Feature Specification: Knowledge Layer

**Feature Branch**: `003-knowledge-layer`

**Created**: 2026-07-07

**Status**: Draft

**Input**: Phase 3 of the D2OS phased implementation plan — stand up a governed KnowledgeItem lifecycle so persona operations retrieve scoped, workspace-isolated knowledge that is snapshotted per execution for exact replay, and so completed cases feed a lessons-learned capture flow that is curated and promoted through a default-deny governance gate before it can influence future work. This phase adds the knowledge core, per-execution injection snapshots, the capture→curation→publish lifecycle via the Knowledge Curator persona, and a knowledge-influence KPI.

## Clarifications

### Session 2026-07-07

- Q: Under what conditions may knowledge captured in one project become usable in another project? → A: Default-DENY. Project-confidential knowledge is never promotable across projects automatically; promotion is possible only through a fixed three-gate pipeline — (1) an automated sensitivity/PII pre-filter, THEN (2) a human redaction step performed by the Knowledge Curator persona, THEN (3) a workspace-owner "D4" governance gate. Redaction and version control are required steps, not optional.
- Q: What exactly is recorded on an OperationExecution when knowledge is injected? → A: An injection snapshot listing the exact item identity and exact version of every KnowledgeItem placed into that operation's inputs, captured at execution time, so a later replay reconstructs the same knowledge context byte-for-byte even if the underlying items have since changed or been deprecated.
- Q: When a KnowledgeItem is deprecated, what happens to executions that already used it? → A: History is never rewritten. Deprecation flags the past executions that consumed the now-deprecated item so they are discoverable and reviewable, but their recorded snapshots and outputs remain byte-identical and replayable exactly as they ran.
- Q: How is retrieval scoped so a persona in one workspace cannot see another workspace's knowledge? → A: Retrieval is scoped by a workspace-anchored scope lattice plus tags and the requesting persona's knowledge profile; the vector index is partitioned per workspace, and the AI gateway independently asserts the caller's workspace scope on every injection so a scope error cannot leak cross-workspace knowledge into a prompt.
- Q: What does the knowledge-influence KPI measure? → A: The rubric-score delta of a persona operation run with a candidate KnowledgeItem injected versus the same operation run without it, so the marginal value each item contributes is measurable rather than assumed.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Scoped knowledge is injected into persona operations and snapshotted for exact replay (Priority: P1)

A persona operation runs as part of a Case exactly as in Phase 2, but now, before its prompt is built, the
system retrieves the KnowledgeItems that are in scope for that workspace, match the operation's tags, and
fit the persona's knowledge profile, and injects them into the operation's inputs. At execution time the
system records an injection snapshot — the exact identity and version of every item injected — onto that
OperationExecution, so a later replay reconstructs the identical knowledge context even if those items have
since changed.

**Why this priority**: Scoped retrieval with per-execution snapshots is the foundation the rest of the phase
stands on — capture, deprecation, and the influence KPI all reference the same snapshotted injection record.
Without it, injected knowledge is neither reproducible nor auditable, so Phase 1's replay guarantee would
break the moment knowledge entered a prompt. It is the phase's headline P1.

**Independent Test**: Seed a workspace's knowledge set, run an Initiation persona operation, and confirm the
retrieved items are workspace-scoped and tag/profile-matched, that an injection snapshot recording each
item's exact version is attached to the OperationExecution, and that replaying the execution reproduces the
same knowledge context byte-for-byte.

**Acceptance Scenarios**:

1. **Given** a workspace with a seeded KnowledgeItem set, **When** a persona operation is prepared, **Then**
   the retrieval step returns only items in the workspace's scope that match the operation's tags and the
   persona's knowledge profile, and no out-of-scope item is injected.
2. **Given** a persona operation with injected knowledge, **When** the operation executes, **Then** an
   injection snapshot recording the exact identity and version of every injected item is written onto that
   OperationExecution in the same transaction as the execution record.
3. **Given** an executed operation whose injected items were later edited or deprecated, **When** the
   execution is replayed, **Then** the replay reconstructs the identical injected knowledge context from the
   snapshot and reproduces the recorded output byte-for-byte, independent of the items' current state.
4. **Given** a retrieval request carrying one workspace's scope, **When** the AI gateway builds the prompt,
   **Then** the gateway independently asserts the caller's workspace scope before injection, and a scope
   mismatch is refused rather than allowed to leak another workspace's knowledge into the prompt.

---

### User Story 2 - Post-case lessons are captured, curated, and promoted through a default-deny gate (Priority: P1)

When a Case completes, a capture subprocess collects candidate lessons-learned from that case. These
candidates are not knowledge yet — they are project-confidential by default and cannot influence any other
project until they pass a fixed promotion pipeline: an automated sensitivity/PII pre-filter, then a human
redaction step performed by the Knowledge Curator persona against a curation rubric, then a workspace-owner
"D4" governance gate. Only after all three gates approve — with sensitive fields excluded by default and the
redaction version-controlled — does a candidate become a published, promotable KnowledgeItem.

**Why this priority**: This is the governance heart of the phase and the resolution of the cross-project
knowledge-leakage question (Q5). Capture without the default-deny gate would let confidential project
material bleed across projects — the exact failure the phase exists to prevent. It is co-equal P1 with
scoped retrieval.

**Independent Test**: Complete a demo Case, confirm the capture subprocess produces candidate lessons that
start project-confidential and non-promotable, then drive one candidate through pre-filter → Curator
redaction → D4 gate and confirm it becomes a published KnowledgeItem with sensitive fields excluded and the
redaction recorded as a new version; confirm a candidate that fails any gate is not published.

**Acceptance Scenarios**:

1. **Given** a completed Case, **When** the capture subprocess runs, **Then** it produces candidate
   lessons-learned that are marked project-confidential and non-promotable by default, with no automatic
   cross-project visibility.
2. **Given** a capture candidate, **When** it enters the promotion pipeline, **Then** it must pass the
   automated sensitivity/PII pre-filter, then the Knowledge Curator's human redaction step, then the
   workspace-owner D4 gate — in that order — before it can be published.
3. **Given** the automated pre-filter flags sensitive or PII content, **When** the candidate reaches the
   Curator, **Then** the flagged content feeds the redaction step and sensitive fields are excluded by
   default rather than carried forward.
4. **Given** the Curator has redacted a candidate, **When** the redaction is saved, **Then** it is recorded
   as a new version under version control, preserving the pre-redaction version for audit, and the D4 gate
   reviews the redacted version.
5. **Given** a candidate that any gate rejects, **When** the pipeline resolves, **Then** the candidate is
   not published as a promotable KnowledgeItem and the rejection is recorded with its reason.

---

### User Story 3 - Deprecating a KnowledgeItem flags its past executions without rewriting history (Priority: P2)

A published KnowledgeItem is later found to be wrong, stale, or superseded, and a workspace owner deprecates
it. Deprecation stops the item from being retrieved into new operations, and it flags every past
OperationExecution whose injection snapshot referenced the now-deprecated item so those executions are
discoverable for review — but it never alters the historical snapshots or outputs, which remain
byte-identical and replayable exactly as they originally ran.

**Why this priority**: Deprecation makes the knowledge base maintainable and lets operators find work that
leaned on knowledge later judged unsound, which is essential once knowledge influences outputs. It is P2
because the core injectable, snapshotted, governed knowledge lifecycle (US1–US2) delivers the value; this
protects it over time.

**Independent Test**: Run an operation that injects a given KnowledgeItem, deprecate that item, and confirm
the past execution is flagged as knowledge-affected and surfaced for review, that the item is no longer
retrieved into new operations, and that replaying the flagged execution still reproduces its original output
byte-for-byte.

**Acceptance Scenarios**:

1. **Given** a published KnowledgeItem in active use, **When** a workspace owner deprecates it, **Then** the
   item is no longer eligible for retrieval into new persona operations.
2. **Given** past executions whose injection snapshots referenced the item, **When** it is deprecated,
   **Then** each such execution is flagged as affected and made discoverable for review, without modifying
   its snapshot or recorded output.
3. **Given** a flagged past execution, **When** it is replayed, **Then** it reproduces its original output
   byte-for-byte from the preserved snapshot, confirming deprecation did not rewrite history.

---

### User Story 4 - A knowledge-influence KPI shows each item's rubric-score contribution (Priority: P3)

An operator needs to know whether a KnowledgeItem actually helps. The system computes a knowledge-influence
metric: the rubric-score delta of a persona operation run with a candidate item injected versus the same
operation run without it. The metric makes each item's marginal contribution measurable, so low- or
negative-value items can be identified and retired instead of being assumed useful.

**Why this priority**: The influence KPI turns knowledge management from faith into evidence, but it reports
on the lifecycle rather than delivering it. It is P3 because the phase's shippable value is a governed,
injectable, snapshotted knowledge base; the KPI improves stewardship of that base.

**Independent Test**: For a chosen KnowledgeItem, run an evaluation operation with and without the item
injected against the same rubric and confirm the system emits a knowledge-influence value equal to the
rubric-score delta, attributable to that item.

**Acceptance Scenarios**:

1. **Given** a candidate KnowledgeItem and a persona operation, **When** the influence evaluation runs,
   **Then** the system executes the operation both with and without the item injected against the same
   rubric and records both rubric scores.
2. **Given** the two rubric scores, **When** the metric is computed, **Then** the system emits a
   knowledge-influence value equal to the with-minus-without rubric-score delta, attributed to that item.
3. **Given** the influence metric over the item set, **When** an operator reviews it, **Then** items whose
   measured influence is low or negative are identifiable for deprecation.

---

### Edge Cases

- A retrieval request scoped to workspace A somehow references an item owned by workspace B → the per-workspace
  partitioned index and the gateway's independent workspace-scope assertion both reject it, so no
  cross-workspace knowledge is injected into the prompt (defense in depth, not a single check).
- A KnowledgeItem is edited (new version) after an execution injected an earlier version → the execution's
  snapshot still points at the exact injected version, and replay uses the snapshotted version, not the
  current one.
- A capture candidate contains PII the automated pre-filter misses → the human redaction step is still
  required and remains the authoritative exclusion point; sensitive fields are excluded by default, so a
  pre-filter miss does not by itself publish PII.
- The D4 gate approver is the same person as the Curator → the roles remain distinct governance steps; the
  D4 gate is a separate approval that cannot be self-satisfied by the redaction step alone.
- An item is deprecated while an operation that already retrieved it is mid-flight → the in-flight operation
  keeps the version it snapshotted; only subsequent retrievals exclude the deprecated item.
- The influence evaluation is requested for an item never injected anywhere → the metric is reported as not
  yet measurable rather than fabricated.
- A candidate is rejected at the D4 gate after Curator redaction → it stays project-confidential and
  non-promotable, and the rejection with its reason is recorded; no partially-promoted state leaks. The
  rejection is terminal in v1 (no resubmit loop) and the case is not re-harvested — see FR-013 v1 scope.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST provide a KnowledgeItem as an immutable, versioned entity carrying its content,
  scope, tags, and lifecycle state, such that any change produces a new version and prior versions remain
  retrievable for audit and replay.
- **FR-002**: System MUST scope knowledge retrieval by a workspace-anchored scope lattice combined with the
  operation's tags and the requesting persona's knowledge profile, returning only items a given persona
  operation is entitled to in its workspace.
- **FR-003**: System MUST expose knowledge retrieval as a defined service interface consumable by persona
  operations, taking scope, tags, and persona identity as inputs and returning the matching item set.
- **FR-004**: System MUST partition the knowledge vector index per workspace so that similarity retrieval
  cannot return another workspace's items.
- **FR-005**: The AI gateway MUST independently assert the caller's workspace scope on every knowledge
  injection and refuse a scope mismatch, so a scope error cannot leak cross-workspace knowledge into a
  prompt even if upstream retrieval were wrong.
- **FR-006**: System MUST record an injection snapshot on every OperationExecution that injected knowledge,
  listing the exact identity and exact version of each injected KnowledgeItem, written in the same
  transaction as the execution record.
- **FR-007**: System MUST reconstruct the injected knowledge context for a replayed OperationExecution from
  its injection snapshot, using the snapshotted item versions rather than current state, so replay of a
  knowledge-injected execution remains byte-identical to Phase 1's replay guarantee.
- **FR-008**: System MUST run a case-end capture subprocess that produces candidate lessons-learned from a
  completed Case, marking each candidate project-confidential and non-promotable by default with no
  automatic cross-project visibility.
- **FR-009**: System MUST require every capture candidate to pass, in order, an automated sensitivity/PII
  pre-filter, then a redaction step, then a workspace-owner D4 governance gate, before it may be published
  as a promotable KnowledgeItem (default-deny promotion).
  - **v1 scope (accepted deviation, T036):** the automated capture path produces the redaction
    *deterministically* — the pre-filter has already excluded sensitive spans, so the redaction draft is
    the pre-filtered content saved as a new revision — rather than running the Knowledge Curator persona.
    The Curator persona/playbook/rubric/prompt are still seeded (FR-021) for provenance and for a later
    capture-time snapshot that runs the Curator op through the persona path. The **substantive human gate
    in v1 is D4**: the workspace owner reviews the redacted content before it can publish. The manual
    `POST /knowledge/candidates/{id}/redaction` endpoint remains available for a human-confirmed redaction.
    Rationale: the case's frozen `CaseDefinitionSnapshot` (Principle I) pins only the initiation suite, so
    `knowledge-curator` is not resolvable through the delivered case's persona path, and the snapshot is
    immutable.
- **FR-010**: System MUST feed the automated pre-filter's sensitivity/PII findings into the Curator's
  redaction step and MUST exclude sensitive fields by default rather than carrying them forward.
- **FR-011**: System MUST record each Curator redaction as a new version under version control, preserving
  the pre-redaction version for audit, and MUST present the redacted version (not the raw candidate) to the
  D4 gate.
- **FR-012**: System MUST evaluate the Knowledge Curator's redaction against a defined curation rubric before
  the D4 gate, consistent with the Phase 1/2 rubric-gated operation discipline.
  - **v1 scope (accepted deviation, T036):** rubric scoring applies only when the Curator op runs through
    the persona path. Because v1's automated capture path is deterministic (see FR-009) and does not run
    the Curator persona, **no rubric is scored on that path**; the curation rubric is seeded and wired for
    the later capture-time-snapshot refinement. This requirement is fully met only once that refinement
    lands; in v1, D4 workspace-owner review is the human quality gate.
- **FR-013**: System MUST record the outcome of every promotion gate, and when any gate rejects a candidate
  the System MUST leave it project-confidential and non-promotable and record the rejection with its reason,
  with no partially-promoted state.
  - **v1 scope (accepted design, gap-3): REJECT is terminal — there is no rework/resubmit loop.** A
    candidate rejected at any gate (PREFILTER, CURATION, or D4) moves to the terminal `REJECTED` state and
    stays there as an immutable audit record; the state machine exposes no path back into the pipeline, and
    `CaptureService` is idempotent per case (it will not re-harvest a case that already has a candidate), so
    a rejected lesson is not re-capturable for that case in v1. This is intentional: the rejection and its
    reason are the auditable outcome. A remediation loop (re-redact after a fixable D4 rejection, or a fresh
    capture revision) is a deferred enhancement, not a v1 requirement.
- **FR-014**: When a KnowledgeItem is deprecated, System MUST make it ineligible for retrieval into new
  persona operations while leaving in-flight operations that already snapshotted it unaffected.
- **FR-015**: On deprecation, System MUST flag every past OperationExecution whose injection snapshot
  referenced the item as knowledge-affected and make those executions discoverable for review, without
  modifying any historical snapshot or recorded output.
- **FR-016**: System MUST preserve byte-identical replay of any past execution after an item it used is
  edited or deprecated, so deprecation never rewrites history.
- **FR-017**: System MUST compute a knowledge-influence metric equal to the rubric-score delta of a persona
  operation run with a candidate KnowledgeItem injected versus the same operation run without it, attributed
  to that item.
- **FR-018**: System MUST emit the knowledge-influence metric so operators can identify low- or
  negative-influence items for deprecation, and MUST report the metric as not-yet-measurable for an item
  never injected rather than fabricating a value.
- **FR-019**: System MUST keep personas stateless with respect to the knowledge layer — personas propose
  capture candidates and (as the Curator) redact within the governed flow, but never publish or promote
  knowledge directly, never approve their own promotion, and never bypass the D4 gate.
- **FR-020**: System MUST preserve all Phase 1 and Phase 2 guarantees unchanged when knowledge is injected —
  append-only audit in the same transaction as the change, workspace isolation, per-Case cost budget, and
  byte-identical replay — with the injection snapshot recorded as part of that same audited execution.
- **FR-021**: System MUST seed an initial KnowledgeItem set, one Knowledge Curator persona, one curation
  Playbook, and one curation Rubric through the same catalog-governance and provenance discipline used for
  personas in Phases 1 and 2.

### Key Entities *(include if feature involves data)*

- **KnowledgeItem**: The immutable, versioned unit of governed knowledge; carries content, a
  workspace-anchored scope, tags, and a lifecycle state (candidate → published → deprecated). Every change
  yields a new version; prior versions are retained for audit and replay.
- **Scope lattice**: The workspace-anchored structure that determines which items are visible to which
  persona operations; combined with tags and the persona knowledge profile to bound retrieval.
- **Injection snapshot**: The per-execution record attached to an OperationExecution listing the exact
  identity and version of every KnowledgeItem injected into that operation, enabling byte-identical replay
  of the knowledge context.
- **Capture candidate**: A lessons-learned proposal emitted by the case-end capture subprocess; starts
  project-confidential and non-promotable, and is the input to the promotion pipeline.
- **Knowledge Curator persona**: The knowledge-owner analog; performs the human redaction step against a
  curation rubric and version-controls the redacted candidate — but does not itself publish or approve
  promotion.
- **D4 governance gate**: The workspace-owner (steward analog) approval that is the final, non-self-satisfiable
  gate a redacted candidate must pass to be published as a promotable KnowledgeItem.
- **Knowledge Retrieval Service**: The service interface that resolves scope + tags + persona profile to the
  matching KnowledgeItem set for injection, against the per-workspace partitioned index.
- **Knowledge-influence metric**: The recorded rubric-score delta (with-item minus without-item) attributed
  to a KnowledgeItem, used to steward the knowledge base.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: In a seeded workspace, a persona operation retrieves only workspace-scoped, tag- and
  profile-matched KnowledgeItems, with zero out-of-scope items injected across the demonstration set.
- **SC-002**: Every OperationExecution that injected knowledge carries an injection snapshot recording the
  exact version of each injected item (100% coverage; zero knowledge-injected executions without a snapshot).
- **SC-003**: Replaying a knowledge-injected execution reproduces its output byte-for-byte from the snapshot
  even after the injected items are edited or deprecated (100% replay fidelity).
- **SC-004**: A cross-workspace retrieval attempt injects zero of another workspace's items, blocked by both
  the partitioned index and the gateway workspace-scope assertion.
- **SC-005**: A demonstration completes the full capture → pre-filter → Curator redaction → D4 gate →
  publish flow, producing a promotable KnowledgeItem with sensitive fields excluded by default and the
  redaction recorded as a new version; a candidate that fails any gate is published zero times.
- **SC-006**: No capture candidate becomes cross-project promotable without passing all three gates in order
  (default-deny holds 100% of the time in the demonstration set).
- **SC-007**: Deprecating a KnowledgeItem removes it from new-operation retrieval and flags 100% of the
  past executions whose snapshots referenced it **at deprecation time**, while altering zero historical
  snapshots or outputs.
  - **v1 scope (accepted limitation, gap-2):** flagging is point-in-time — the deprecation transaction's
    insert-select flags exactly the executions committed when it runs. An in-flight operation whose
    envelope was built before deprecation but whose execution commits after it is not flagged (and FR-014
    permits that operation to keep its snapshot), so the "100%" is exhaustive for the single-node,
    single-threaded demonstration set but is not a real-time invariant under concurrent deprecation.
    History is never rewritten either way (FR-016 holds unconditionally). A reconciliation sweep
    (idempotent re-run of the flag insert-select) is the deferred path to eventual 100% — see research R8.
- **SC-008**: For a chosen KnowledgeItem, the system emits a knowledge-influence value equal to the measured
  with-minus-without rubric-score delta, and reports not-yet-measurable for an item never injected.
- **SC-009**: Every Phase 1 and Phase 2 success criterion (byte-identical replay, zero cross-workspace leaks,
  append-only audit, cost-budget suspension, full parallel Initiation delivery) continues to pass unchanged
  with the knowledge layer active.

## Assumptions

- Scope remains the **Initiation** case type only, consistent with Phases 1 and 2. The Assessment and
  Enhancement case types and first-class governed review/approval subprocesses remain later phases (4 and 5);
  the knowledge layer is added to the existing Initiation pipeline rather than expanding case-type coverage.
- **Q5 is resolved as default-DENY**: project-confidential knowledge is never promotable across projects
  automatically. The only promotion path is the fixed three-gate pipeline (automated sensitivity/PII
  pre-filter → human Curator redaction → workspace-owner D4 gate), and redaction with version control is a
  required step, not optional. The Knowledge Curator is the knowledge-owner analog; the D4 gate is the
  steward analog. These roles remain distinct and the D4 gate is not self-satisfiable.
- The **Knowledge Curator persona** and the **case-end capture subprocess** build on the full persona suite
  and subprocess machinery delivered in Phase 2; personas continue to propose and redact within the governed
  flow and never write or promote knowledge directly.
- **Injection snapshots** attach to the OperationExecution introduced in Phase 1 and are recorded in the same
  audited transaction as the execution; the Phase 1 replay harness is extended to reconstruct the knowledge
  context from the snapshot.
- Retrieval relies on the **per-workspace tenancy/isolation** established in Phase 1; the vector index is
  partitioned per workspace and the AI gateway asserts workspace scope independently, so isolation is
  enforced at more than one layer.
- Sensitive fields are **excluded by default** at redaction; the automated pre-filter is an assist that feeds
  redaction, not the authoritative exclusion point — the human Curator redaction step is authoritative.
- Content remains **single-language** in v1, carrying the existing locale dimension forward without extending
  it, consistent with Phases 1 and 2.
- The **knowledge-influence KPI** is computed against the same rubric machinery used to gate persona
  operations; it is reported for stewardship and does not itself gate delivery.
- Team and deployment assumptions are unchanged from Phases 1 and 2 (small team, single-region cloud, logical
  per-workspace isolation). In particular, deployment is **single-node** in v1: the case-end capture
  trigger's check-then-start (and the harvest's per-case idempotency) serialize correctly on one node but
  are not guarded by a DB/Flowable business-key uniqueness constraint, so a multi-node deployment could
  double-start capture. Accepted for v1 (gap-4); the D4 wait-releaser is nonetheless made duplicate-tolerant
  now, and full multi-node uniqueness backstops are deferred to the horizontal-scale workstream (research R6).

## Dependencies

- **Depends on Phase 2 (spec `002-full-persona-parallel`) — STATUS: specified.** The Knowledge Curator
  persona and the case-end capture subprocess require Phase 2's full persona suite and subprocess machinery
  (E2.1–E2.3). Because Phase 2 is specified, this dependency is satisfied.
- **Depends on Phase 1 (spec `001-catalog-initiation-case`) — STATUS: specified.** Injection snapshots attach
  to the OperationExecution from Phase 1 (E1.6); scoped retrieval relies on Phase 1's row-level workspace
  tenancy and isolation (E1.2); and the replay harness (E1.10 / NFR-6) is extended in this phase to
  reconstruct the injected knowledge context. Because Phase 1 is specified, this dependency is satisfied.
- **No forward or unspecified dependency.** All upstream phases (1 and 2) already have specifications, and the
  governing open question (Q5) is resolved and research-backed. This phase is therefore **unblocked** and can
  proceed to planning without waiting on any later phase.
