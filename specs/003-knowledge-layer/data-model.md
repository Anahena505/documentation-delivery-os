# Data Model: Knowledge Layer

**Feature**: 003-knowledge-layer · **Date**: 2026-07-07
Delta over the Phase 1–2 schema (V1–V12). All new tables carry `workspace_id uuid NOT NULL` with
the standard RLS policy (`app.workspace_id` session setting) and are granted to `d2os_app` per
V8's default privileges. Migrations: **V13** (knowledge core, knowledge module), **V14** (injection
snapshot + `operation_execution.evaluation`, persona module), **V15** (`knowledge_influence` KPI
CHECK widening + `dimensions`, observability module), **V16** (knowledge_item immutability trigger,
knowledge module). Research references: [research.md](research.md) R2, R4–R9.

## New Entities

### KnowledgeItem (V13, knowledge) — LIST-partitioned by `workspace_id`

The immutable, versioned unit of governed knowledge (FR-001). Follows the `(key, version)`
discipline of `definition_asset` but is workspace content, not a catalog definition. Any change —
including a Curator redaction — produces a new version row; versions are never mutated.

| Field | Type | Notes |
|---|---|---|
| id | uuid | PK component (with `workspace_id` — partitioned table) |
| workspace_id | uuid NOT NULL | partition key + RLS scope |
| key | text NOT NULL | stable identity across versions |
| version | int NOT NULL | UNIQUE (workspace_id, key, version) |
| scope_level | text NOT NULL | `WORKSPACE` \| `PROJECT`. The CHECK also admits `GLOBAL`, but it is **reserved/unreachable in v1**: `knowledge_item` is LIST-partitioned per workspace, so a row cannot live outside some workspace's partition, and the retrieval scope-lattice defines no GLOBAL semantics. Cross-workspace sharing (Phase 6) is expected to be copy-on-subscribe, not in-place GLOBAL scope (R4). |
| scope_ref | uuid NOT NULL | workspace id or project id per scope_level |
| tags | text[] NOT NULL | retrieval tag match |
| locale | text NOT NULL DEFAULT 'en' | carries the Q11 locale dimension forward |
| title | text NOT NULL | |
| content | text NOT NULL | the injectable knowledge text |
| content_hash | text NOT NULL | SHA-256 (integrity, Principle III) |
| embedding | vector NOT NULL | pgvector; HNSW index per partition (R2/R3) |
| embed_model | text NOT NULL | model identity/version used to embed (Principle II) |
| status | text NOT NULL | `PUBLISHED → DEPRECATED` (items exist only from publish; pre-publish life is CaptureCandidate) |
| source_candidate_id | uuid NULL | provenance: candidate this version was published from |
| supersedes_version | int NULL | provenance chain across versions |
| created_at / deprecated_at | timestamptz | `deprecated_at` set by DeprecationService |
| deprecation_reason | text NULL | required when status=DEPRECATED |

**State transitions**: `PUBLISHED → DEPRECATED` only (audited governance action recorded as an
**AuditEntry**, not a Decision row — the V4 `decision` table is case-bound + D-gate-CHECK-constrained,
so it cannot hold a case-independent knowledge deprecation; see research.md R8 / tasks.md T040).
No other transition exists; correction = publish a new version. All non-status columns
(content/hash/embedding/key/version/…) are **immutable after insert**, enforced by the V16 trigger,
so the byte-for-byte guarantee that injection snapshots depend on cannot be silently broken.
**Invariants**: retrieval predicate always includes `status='PUBLISHED'` and scope
ancestor-or-equal (R10); seed set loaded via CatalogSeedLoader-style loader with provenance
(FR-021).

### CaptureCandidate (V13, knowledge)

A lessons-learned proposal from the case-end capture subprocess (FR-008). Born
project-confidential and non-promotable; the promotion pipeline is a strict state machine.
Redaction creates a **new revision row** (version-controlled, FR-011) — `revision_of` chains them.

| Field | Type | Notes |
|---|---|---|
| id | uuid PK | |
| workspace_id | uuid NOT NULL | RLS scope |
| case_instance_id | uuid NOT NULL | source case (FK → case_instance) |
| project_id | uuid NOT NULL | birth scope (always PROJECT — R4) |
| revision | int NOT NULL | 1 = raw capture; +1 per redaction save |
| revision_of | uuid NULL | prior revision id (NULL for revision 1) |
| title / content | text NOT NULL | sensitive-tagged source fields already excluded by default (T3-c, R7) |
| tags | text[] NOT NULL | proposed retrieval tags |
| status | text NOT NULL | see state machine below |
| curator_operation_execution_id | uuid NULL | FK → operation_execution (the Curator redaction op snapshot) |
| rejection_stage | text NULL | `PREFILTER` \| `CURATION` \| `D4` |
| rejection_reason | text NULL | required when REJECTED (FR-013) |
| created_at | timestamptz NOT NULL | |

**State machine** (FR-009, order is fixed; no skip path exists in code):

```
CAPTURED → PREFILTERED → REDACTED → D4_PENDING → PUBLISHED
     └────────┴─────────────┴────────────┴──────→ REJECTED (terminal, with stage+reason)
```

- `CAPTURED → PREFILTERED`: deterministic pre-filter ran; findings recorded.
- `PREFILTERED → REDACTED`: Curator persona redaction saved as a new revision, rubric-validated
  (FR-012); D4 reviews the **latest revision only**.
- `REDACTED → D4_PENDING → PUBLISHED`: workspace-owner user task approves → publish service task
  creates the KnowledgeItem version (scope raised to the approved level) with
  `source_candidate_id` provenance. D4 approver identity must differ from the Curator actor
  (edge case: non-self-satisfiable gate).
