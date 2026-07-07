# Feature Specification: Governance & Review Gates

**Feature Branch**: `005-governance-review-gates`

**Created**: 2026-07-07

**Status**: Draft

**Input**: Phase 5 of the D2OS phased implementation plan — promote the review and approval steps that earlier phases handled inline into first-class, reusable governance subprocesses: Review-Gate and Approval-Gate with a real reviewer experience, versioned escalation policies with advisory SLA timers, a regeneration policy that re-opens the gates of directly dependent artifacts when an approved upstream artifact is revised, and audit hardening (tamper-evident hash-chaining, long-term retention, role-scoped package access) plus a verified disaster-recovery posture. Every human decision becomes a tamper-evident record of who decided what, when, and on what information.

## Clarifications

### Session 2026-07-07

- Q: When a reviewer disagrees with an AI-drafted artifact, may they edit its content in place? → A: No. In v1 the only path is gate-comment-and-regenerate: the reviewer records comments and triggers a regeneration, which produces a new immutable completion; the original AI-generated artifact is retained unchanged so the audit trail keeps every version. No in-system editing of AI-drafted artifact content exists.
- Q: When an already-approved upstream artifact is later revised, how far does gate re-opening propagate? → A: Only to DIRECT dependents along `DERIVES_FROM`/`SATISFIES` edges, identified by a decision table over the edge tables; transitive (indirect) dependents are flagged for manual review, not auto-reopened. A reopen is gated on a formal impact-assessment record (reason, scope, risk) — it is a deliberate, auditable Decision, not a silent side effect.
- Q: Do SLA timers enforce routing (auto-approve, auto-reassign) when a gate ages out? → A: No — SLAs are advisory in v1. A timer firing emits a notification and an escalation event and updates the visible escalation-policy status; it never auto-approves, auto-rejects, or auto-routes. Automatic routing is a documented future toggle, not built in v1.
- Q: What makes the audit stream tamper-evident beyond being append-only? → A: The AuditEntry stream is periodically hash-chained, so any retroactive alteration or deletion of a past entry breaks the chain and is detectable; verification of the chain is a first-class check.
- Q: What is the required long-term audit/case retention horizon (FR-014, SC-006)? → A: 7 years. This is the workspace-configurable retention floor; workspaces may configure a longer horizon but not shorter.
- Q: What are the target RPO/RTO for the disaster-recovery drill (FR-016, SC-007)? → A: RPO ≤ 15 minutes / RTO ≤ 1 hour.
- Q: How should advisory SLA escalation notifications (FR-010) be delivered? → A: In-app only in v1 — visible in the reviewer's workspace UI; no email or webhook delivery. Consistent with the advisory (non-enforced) posture and keeps delivery inside the existing event stream without adding an external channel dependency.
- Q: How often is the AuditEntry stream hash-chained / sealed into a segment (FR-013)? → A: Time-based: a new segment is sealed on a fixed hourly interval, giving predictable, bounded-latency tamper detection independent of entry volume.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Every decision flows through a first-class gate with a tamper-evident record (Priority: P1)

Where Phase 1 handled review and approval steps inline, this phase routes every governance decision (D4)
through a reusable Review-Gate or Approval-Gate subprocess with a real reviewer experience. A reviewer
opens a gate, sees the artifact(s) under review together with the exact information the decision is being
made on, and records a decision (approve, reject, request-changes) with rationale. The system captures a
tamper-evident record of who reviewed what, when, on what information, and what they decided — the
regulatory-grade audit unit that the rest of the phase builds on.

**Why this priority**: First-class gates are the phase's foundation — the escalation, regeneration, and
audit-hardening capabilities all attach to the gate machinery. Without gates as reusable subprocesses with
a proper decision record, none of the rest can exist. It is therefore the top priority.

**Independent Test**: Run a case to a review point and confirm the decision is made through a gate
subprocess with a reviewer UI, and that the resulting Decision record captures reviewer identity, the
artifacts and inputs reviewed, the timestamp, and the outcome — verifiable in the audit trail.

**Acceptance Scenarios**:

1. **Given** a case reaches a governance point, **When** the workflow runs, **Then** the decision is made
   through a Review-Gate or Approval-Gate subprocess (a reusable definition), not an inline step.
2. **Given** a reviewer opens a gate, **When** they view it, **Then** they see the artifact(s) under review
   and the exact inputs the decision is based on, and can record approve / reject / request-changes with
   rationale.
