# Research: Governance & Review Gates

**Feature**: 005-governance-review-gates · **Date**: 2026-07-07
All NEEDS CLARIFICATION items from the Technical Context are resolved below.

## R1. Gate subprocesses — BPMN callActivities bridged to a governance aggregate

**Decision**: `review-gate.bpmn20.xml` and `approval-gate.bpmn20.xml` are standalone
ProcessDefinitions invoked as **callActivities** from case workflows (initiation-v3,
assessment-v2, enhancement-v2 ship as new workflow versions embedding them). Each gate execution
creates a `gate_instance` row (governance module) via `GateTaskBridge`; the human decision is a
durable Flowable **userTask**. `GateService.decide()` accepts exactly three verbs — APPROVE /
REJECT / REQUEST_CHANGES(comments) — writes the Decision + AuditEntry in the same transaction,
completes the user task, and the subprocess routes on the verb. The gate definition is published
as a DefinitionAsset (`type=SUBPROCESS`), so the same gate is reused across case types by
reference, never re-implemented.

**Rationale**: callActivity gives real reuse (one definition, many workflows) with engine-durable
wait states that survive restarts — the same reasoning as Phase 3's D4 user task, now promoted to
a first-class shared subprocess (E5.1). Keeping gate state in a governance aggregate (not only
engine variables) keeps the system of record relational (Principle III) and gives the API/UI a
queryable surface.

**Alternatives considered**: inline userTasks per workflow (status quo) — rejected (the phase
exists to end per-workflow re-implementation); a pure application-level gate with no BPMN —
rejected (loses durable waits, boundary timers, and the engine correlation that escalation/SLA
need).

## R2. Comment-and-regenerate + deterministic delta reports (Q4)

**Decision**: REQUEST_CHANGES stores reviewer comments on the `gate_instance`, then
`RegenerationDelegate` re-enters the standard persona execution path with the comments injected
**as delimited data** in the envelope (same T1-a framing); the persona produces a new immutable
ArtifactRevision (full snapshot + rubric as always). `DeltaReportService` renders a deterministic
unified diff (java-diff-utils) between the prior and new revision content, stored as a delta
report attached to the reopened/re-presented gate. No endpoint anywhere accepts artifact content
from a human for an AI-drafted artifact.

**Rationale**: Q4's immutable-completion practice verbatim; re-using the persona path means
regenerated output inherits reproducibility (Principle II) with zero new AI machinery.
Deterministic diffs (not AI summaries) make the delta report itself replayable and
tamper-checkable.

**Alternatives considered**: AI-generated "what changed" summaries — deferred (can layer on
later; the deterministic diff is the auditable core); PATCH endpoint with human edits — rejected
(constitutionally forbidden, Principle II).

## R3. Reopen policy — DMN over edges, impact assessment as precondition (Q3)

**Decision**: When a new revision of an **approved** artifact is published,
`ReopenCandidateService` queries `trace_link` edges of kinds `DERIVES_FROM`/`SATISFIES` pointing
at the revised artifact and feeds the edge set to `reopen-direct-dependents.dmn` (hit policy
COLLECT — kind → candidate, keeping policy in an authorable definition). Candidates land as
`gate_reopen_candidate` state on the affected `gate_instance` (status `REOPEN_CANDIDATE`), and
**transitive** dependents (edges-of-edges, depth > 1) are flagged `MANUAL_REVIEW` only. The
actual reopen (`ReopenService.reopen`) requires an `impact_assessment` row (reason, scope, risk,
author) captured first — the endpoint 409s without it — then flips the gate to `REOPENED`,
records a Decision, and signals the engine to re-activate the gate's user task. Delta report
(R2) is attached so the reviewer re-decides informed.

**Rationale**: Q3's ruling verbatim — direct dependents only, DMN over edge tables, reopen gated
on a formal impact assessment so it is an auditable Decision, never a silent side effect
(Principle V). Candidates-vs-reopen separation makes "identified" and "acted on" independently
auditable, which is exactly the document-control reopen-rate discipline.

**Alternatives considered**: automatic reopen on candidate detection — rejected (Q3 explicitly
requires the impact-assessment precondition); transitive auto-reopen — rejected (Q3: manual);
hardcoding the edge-kind policy in Java — rejected (Q8/AD-5 keep routing policy in authorable
DMN).

## R4. Advisory SLA timers + versioned EscalationPolicy (Q9)

**Decision**: EscalationPolicy is a new DefinitionAsset `type=ESCALATION_POLICY` (content-level
addition to the existing supertype — body: role chain, per-step SLA durations). Gate BPMN
carries **non-interrupting boundary timer events** on the user task; each
firing calls `TimerFiredHandler`, which resolves the pinned policy version, records an
`escalation_activation` row (step, role, fired_at, visible status), emits an in-app notification
(NotificationService — in-app only in v1, surfaced in the workspace UI; no email/webhook) and an
outbox event — **and nothing else**:
the user task remains active, no reassignment/approval/rejection occurs. A policy step whose role
has no assignee still records + surfaces the activation. Gates pin the policy version at open.

