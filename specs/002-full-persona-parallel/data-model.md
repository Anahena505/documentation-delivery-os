# Data Model: Full Persona Suite + Parallel Execution

**Feature**: 002-full-persona-parallel · **Date**: 2026-07-07
Delta over the Phase 1 schema (V1–V9). All new tables carry `workspace_id uuid NOT NULL` with the
standard RLS policy (`app.workspace_id` session setting) and are granted to `d2os_app` per V8's
default privileges. Migrations: **V10** (intake module), **V11** (persona module), **V12** (casecore
module).

## New Entities

### Attachment (V10, intake)

An uploaded file on a ProblemSubmission. Raw content lives in the object store; the row tracks
lifecycle and provenance. Raw bytes are never interpolated into a prompt (AD-12/T1-d).

| Field | Type | Notes |
|---|---|---|
| id | uuid PK | |
| workspace_id | uuid NOT NULL | RLS scope |
| submission_id | uuid NOT NULL FK → problem_submission | |
| filename | text NOT NULL | original client filename (display only, never a storage path) |
| content_type | text NOT NULL | must be in the allowlist at upload time |
| size_bytes | bigint NOT NULL | ≤ configured cap (default 20 MB) |
| object_key | text NOT NULL | workspace-scoped object-store key |
| content_hash | text NOT NULL | SHA-256 of stored bytes (integrity, Principle III) |
| status | text NOT NULL | `RECEIVED → EXTRACTING → SUMMARIZED / REJECTED` |
| rejection_reason | text NULL | set when status=REJECTED (unparseable, timeout, oversized…) |
| created_at | timestamptz NOT NULL | |

**State transitions**: `RECEIVED → EXTRACTING → SUMMARIZED` (happy) · `RECEIVED|EXTRACTING → REJECTED`
(allowlist/size fail at upload is a 4xx, no row; extraction failure is `REJECTED` + audit).
Rows are immutable once terminal (`SUMMARIZED`/`REJECTED`) except nothing — terminal is terminal.

### AttachmentSummary (V10, intake)

The sanitized representation personas may consume. Produced by the sandboxed extract + gateway
summarize pass; snapshot-recorded like any AI operation so replay holds.