3. **Given** a decision is recorded, **When** the audit trail is inspected, **Then** it holds a Decision
   entry naming the reviewer, the reviewed artifacts and inputs, the time, and the outcome — a complete
   who/what/when/on-what-information/what-decision record.
4. **Given** the same gate definition, **When** it is used in different workflows or case types, **Then** it
   behaves consistently as a shared subprocess rather than being re-implemented per workflow.

---

### User Story 2 - Reviewers comment and regenerate rather than editing AI content (Priority: P1)

A reviewer who disagrees with an AI-drafted artifact cannot edit its text in place. Instead they record
comments and trigger a regeneration; the system produces a new immutable completion of the artifact while
retaining the original AI-generated version unchanged. This keeps every AI submission intact as an
immutable completion for audit and reproducibility, and keeps humans in a governing role (accept, reject,
direct regeneration) rather than silently rewriting machine output.

**Why this priority**: Comment-and-regenerate is the defining human-AI interaction contract of the gate
system and a hard regulatory-grade requirement — editing AI output in place would destroy the immutable,
replayable audit trail that Phase 1 established. It is co-equal P1 with the gates themselves.

**Independent Test**: At a gate, submit review comments and trigger regeneration; confirm a new artifact
completion is produced, the original AI completion is retained unchanged, both appear in the version
history/audit, and no path exists to overwrite the AI content directly.

**Acceptance Scenarios**:

1. **Given** an AI-drafted artifact at a gate, **When** a reviewer wants changes, **Then** the only
   available action is to record comments and trigger regeneration — there is no in-place content editor.
2. **Given** a regeneration is triggered, **When** it completes, **Then** a new immutable completion is
   created and the original AI-generated completion is retained unchanged, both visible in the artifact's
   history and audit trail.
3. **Given** a regenerated artifact, **When** it is produced, **Then** a delta report describes what changed
   from the prior completion, so the reviewer can see the effect of their comments.

---

### User Story 3 - Revising an approved upstream artifact re-opens its direct dependents' gates (Priority: P1)

When an artifact that was already approved is later revised, the approvals of the artifacts that directly
derive from or satisfy it can no longer be assumed to hold. The system identifies the direct
`DERIVES_FROM`/`SATISFIES` dependents via a decision table over the dependency edges and re-opens their
gates — but only after a formal impact-assessment record (reason, scope, risk) is captured, so the reopen
is a deliberate, auditable Decision. Transitive (indirect) dependents are flagged for manual review rather
than automatically reopened.

**Why this priority**: This closes the correctness gap that parallel, dependency-linked artifacts create —
a stale approval on a downstream artifact after its upstream changed. It is what makes the governance
system trustworthy across revisions, so it ships in the same phase as the gates, at P1.

**Independent Test**: Approve an upstream artifact and its direct dependents, revise the upstream, and
confirm the direct dependents' gates re-open only after an impact-assessment record is supplied, that
transitive dependents are flagged (not auto-reopened), and that each reopen is recorded as a Decision.

**Acceptance Scenarios**:

1. **Given** an approved upstream artifact with approved direct dependents, **When** the upstream is
   revised, **Then** the system identifies the direct `DERIVES_FROM`/`SATISFIES` dependents via a decision
   table over the edge tables as reopen candidates.
2. **Given** reopen candidates are identified, **When** a reopen is attempted, **Then** it is blocked until
   an impact-assessment record (reason, scope, risk) is captured, and the reopen is then recorded as an
   auditable Decision — never a silent side effect.
3. **Given** the upstream revision, **When** dependents are evaluated, **Then** only direct dependents are
   auto-reopened; transitive dependents are flagged for manual review and are not automatically reopened.
4. **Given** a reopened gate, **When** it is presented, **Then** a delta report shows what changed upstream
   so the reviewer can re-decide on an informed basis.

---

### User Story 4 - Advisory SLA timers escalate without auto-routing (Priority: P2)

A gate that sits too long should draw attention, but in v1 it must never make a decision on a human's
behalf. Each gate can carry a versioned escalation policy with role chains and SLA timers; when a timer
fires it emits a notification and an escalation event and updates the visible escalation-policy status, so
people can see which policies are active — but it never auto-approves, auto-rejects, or auto-reassigns.
This advisory posture builds trust before any enforced-SLA behavior is ever turned on.

**Why this priority**: Escalation keeps the gate system operable at scale (stuck gates surface instead of
stalling silently), but it governs timeliness rather than the decision itself, so it is P2 — important
operability, layered on the P1 decision machinery.