**Rationale**: Q9 verbatim (advisory only; visible activation status; human resolution).
Non-interrupting boundary timers are the BPMN-native way to fire N escalation steps without
touching the task's lifecycle — the "no transition authority" property is structural, not
disciplinary. Definition-typed policies get versioning/publish governance for free (Principle I).

**Alternatives considered**: interrupting timers + reassignment — rejected (that *is*
auto-routing; explicitly a post-v1 toggle); cron polling for aged gates — rejected (the engine
already schedules timers durably; polling adds drift and a second scheduler).

## R5. Audit hash-chaining (T6-b)

**Decision**: `audit_chain_segment` (casecore, V18): a periodic `AuditChainSealer` job seals
consecutive `audit_entry` ranges — `segment_seq`, `from_entry` / `to_entry` (id watermarks),
`entry_count`, `segment_hash` = SHA-256 over the ordered canonical serialization of the segment's
entries, `prev_segment_hash` chaining to the prior segment (genesis = zeros). Segments are
append-only and RLS-scoped per workspace (chain per workspace). `AuditChainVerifier` recomputes
any segment (or the whole chain) on demand and on schedule; any alteration/deletion of a sealed
entry breaks the recomputed hash ⇒ tamper alert (notification + outbox event). Sealing cadence:
fixed hourly interval (FR-013; not configurable in v1).

**Rationale**: T6-b asks for periodic hash-chaining on top of the append-only grants (T6-a) —
segments amortize hashing cost, and per-workspace chains keep verification tenant-scoped (an
auditor verifies their workspace without reading others). Canonical serialization pinned in code
+ tested so verification is stable across versions.

**Alternatives considered**: per-entry chaining (hash in each audit row) — rejected (write-path
coupling and hot-row contention on every transaction; segment sealing is off-path); external
notarization/anchoring — deferred (post-v1 hardening; adds a vendor surface).

## R6. Retention (NFR-5) and package access grants (T3-d)

**Decision**: V20 adds workspace columns `retention_years int NOT NULL DEFAULT 7` and
`retention_policy_notes text`; a retention verification job reports (never auto-deletes in v1)
records approaching/na­vigating policy boundaries — disposal remains an explicit governed action.
V19 adds `package_access_grant` (artifacts): `(workspace_id, package_id, role, granted_by,
created_at)`; `PackageAccessService` enforces that reading a delivered package requires a grant
for one of the caller's roles — grants are seeded at delivery for the case's participant roles,
so nothing is workspace-wide by default.

**Rationale**: NFR-5 requires 7-year retention **config** per workspace and verified enforcement
posture — v1 verifies and reports rather than auto-deleting (destructive automation without a
governance gate would contradict Principle V). T3-d verbatim: role-scoped package access,
default-deny beyond the granted roles.

**Alternatives considered**: automatic disposal job — rejected for v1 (destructive, ungated);
storing grants as JSONB on the package — rejected (grants need per-row audit and revocation;
a real table is the honest shape).

## R7. DR drill (NFR-8)

**Decision**: `ops/dr-drill.md` runbook + compose-level scripts: PostgreSQL WAL archiving
(archive_command to the object store / MinIO bucket) + nightly base backup ⇒ point-in-time
recovery within RPO ≤ 15 min; drill procedure = restore base + replay WAL to a timestamp, boot the
app against the restored DB, run the replay-audit smoke suite, measure wall-clock RTO ≤ 1 h;
results (achieved RPO/RTO, date, operator) recorded in `quickstart-results.md`. MinIO/S3 artifact
store relies on bucket versioning; the drill verifies artifact hash integrity after restore.

**Rationale**: NFR-8 asks for a *verified, documented* posture, not new product code. WAL
archiving is the standard PostgreSQL PITR mechanism and maps directly onto the 15-minute RPO.
Running the existing replay/integrity suites against the restored instance is the strongest
cheap proof that a restore is actually usable.

**Alternatives considered**: streaming replica + failover — deferred (single-region v1, AS-4;
a replica is an availability measure, not a substitute for tested restores).

## R8. Gate event payloads for projection (FR-019, Phase 7 dependency)

**Decision**: Every gate lifecycle transition (opened, decided, reopened-candidate, impact-assessed,
reopened, escalation-fired, regeneration-triggered) emits an outbox event whose payload carries
the full projection-sufficient tuple: gate id + type + definition version, case id, artifact
revision ids in/out, decision verb + decider + rationale ref, impact assessment id, policy
version + step. The payload contract is written down in `contracts/api.yaml` components (event
schemas) and asserted by a payload-completeness test so Phase 7's projector needs no
back-reading of aggregate tables.

**Rationale**: The cross-phase dependency map's stated reason Phase 5 blocks Phase 7 — gate
events complete the projection-sufficient payload set (E7.1). Making the payload a tested
contract now prevents the Phase 7 "projection-insufficient event" failure mode pre-emptively.

**Alternatives considered**: thin events + projector joins — rejected (breaks the rebuildable-
from-outbox property when aggregates evolve; Phase 7's audit explicitly checks payload
sufficiency).
