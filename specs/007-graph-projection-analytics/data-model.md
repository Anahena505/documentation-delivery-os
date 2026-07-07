# Data Model: Graph Projection + Analytics

**Feature**: 007-graph-projection-analytics · **Date**: 2026-07-07
Delta over the Phase 1–6 schema (V1–V22). Migration: **V23** (projection module). All tables are
**derived** — rebuildable from the outbox + edge tables at any time, written only by the
`d2os_projector` role (SELECT-only for `d2os_app`), RLS-scoped per workspace. Research
references: [research.md](research.md) R2–R6, R9.

## New Entities (all derived — never a source of truth)

### GraphNode (V23, projection)

| Field | Type | Notes |
|---|---|---|
| id | uuid PK | surrogate; identity is the natural key below |
| workspace_id | uuid NOT NULL | RLS scope (projected from source) |
| generation | int NOT NULL | generational rebuild (R4); live reads filter on `projection_state.live_generation` |
| node_type | text NOT NULL | `CASE` \| `SUBMISSION` \| `ARTIFACT_REVISION` \| `PACKAGE` \| `DEFINITION_VERSION` \| `KNOWLEDGE_ITEM_VERSION` \| `OPERATION_EXECUTION` \| `GATE` \| `FEATURE` \| `PROJECT` |
| natural_key | text NOT NULL | deterministic source identity (R3); UNIQUE (workspace_id, generation, node_type, natural_key) |
| label | text NOT NULL | display name for the UI panel |
| attributes | jsonb NOT NULL | type-specific projected fields (status, key/version, …) |
| source_kind | text NOT NULL | `OUTBOX_EVENT` \| `TRACE_LINK` \| `DEPENDENCY` \| `INJECTION_SNAPSHOT` (R2) |
| source_ref | text NOT NULL | event id / edge row id / snapshot id — FR-003: no unsourced element |
| projected_at | timestamptz NOT NULL | |

### GraphEdge (V23, projection)

| Field | Type | Notes |
|---|---|---|
| id | uuid PK | |
| workspace_id | uuid NOT NULL | |
| generation | int NOT NULL | |
| edge_type | text NOT NULL | `TRACES_TO` \| `DEPENDS_ON` \| `DERIVES_FROM` \| `SATISFIES` \| `INJECTED_INTO` \| `PRODUCED` \| `GATED_BY` \| `BELONGS_TO` |
| from_node / to_node | uuid NOT NULL | FK → graph_node |
| attributes | jsonb NOT NULL | e.g. gate verb, injection position |
| source_kind / source_ref | text NOT NULL | provenance, as on nodes |
| projected_at | timestamptz NOT NULL | |

**Indexes**: `(workspace_id, generation, from_node, edge_type)` and
`(workspace_id, generation, to_node, edge_type)` — both traversal directions (R7).
**Identity**: UNIQUE (workspace_id, generation, edge_type, from_node, to_node, source_ref) —
idempotent upserts (R3/FR-012).

### ProjectionCheckpoint + ProjectionState (V23, projection)

| Field | Type | Notes |
|---|---|---|
| consumer | text PK component | e.g. `graph-projector` |
| workspace_id | uuid | checkpoint per workspace |
| outbox_watermark | uuid / bigint | last consumed outbox position (incremental runs; rebuilds ignore it) |
| updated_at | timestamptz | |

`projection_state` (one row per workspace): `live_generation int NOT NULL` — the atomic flip
target of `RebuildJob` (R4); `last_equivalence_check timestamptz`, `last_equivalence_result text`.

### ProjectionGap (V23, projection)

Projection-sufficiency findings (R6, FR-011). Append-only.

| Field | Type | Notes |
|---|---|---|
| id | uuid PK | |
| workspace_id | uuid NOT NULL | |
| event_id | uuid NOT NULL | the insufficient outbox event |
| event_type | text NOT NULL | |
| missing_fields | text[] NOT NULL | what the projector required and did not find |
| detected_at | timestamptz NOT NULL | |
| status | text NOT NULL | `OPEN → RESOLVED` (e.g. after emitter fix + rebuild) |

## Modified Entities

**None.** Source-of-truth modules are untouched; the projector reads `event_outbox`,
`trace_link`, `dependency`, `knowledge_injection_snapshot`, `kpi_sample`, and (via Phase 5's
`GateEventPayload` contract) gate lifecycle events. DB roles: V23 creates the `d2os_projector`
role/grants (write graph tables only; read sources).

## Node/Edge Mapping (projector contract — R3)

| Source | Projects to |
|---|---|
| Case lifecycle outbox events | `CASE` node (+ `BELONGS_TO` → FEATURE/PROJECT) |
| Artifact/package events | `ARTIFACT_REVISION`, `PACKAGE` nodes; `PRODUCED` edges from executions |
| Artifact revisions of requirement-type templates | `REQUIREMENT` nodes (artifact subtype — same natural key/provenance as the revision; template type distinguishes them, FR-005) |
| `trace_link` rows | `TRACES_TO` / `DERIVES_FROM` / `SATISFIES` edges (kind-preserving) |
| `dependency` rows | `DEPENDS_ON` edges |
| `knowledge_injection_snapshot` rows | `KNOWLEDGE_ITEM_VERSION` nodes + `INJECTED_INTO` edges |
| Gate events (`GateEventPayload`, Phase 5) | `GATE` nodes + `GATED_BY` edges (verb, decider in attributes) |
| Definition references in snapshots/events | `DEFINITION_VERSION` nodes (exact key:version) |

## State & Lifecycle

```
Incremental: outbox event → sufficiency audit → (ok) idempotent upsert into live generation
                                        └→ (thin) projection_gap row + alert threshold
Rebuild:     build generation N+1 from zero → EquivalenceVerifier (per-type digests vs.
             relational truth) → PASS: flip live_generation, purge N
                               → FAIL: keep N live, drop N+1, divergence alert (never served)
Deletion/deprecation in source → event projected as node/edge removal or status attribute in the
             live generation; rebuild reflects current truth (no phantom nodes, FR-014)
```

## Validation Rules (from requirements)

- Sole writer: only `d2os_projector` may write graph tables (grant-enforced, R2/FR-001).
- No unsourced element: `source_kind`/`source_ref` NOT NULL; verifier joins them back (FR-003).
- Idempotency: natural-key upserts; replay-twice test asserts row-set equality (FR-012).
- Reads: always `generation = live_generation` — mid-rebuild reads are stale-consistent, never
  partial (FR-015).
- Queries: RLS + explicit workspace predicate; node budget + pagination past 500 nodes (FR-007,
  FR-013).
- Cycle alerts: whole-workspace graph, cross-case; zero alerts on acyclic graphs (FR-008/009).