- Any stage → `REJECTED`: candidate stays confidential/non-promotable; no partial promotion.

### PrefilterFinding (V13, knowledge)

Deterministic sensitivity/PII findings feeding the redaction step (FR-010, R7). Append-only.

| Field | Type | Notes |
|---|---|---|
| id | uuid PK | |
| workspace_id | uuid NOT NULL | |
| candidate_id | uuid NOT NULL | FK → capture_candidate (revision 1) |
| category | text NOT NULL | `EMAIL` \| `PHONE` \| `ID_NUMBER` \| `CREDENTIAL` \| `TAGGED_SENSITIVE` |
| span_start / span_end | int NOT NULL | location in candidate content |
| source | text NOT NULL | `PATTERN:<name>` or `INTAKE_TAG:<field>` (T3-a propagation) |
| created_at | timestamptz NOT NULL | |

### PromotionGateRecord (V13, knowledge)

One row per gate outcome per candidate (FR-013) — the auditable pipeline trail alongside the
existing Decision/AuditEntry rows (which are still written; this table is the knowledge-side
index of them).

| Field | Type | Notes |
|---|---|---|
| id | uuid PK | |
| workspace_id | uuid NOT NULL | |
| candidate_id | uuid NOT NULL | FK → capture_candidate |
| gate | text NOT NULL | `PREFILTER` \| `CURATION` \| `D4` |
| outcome | text NOT NULL | `PASS` \| `REJECT` |
| decision_id | uuid NULL | FK → decision (D4 human decision; NULL for automated prefilter) |
| actor | text NOT NULL | user id, or `system:prefilter`, or Curator persona key+version |
| detail | text NULL | reason / rubric score ref |
| created_at | timestamptz NOT NULL | |

**Invariant**: a candidate may have at most one `PASS` row per gate, and gates must PASS in
pipeline order (enforced in `PromotionGateService`, asserted by CapturePromotionIT).

### KnowledgeAffectedExecution (V13, knowledge)

Deprecation flags (FR-015, R8). Append-only; written in the same transaction as the deprecation.

| Field | Type | Notes |
|---|---|---|
| id | uuid PK | |
| workspace_id | uuid NOT NULL | |
| knowledge_item_key | text NOT NULL | + `knowledge_item_version int NOT NULL` — the deprecated version used |
| operation_execution_id | uuid NOT NULL | FK → operation_execution (never mutated) |
| case_instance_id | uuid NOT NULL | denormalized for operator review listing (SC-007) |
| flagged_at | timestamptz NOT NULL | |
| review_status | text NOT NULL | `OPEN → REVIEWED` (operator acknowledgement only; never touches the execution) |

### KnowledgeInjectionSnapshot (V14, persona)

The per-execution injection record (FR-006, R5). Written by `OperationExecutionRecorder` in the
**same transaction** as `operation_execution`. Append-only, immutable.

| Field | Type | Notes |
|---|---|---|
| id | uuid PK | |
| workspace_id | uuid NOT NULL | |
| operation_execution_id | uuid NOT NULL | FK → operation_execution |
| knowledge_item_id | uuid NOT NULL | exact item row injected |
| knowledge_item_key | text NOT NULL | + `knowledge_item_version int NOT NULL` — exact `(id, version)`, never a mutable pointer (AD-1) |
| content_hash | text NOT NULL | hash of injected content at injection time (replay verification) |
| position | int NOT NULL | order within the envelope's knowledge slot (byte-identical replay) |
| created_at | timestamptz NOT NULL | |

**Invariant**: UNIQUE (operation_execution_id, position); replay reconstructs the knowledge slot
by ordering on `position` and verifying `content_hash` against the snapshotted item version
(FR-007). Executions run for influence evaluation carry the same snapshots (R9).

## Modified Entities

| Entity | Change |
|---|---|
| `operation_execution` (V5, casecore) | **Additive column** `evaluation boolean NOT NULL DEFAULT false` (V14) so influence-evaluation runs never feed delivery or dilute delivery KPIs (R9); also gains child rows in `knowledge_injection_snapshot`. No existing column altered. |
| `AiGatewayClient` (persona) | + `embed(EmbedRequest)` operation; injection path asserts caller workspace vs. every injected item's workspace (T2-c) before prompt assembly — refusal is audited. |
| `PersonaDefinition` (catalog, content-level) | Knowledge profile (allowed tags/domains) carried in the definition body — a **content** addition to the Curator + existing persona definitions via new published versions; no catalog schema change. |
| `kpi_sample` (V9, observability) | **V15 widening** (the V9 `metric` CHECK forbids unknown names, so a schema change is required): the `metric` CHECK is re-created to include `knowledge_influence`, and a `dimensions JSONB DEFAULT '{}'` column + partial index on `(dimensions->>'key', dimensions->>'version')` carry the item key/version (R9, T046). |

## Relationships (summary)

```
case_instance ──< capture_candidate ──< prefilter_finding
                        │ ──< promotion_gate_record >── decision
                        │ (revision chain: revision_of)
                        └──(publish, D4 PASS)──> knowledge_item (source_candidate_id provenance)
knowledge_item (key,version) ──< knowledge_injection_snapshot >── operation_execution
knowledge_item (DEPRECATED) ──< knowledge_affected_execution >── operation_execution
```

## Retention & Audit

- Every state transition above writes an `AuditEntry` in the same transaction (Principle III);
  D4 outcomes additionally write a `Decision` row (Principle V).
- Nothing in this schema updates or deletes historical rows: candidates chain revisions, items
  chain versions, snapshots and flags are append-only. The only in-place status flips are
  `knowledge_item.status → DEPRECATED` (audited, content untouched) and
  `knowledge_affected_execution.review_status → REVIEWED` (operator acknowledgement).