| Field | Type | Notes |
|---|---|---|
| id | uuid PK | |
| workspace_id | uuid NOT NULL | |
| attachment_id | uuid NOT NULL FK → attachment, UNIQUE | 1:1 |
| extracted_chars | int NOT NULL | size of Tika output fed to summarizer |
| summary_text | text NOT NULL | the only attachment-derived content allowed into envelopes |
| operation_execution_id | uuid NOT NULL | FK → operation_execution (the summarize call's snapshot) |
| created_at | timestamptz NOT NULL | |

### ConsistencyFinding (V11, persona)

One cross-artifact discrepancy found by the Consistency-Check subprocess.

| Field | Type | Notes |
|---|---|---|
| id | uuid PK | |
| workspace_id | uuid NOT NULL | |
| case_id | uuid NOT NULL FK → case_instance | |
| tier | text NOT NULL | `DETERMINISTIC` (blocking) / `SEMANTIC` (advisory) |
| kind | text NOT NULL | `DANGLING_REFERENCE` / `ATTRIBUTE_CONTRADICTION` / `SEMANTIC_INCOHERENCE` |
| subject_ref | text NOT NULL | the namespaced id or attribute in conflict (e.g. `entity:Order`) |
| source_artifact_id | uuid NOT NULL FK → artifact | artifact making the reference/assertion |
| target_artifact_id | uuid NULL FK → artifact | owning/contradicting artifact (NULL for dangling ref) |
| detail | jsonb NOT NULL | values asserted, locations, rubric criterion for SEMANTIC |
| operation_execution_id | uuid NULL | set for SEMANTIC findings (the reviewing AI call's snapshot) |
| status | text NOT NULL | `OPEN → RESOLVED / WAIVED` (human decision, audited) |
| resolved_by | text NULL, resolved_at timestamptz NULL | decision provenance (Principle V) |
| created_at | timestamptz NOT NULL | |

Each finding additionally writes `trace_link` edges (`CONFLICTS_WITH`) between the two artifacts
(AD-7), so the future graph projection sees conflicts without reading this table.

**Invariant (FR-007)**: a Case cannot transition past the consistency stage while any
`tier=DETERMINISTIC AND status=OPEN` finding exists for it — enforced in the subprocess gateway and
re-checked in `PackageAssemblyService`.

### ProgressEvent (V12, casecore)

Append-only user-visible liveness stream (FR-011). Same append-only grant treatment as
`audit_entry` (REVOKE UPDATE, DELETE from `d2os_app` — extends the V8/T6-a contract in V12).

| Field | Type | Notes |
|---|---|---|
| id | bigint PK (identity) | monotonic per-table for `waitAfter` long-poll cursoring |
| workspace_id | uuid NOT NULL | |
| case_id | uuid NOT NULL | |
| kind | text NOT NULL | `STEP_STARTED / STEP_COMPLETED / VALIDATION_ATTEMPT / BRANCH_FORKED / BRANCH_JOINED / HEARTBEAT / ESCALATED / SUSPENDED / RECONCILED / DELIVERED` |
| activity_id | text NULL | BPMN activity / persona key where applicable |
| detail | jsonb NULL | attempt number, branch set, heartbeat op id… |
| created_at | timestamptz NOT NULL | cadence assertion key (≤5 s gap per running case) |

### WorkspaceBudget (V12, casecore)

Per-workspace AI-cost ceiling and durable consumption rollup (FR-017, T5-b).

| Field | Type | Notes |
|---|---|---|
| workspace_id | uuid PK | one row per workspace |
| period_start | date NOT NULL | rolling monthly window |
| token_cap | bigint NOT NULL | 0 = unlimited (default-deny posture: seeded with a real cap) |
| tokens_consumed | bigint NOT NULL DEFAULT 0 | updated in the same tx as each OperationExecution cost |
| rate_limit_per_minute | int NOT NULL | request-rate smoothing input (in-memory limiter reads this) |
| updated_at | timestamptz NOT NULL | optimistic concurrency on rollup update |

**Invariant**: `tokens_consumed` is only ever increased by the gateway in the OperationExecution
transaction; breach → the *offending Case* suspends (reuses Phase 1 suspend path), other Cases in the
workspace continue until they too attempt a call.

### ReconciliationRun (V12, casecore)

Audit trail of the dual-state reconciler (FR-010). One row per sweep that found divergence (clean
sweeps emit nothing — no row spam).

| Field | Type | Notes |
|---|---|---|
| id | uuid PK | |
| workspace_id | uuid NOT NULL | |
| case_id | uuid NOT NULL | |
| divergence | text NOT NULL | `MISSING_DOMAIN_TRANSITION / DEAD_LETTER_JOB / STATE_MISMATCH` |
| engine_state | jsonb NOT NULL | snapshot of what the engine said |
| domain_state | jsonb NOT NULL | snapshot of what the domain said |
| action | text NOT NULL | `REPAIRED / ESCALATED / IGNORED_TRANSIENT` |
| created_at | timestamptz NOT NULL | |

## Modified Entities

### CaseInstance (casecore) — no schema change required

Branch-level state is owned by the engine (active executions) and `PersonaInvocation` rows; the Case
keeps its Phase 1 status machine. One semantic extension: `Escalated` may now coexist with other
branches still `Running` — the Case-level status is `Escalated` if **any** branch is escalated
(most-severe-wins projection), computed, not stored per branch.

### PersonaInvocation (persona) — V11 adds one column

| Field | Type | Notes |
|---|---|---|
| branch_id | text NULL | BPMN execution/activity id of the parallel branch (NULL for sequential steps) — lets replay, reconciliation, and the join reason about branches without engine queries |

### DefinitionAsset (catalog) — no schema change

13 new persona/prompt/rubric/template DefinitionAssets published as new rows (Principle I). The
`case_type:initiation` body's `dependsOn` list and `workflow:initiation` (v2 →
`initiation-v2.bpmn20.xml` process) are published as **version 2** assets; v1 stays published for
replay of Phase 1 cases. CaseDefinitionSnapshot pinning (AD-4) is untouched — new cases resolve v2.

### ExecutionEnvelope (persona, in-memory) — new slot

`attachmentSummaries: List<{attachmentId, filename, summaryText}>` — rendered into the prompt inside
the untrusted-data delimiters (T1-a). Envelope hash (already recorded) now covers summaries, keeping
replay byte-exact.

## Relationships (delta)

```
problem_submission 1 ──── * attachment 1 ──── 1 attachment_summary ──── 1 operation_execution
case_instance 1 ──── * consistency_finding * ──── 1..2 artifact   (+ trace_link CONFLICTS_WITH edges)
case_instance 1 ──── * progress_event
workspace 1 ──── 1 workspace_budget
case_instance 1 ──── * reconciliation_run
persona_invocation *──── 1 case_instance   (now branch-tagged)
```

## Validation Rules (from FRs)

- Attachment upload: content-type ∈ allowlist ∧ size ≤ cap, else 422 (FR-015 edge).
- Envelope construction MUST NOT read `attachment.object_key` content — only `attachment_summary.summary_text` (FR-015; asserted by AttachmentSandboxIT).
- Package assembly MUST re-verify zero OPEN DETERMINISTIC findings (FR-007 — defense in depth beyond the subprocess gateway).
- Progress heartbeat: every OperationExecution in RUNNING must have a progress_event ≤5 s old (FR-011).
- Workspace budget rollup update uses optimistic `updated_at` check; retry on conflict (concurrent branches of concurrent cases will contend — FR-017).
- All V10–V12 tables: RLS policy + `d2os_app` grants; `progress_event` additionally append-only (T6-a treatment).