**Independent Test**: Configure a gate with a short advisory SLA and an escalation policy, let the timer
expire, and confirm a notification and escalation event fire and the policy status becomes visible, while
the gate remains open and undecided (no automatic routing or approval).

**Acceptance Scenarios**:

1. **Given** a gate with an advisory SLA timer, **When** the timer expires, **Then** the system emits a
   notification and an escalation event and marks the escalation policy active/visible.
2. **Given** the timer has expired, **When** the escalation fires, **Then** the gate is not auto-approved,
   auto-rejected, or auto-reassigned — it remains open for a human decision.
3. **Given** a versioned escalation policy with a role chain, **When** escalation proceeds, **Then** it
   follows the policy's role chain and its status is visible so people can see which policies are active.
4. **Given** an escalation policy referencing a role with no current assignee, **When** it fires, **Then**
   the escalation is still recorded and surfaced rather than being silently dropped.

---

### User Story 5 - The audit stream is tamper-evident and access is role-scoped and retained (Priority: P2)

An auditor needs assurance that the historical record cannot be quietly altered, is kept for the required
period, and is only visible to those entitled to see it. The AuditEntry stream is periodically
hash-chained so any retroactive change or deletion breaks the chain and is detectable; audit and case data
are retained for the required long-term horizon under a workspace-configurable policy; and delivered
package access is scoped by role assignment so a package is not visible workspace-wide by default.

**Why this priority**: These hardening properties make the governance record defensible to an external
auditor or regulator, which is the point of building gates at all — but they harden the record rather than
producing decisions, so they are P2.

**Independent Test**: Alter or delete a past AuditEntry and confirm the hash-chain verification detects it;
confirm retention is configured to the required horizon per workspace; and confirm a package is only
accessible to roles assigned to it, not to the whole workspace.

**Acceptance Scenarios**:

1. **Given** the AuditEntry stream, **When** it is periodically hash-chained and later a past entry is
   altered or deleted, **Then** chain verification detects the break and flags tampering.
2. **Given** a workspace retention policy, **When** audit and case records age, **Then** they are retained
   for at least the required long-term horizon and disposed only per the configured policy.
3. **Given** a delivered package, **When** access is requested, **Then** it is granted only to roles
   assigned to that package, not to every member of the workspace.

---

### User Story 6 - Recovery from disaster is verified, not assumed (Priority: P3)

An operator needs documented confidence that the system can be recovered within agreed bounds. A
backup-and-restore drill demonstrates that data can be recovered to a recent point and the service brought
back within the target recovery times, with the results documented as evidence.

**Why this priority**: DR verification protects everything the phase builds, but it is an operational
assurance activity rather than a user-facing capability, so it is the lowest priority in the phase.

**Independent Test**: Perform a backup/restore drill and confirm the recovery-point and recovery-time
objectives are met and documented.

**Acceptance Scenarios**:

1. **Given** a backup regime, **When** a restore drill is run, **Then** data is recoverable to within the
   target recovery-point objective and the service is restored within the target recovery-time objective.
2. **Given** a completed drill, **When** results are recorded, **Then** the achieved RPO/RTO are documented
   as evidence.

---

### Edge Cases

- A reviewer attempts to edit AI-drafted artifact text directly → there is no such path; only
  comment-and-regenerate is available, and the original completion is never mutated.
- An upstream revision would re-open a gate, but no impact-assessment record has been supplied → the reopen
  is blocked until reason/scope/risk are recorded; the block itself is auditable.
- A dependency chain is long → only the direct dependents auto-reopen; indirect dependents are flagged for
  manual review, so a single upstream edit does not cascade uncontrollably.
- An advisory SLA timer fires on a gate → notification + escalation event only; the gate stays open, and no
  decision is made automatically.
- An escalation policy points at a role with no assignee → the escalation is still recorded and surfaced,
  not silently dropped.
- A past AuditEntry is altered or deleted → the next hash-chain verification detects the break and raises a
  tampering alert.
- A regeneration is triggered repeatedly → each produces a new immutable completion; every prior completion
  (including the original AI draft) remains retained and replayable.
- A restore drill misses its RPO or RTO target → the shortfall is documented as a finding rather than the
  drill being reported as passed.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST provide Review-Gate and Approval-Gate as first-class, reusable governance
  subprocess definitions through which every D4 decision flows, replacing inline review/approval handling.
- **FR-002**: System MUST present reviewers a gate experience showing the artifact(s) under review and the
  exact inputs the decision is based on, and MUST let them record approve / reject / request-changes with
  rationale.
