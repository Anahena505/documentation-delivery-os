# Data Model: Governance & Review Gates

**Feature**: 005-governance-review-gates · **Date**: 2026-07-07
Delta over the Phase 1–4 schema (V1–V16). All new tables carry `workspace_id uuid NOT NULL` with
the standard RLS policy and `d2os_app` grants. Migrations: **V17** (governance), **V18**
(casecore), **V19** (artifacts), **V20** (tenancy, columns only). Research references:
[research.md](research.md) R1–R6, R8.

## New Entities

### GateInstance (V17, governance)

One runtime gate occurrence (Review or Approval) bridged to the engine's user task (R1).

| Field | Type | Notes |
|---|---|---|
| id | uuid PK | |
| workspace_id | uuid NOT NULL | RLS scope |
| case_instance_id | uuid NOT NULL | FK → case_instance |
| gate_type | text NOT NULL | `REVIEW` \| `APPROVAL` |
| gate_definition_key | text NOT NULL | + `gate_definition_version int NOT NULL` (pinned subprocess def) |
| subject_artifact_revision_id | uuid NULL | primary artifact under review |
| inputs_ref | jsonb NOT NULL | exact information the decision is based on (artifact revisions, rubric scores, delta report id) |
| escalation_policy_key | text NULL | + `escalation_policy_version int NULL` (pinned at open, R4) |
| status | text NOT NULL | see state machine |
| decision_id | uuid NULL | FK → decision (set on decide/reopen) |
| reviewer_comments | text NULL | REQUEST_CHANGES payload (Q4) |
| delta_report_id | uuid NULL | FK → delta_report (attached on regenerate/reopen) |
| engine_task_id | text NULL | Flowable task correlation |
| opened_at / decided_at / reopened_at | timestamptz | |

**State machine**:

```
OPEN ──APPROVE──▶ APPROVED ──(upstream revision → DMN candidate)──▶ REOPEN_CANDIDATE
OPEN ──REJECT──▶ REJECTED                                              │ (impact assessment captured)
OPEN ──REQUEST_CHANGES──▶ REGENERATING ──(new revision + delta)──▶ OPEN ▼
REOPEN_CANDIDATE ──ReopenService.reopen (409 without impact_assessment)──▶ REOPENED ──▶ (re-decide as OPEN)
```

**Invariants**: `decide()` accepts only the three verbs; every transition writes Decision (where
human) + AuditEntry in the same transaction; transitive dependents get status flag
`MANUAL_REVIEW` on a candidate row, never auto-`REOPENED` (Q3).

### ImpactAssessment (V17, governance)

The reason/scope/risk record that must precede a reopen (Q3, R3).

| Field | Type | Notes |
|---|---|---|
| id | uuid PK | |
| workspace_id | uuid NOT NULL | |
| gate_instance_id | uuid NOT NULL | FK → gate_instance |
| upstream_artifact_revision_id | uuid NOT NULL | the revised artifact that triggered candidacy |
| reason / scope / risk | text NOT NULL | the formal assessment fields |
| author | text NOT NULL | user id |
| created_at | timestamptz NOT NULL | |

**Invariant**: at most one per (gate, upstream revision); `ReopenService` requires it before the
`REOPEN_CANDIDATE → REOPENED` transition.

### GateReopenCandidate (V17, governance)

The DMN-identified dependents of a revised approved artifact — "identified" audited separately
from "acted on" (R3).

| Field | Type | Notes |
|---|---|---|
| id | uuid PK | |
| workspace_id | uuid NOT NULL | |
| upstream_artifact_revision_id | uuid NOT NULL | new revision that triggered evaluation |
| dependent_artifact_revision_id | uuid NOT NULL | via `DERIVES_FROM`/`SATISFIES` edge |
| gate_instance_id | uuid NULL | the approved gate to reopen (direct) |
| depth | int NOT NULL | 1 = direct (reopenable); >1 = transitive |
| disposition | text NOT NULL | `PENDING → REOPENED \| MANUAL_REVIEW \| DISMISSED` |
| created_at | timestamptz NOT NULL | |

### EscalationActivation (V17, governance)

A visible record of each advisory SLA firing (Q9, R4). Append-only.

| Field | Type | Notes |
|---|---|---|
| id | uuid PK | |
| workspace_id | uuid NOT NULL | |
| gate_instance_id | uuid NOT NULL | |
| policy_key / policy_version | text / int NOT NULL | pinned version fired |
| step_index | int NOT NULL | position in the role chain |
| role | text NOT NULL | escalation target role (recorded even if unassigned) |
| assignee_resolved | boolean NOT NULL | false ⇒ surfaced as unassigned, still recorded |
| status | text NOT NULL | `ACTIVE → RESOLVED` (resolved when the gate is decided) |
| fired_at | timestamptz NOT NULL | |

**Invariant**: firing never mutates GateInstance status or the engine task (advisory only).

### DeltaReport (V17, governance)

Deterministic diff between two revisions of one artifact (R2).

| Field | Type | Notes |
|---|---|---|
| id | uuid PK | |
| workspace_id | uuid NOT NULL | |
| artifact_id | uuid NOT NULL | |
| from_revision_id / to_revision_id | uuid NOT NULL | |
| diff_content | text NOT NULL | unified diff, deterministic |
| diff_hash | text NOT NULL | SHA-256 (tamper check / reproducibility) |
| created_at | timestamptz NOT NULL | |

### InAppNotification (V17, governance)

Persisted in-app notification row — the v1 delivery mechanism for advisory SLA escalations and
tamper alerts (FR-010; no email/webhook). Deliberately generic (`source_module` + `type`) so later
phases (e.g. Phase 7 cycle/divergence alerts) can persist their own rows through the same store.

| Field | Type | Notes |
|---|---|---|
| id | uuid PK | |
| workspace_id | uuid NOT NULL | RLS scope |
| recipient_role | text NOT NULL | role-addressed, resolved to users at read time |
| source_module | text NOT NULL | `governance` in v1; other modules may write rows in later phases |
| type | text NOT NULL | `SLA_ESCALATION` \| `TAMPER_ALERT` (extensible) |
| subject_ref | jsonb NOT NULL | e.g. `{gateInstanceId}`, `{segmentId}` |
| message | text NOT NULL | |
| created_at | timestamptz NOT NULL | |
| read_at | timestamptz NULL | per-notification read marker |

### AuditChainSegment (V18, casecore)

Periodic tamper-evidence seal over the append-only audit stream (T6-b, R5). Append-only,
per-workspace chain.

| Field | Type | Notes |
|---|---|---|
| id | uuid PK | |
| workspace_id | uuid NOT NULL | chain is per workspace |
| segment_seq | bigint NOT NULL | UNIQUE (workspace_id, segment_seq) |
| from_entry_id / to_entry_id | uuid NOT NULL | sealed audit_entry range watermarks |
| entry_count | int NOT NULL | |
| segment_hash | text NOT NULL | SHA-256 over canonical serialization of the range |
| prev_segment_hash | text NOT NULL | genesis = 64×'0' |
| sealed_at | timestamptz NOT NULL | |
| last_verified_at | timestamptz NULL | set by AuditChainVerifier |

### PackageAccessGrant (V19, artifacts)

Role-scoped entitlement to a delivered package (T3-d, R6).

| Field | Type | Notes |
|---|---|---|
| id | uuid PK | |
| workspace_id | uuid NOT NULL | |
| package_id | uuid NOT NULL | FK → execution_package |
| role | text NOT NULL | UNIQUE (package_id, role) |
| granted_by | text NOT NULL | user id or `system:delivery` (participant-role seeding) |
| created_at / revoked_at | timestamptz | revocation is a new state, row retained for audit |

## Modified Entities

| Entity | Change |
|---|---|
| `workspace` (V20, tenancy) | + `retention_years int NOT NULL DEFAULT 7`, `retention_policy_notes text NULL` (NFR-5, R6). Columns only. |
| `definition_asset` (content-level) | New `type` values `SUBPROCESS` (review/approval gate defs) and `ESCALATION_POLICY` — rows, not schema (R1/R4). |
| Case workflows (content-level) | initiation-v3, assessment-v2, enhancement-v2 published embedding gate callActivities; running cases keep pinned prior versions. |
| `event_outbox` payloads | Gate lifecycle events carry the full projection-sufficient payload contract (R8); no schema change. |

## Relationships (summary)

```
case_instance ──< gate_instance ──── decision / audit_entry (same tx)
gate_instance ──< escalation_activation (advisory firings; policy version pinned)
escalation_activation / chain verification ──▶ in_app_notification (v1 delivery: persisted rows, workspace UI)
gate_instance ──< impact_assessment ──(precondition)──▶ REOPENED
revised artifact_revision ──trace_link (DERIVES_FROM/SATISFIES)──▶ gate_reopen_candidate rows
artifact_revision ×2 ──▶ delta_report (deterministic diff)
audit_entry range ──▶ audit_chain_segment (hash-chained, per workspace)
execution_package ──< package_access_grant (role-scoped, default-deny)
```

## Validation Rules (from requirements)

- Gate decision endpoint: verbs APPROVE/REJECT/REQUEST_CHANGES only; no artifact-content input
  anywhere (FR-004).
- Reopen: 409 unless a matching `impact_assessment` exists; only depth-1 candidates reopenable;
  every reopen writes a Decision (FR-006–008).
- Timer firing: writes `escalation_activation` + notification + outbox event; asserts gate status
  unchanged (FR-010/011).
- Chain verification: recompute-and-compare any segment; mismatch ⇒ tamper alert (FR-013).
- Package read: caller must hold a granted role; delivery seeds participant-role grants
  (FR-015).