- **FR-003**: System MUST record each gate decision as a tamper-evident Decision capturing reviewer
  identity, the reviewed artifacts and inputs, the timestamp, and the outcome (who/what/when/on-what/why).
- **FR-004**: System MUST NOT provide any path to edit AI-drafted artifact content in place; the only way to
  change an AI artifact at a gate is to record comments and trigger regeneration.
- **FR-005**: On regeneration, System MUST produce a new immutable artifact completion while retaining every
  prior completion (including the original AI draft) unchanged and replayable, and MUST produce a delta
  report describing what changed.
- **FR-006**: System MUST, when an approved upstream artifact is revised, identify its direct
  `DERIVES_FROM`/`SATISFIES` dependents via a decision table over the dependency edge tables as gate-reopen
  candidates.
- **FR-007**: System MUST block a gate reopen until a formal impact-assessment record (reason, scope, risk)
  is captured, and MUST record the reopen itself as an auditable Decision.
- **FR-008**: System MUST auto-reopen only direct dependents; transitive (indirect) dependents MUST be
  flagged for manual review and MUST NOT be automatically reopened.
- **FR-009**: System MUST support versioned EscalationPolicy definitions with role chains that can be
  attached to gates.
- **FR-010**: System MUST support advisory SLA timers on gates such that a timer firing emits an in-app
  notification (visible in the reviewer's workspace UI; no email or webhook delivery in v1) and an
  escalation event, and updates the visible escalation-policy status.
- **FR-011**: System MUST NOT auto-approve, auto-reject, or auto-reassign a gate when an SLA timer fires or
  an escalation proceeds; the gate MUST remain open for a human decision.
- **FR-012**: System MUST record and surface an escalation even when its policy references a role that has
  no current assignee, rather than dropping it silently.
- **FR-013**: System MUST hash-chain the AuditEntry stream by sealing a new segment on a fixed hourly
  interval, and MUST provide a verification that detects any retroactive alteration or deletion of a past
  entry.
- **FR-014**: System MUST retain audit and case records for at least 7 years under a workspace-configurable
  retention policy (workspaces may configure a longer horizon, never shorter), disposing of records only per
  that policy.
- **FR-015**: System MUST scope delivered-package access by role assignment, so a package is not visible to
  the entire workspace by default.
- **FR-016**: System MUST verify disaster recovery via a backup/restore drill that meets a Recovery Point
  Objective (RPO) of ≤ 15 minutes and a Recovery Time Objective (RTO) of ≤ 1 hour, with achieved results
  documented.
- **FR-017**: System MUST preserve all prior-phase guarantees under the gate machinery — append-only audit
  in the same transaction as the change, workspace isolation, per-step reproducibility snapshots, and
  byte-identical replay — including for regenerated completions and reopened gates.
- **FR-018**: Reviewers and escalation actors MUST act only within their assigned roles; a gate decision or
  escalation MUST be attributable to an authorized role holder.
- **FR-019**: System MUST emit the full governance-event payloads (gate opened, decided, reopened,
  escalated, regenerated) into the event stream so downstream consumers can project a complete governance
  history.

### Key Entities *(include if feature involves data)*

- **Review-Gate / Approval-Gate (subprocess definition)**: The reusable, versioned governance subprocesses
  through which decisions flow; shared across workflows and case types rather than re-implemented.
- **Gate Decision**: The tamper-evident record of a single gate outcome — reviewer, reviewed artifacts and
  inputs, timestamp, outcome, rationale.
- **Artifact completion (immutable)**: A single version of an AI-drafted (or regenerated) artifact;
  regeneration adds a new completion and never mutates a prior one.
- **Impact-assessment record**: The reason/scope/risk record that must be captured before a dependent gate
  is reopened; makes the reopen an auditable Decision.
- **EscalationPolicy**: A versioned definition with a role chain and advisory SLA timers, attachable to
  gates; its activation status is visible.
- **Escalation event / notification**: The advisory signals emitted when an SLA timer fires; carry no
  routing authority in v1.
- **Hash-chained audit segment**: A periodically sealed segment of the AuditEntry stream whose chain
  detects retroactive tampering.
- **Retention policy**: The workspace-configurable rule governing how long audit and case records are kept
  and when they may be disposed.
- **Package access grant**: The role-scoped entitlement controlling who may access a delivered package.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of D4 decisions in a demonstration run flow through a Review-Gate or Approval-Gate
  subprocess, each producing a Decision record with reviewer, inputs, timestamp, and outcome.
- **SC-002**: There is no path to edit AI-drafted artifact content in place; a reviewer change always
  yields a new immutable completion with the original retained and both present in the audit trail.
- **SC-003**: When an approved upstream artifact is revised, its direct `DERIVES_FROM`/`SATISFIES`
  dependents' gates re-open only after an impact-assessment record is captured, transitive dependents are
  flagged (not auto-reopened), and every reopen is recorded as a Decision.
- **SC-004**: A gate whose advisory SLA expires emits a notification and escalation event and shows the
  policy as active, while remaining open and undecided (zero automatic approvals/rejections/reassignments).
- **SC-005**: A retroactive alteration or deletion of any past AuditEntry is detected by hash-chain
  verification 100% of the time.
- **SC-006**: Audit and case retention is configured to at least 7 years per workspace, and a delivered
  package is accessible only to roles assigned to it (zero workspace-wide default exposure).
- **SC-007**: A backup/restore drill demonstrably meets RPO ≤ 15 minutes and RTO ≤ 1 hour, with achieved
  figures documented.
- **SC-008**: Every prior-phase success criterion (zero-manual-edit delivery, byte-identical replay,
  zero cross-workspace leaks, append-only audit) continues to pass unchanged under the gate machinery.

## Assumptions

- SLAs are **advisory in v1**: timers notify and escalate but never route or decide. The enforced/auto-route
  branch is a documented future toggle, deliberately not built here (Q9).
- Human interaction with AI artifacts is **comment-and-regenerate only**; no in-system content editing of
  AI-drafted artifacts exists in v1 (Q4). This preserves the immutable, replayable audit trail.
- Gate re-opening on upstream revision is **limited to direct dependents**, resolved by a decision table
  over the existing dependency edge tables, and is **gated on a formal impact assessment**; transitive
  re-open remains a manual action (Q3). The "reopen rate" is the existing Regeneration-rate KPI, not a new
  metric.
- The **relational record remains the system of record** and the audit stream remains **append-only and not
  extensible**; hash-chaining adds tamper-evidence on top of, not in place of, the append-only guarantee.
- Escalation acts through **versioned policies with role chains**; role assignment and workspace tenancy are
  those established in earlier phases and are reused, not redefined.
- Retention horizon is 7 years (workspace-configurable floor) and recovery objectives are RPO ≤ 15 minutes /
  RTO ≤ 1 hour; the phase verifies these via the DR drill and retention policy rather than defining new
  targets elsewhere.
- The v1 "reviewer experience" surface is the gate/notification **API contract** (with persisted
  in-app notification rows); a dedicated reviewer UI is a later-phase deliverable (Phase 6+), not
  built here.
- Record **disposal** under the retention policy is deferred: v1 verifies and reports retention
  posture only — disposal remains an explicit, governed future action, never an automatic job.
- Team and deployment assumptions are unchanged from earlier phases (small team, single-region cloud,
  logical per-workspace isolation).

## Dependencies

- **Phase 4 (spec `004-assessment-enhancement-case-types`) — satisfied.** Phase 5's entry criterion is
  "Phase 4 exit." Per the plan's cross-phase dependency map, **Phase 4 blocks Phase 5 because the
  regeneration policy (Q3) must be exercised across ≥ 2 case types to validate DMN gate re-opening** — so
  the second and third case types must exist first. Phase 4 is specified (prior batch), which in turn
  depends on Phases 1–3 (all specified).
- **Phase 1 (spec `001`) — satisfied.** Provides the append-only AuditEntry stream and same-transaction
  audit (E1.4) that hash-chaining hardens, the generalized `DERIVES_FROM`/`SATISFIES` edge tables (E1.7 /
  AD-7) the regeneration DMN reads, and the inline D4 gate treatment this phase promotes to first-class
  subprocesses.
- **Open questions**: Q3, Q4, and Q9 are all RESOLVED (research-backed) in the plan and recorded in
  Clarifications and Assumptions; none block this phase.
- **This phase UNBLOCKS Phases 6 and 7.** Both depend on Phase 5: **Phase 6** because the publish-governance
  UI (E6.2) encodes this phase's gate machinery (E5.1), and **Phase 7** because the governance-event
  payloads emitted here (FR-019) complete the projection-sufficient payload set (E7.1). With Phase 5
  specified, the `{3, 4, 5, 6, 7}` chain is dependency-complete.
