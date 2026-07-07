# Research: Graph Projection + Analytics

**Feature**: 007-graph-projection-analytics · **Date**: 2026-07-07
All NEEDS CLARIFICATION items from the Technical Context are resolved below.

## R1. Graph store — PostgreSQL adjacency tables + recursive CTEs (PD-4)

**Decision**: The graph is two relational tables, `graph_node` and `graph_edge` (typed, RLS'd,
workspace-scoped), queried with recursive CTEs for multi-hop traversal. No graph database.
A dedicated graph store is adopted **only** if the traceability-query benchmark exceeds its p95
target (PD-4's documented escalation), behind the same `TraceabilityQueryService` interface so the
swap costs an adapter.

**Rationale**: PD-4 verbatim: the projection is rebuildable by definition (AD-6), so the storage
choice is low-reversal-cost — start with the engine already operated, secured (RLS), backed up
(Phase 5 DR), and tested. Expected scale (tens of thousands of nodes per workspace) is well within
recursive-CTE competence when edges are indexed both directions.

**Alternatives considered**: Neo4j/AGE from day one — rejected (new operational surface, RLS/DR
re-solved from scratch, against PD-4); Apache AGE Postgres extension — rejected for v1 (younger
operational maturity than plain tables; CTEs suffice until the benchmark says otherwise).

## R2. Sole-writer enforcement and provenance columns

**Decision**: V23 grants INSERT/UPDATE/DELETE on graph tables to a dedicated `d2os_projector`
role only; the app's normal `d2os_app` role gets SELECT. The projector datasource binds the
projector role; every other module can only read. Every node/edge row carries provenance:
`source_kind` (`OUTBOX_EVENT` | `TRACE_LINK` | `DEPENDENCY` | `INJECTION_SNAPSHOT`) +
`source_ref` (event id / edge row id / snapshot id), satisfying FR-003 ("no unsourced element")
mechanically.

**Rationale**: Principle III demands the graph never become a second source of truth — a grant is
structural where code review is aspirational. Provenance columns turn the "every element backed by
a fact" invariant into a NOT NULL constraint plus a verifiable join.

**Alternatives considered**: trusting module boundaries alone — rejected (one misplaced `@Autowired`
repository would silently break the invariant); event-sourcing the graph itself — rejected (the
outbox already is the event source; the graph is the projection, not another log).

## R3. Idempotent projection via deterministic natural keys

**Decision**: Node identity = `(workspace_id, node_type, natural_key)` where `natural_key` is the
source entity's id (case id, artifact revision id, definition `type:key:version`, knowledge item
`key:version`, gate id…). Edge identity = `(workspace_id, edge_type, from_node, to_node,
source_ref)`. The projector upserts on these keys (`ON CONFLICT DO UPDATE`), so replaying any
event or re-reading any edge row is a no-op — FR-012 (replay-twice ⇒ identical graph) holds by
construction. `projection_checkpoint` tracks the outbox watermark per consumer for incremental
runs; a rebuild ignores checkpoints and replays from zero.

**Rationale**: Deterministic keys make idempotency a property of the schema rather than of
careful consumer bookkeeping; duplicate delivery (at-least-once outbox) and rebuild replay become
the same code path. Natural keys also make the equivalence check (R5) a set comparison.

**Alternatives considered**: surrogate-keyed inserts + dedup pass — rejected (a window where the
graph holds duplicates violates "queryable at any time"); exactly-once delivery machinery —
rejected (idempotent upserts are simpler and stronger).

## R4. Generational rebuild — serve N while building N+1 (FR-015)

**Decision**: Graph tables carry a `generation int` column; live reads always filter
`generation = (SELECT live_generation FROM projection_state)`. `RebuildJob` builds generation
N+1 from scratch (full outbox replay + edge-table scan), runs the equivalence verifier (R5)
against relational truth, and only on PASS atomically flips `live_generation` and purges N.
On FAIL, N stays live, N+1 is dropped, and a divergence alert fires — a divergent rebuild is
never served (FR-002). Incremental projection continues against the live generation between
rebuilds.

**Rationale**: FR-015 verbatim (mid-rebuild reads serve the last consistent graph, never a
half-built one) with a plain-SQL mechanism — no table swaps or dual schemas. The verify-then-flip
ordering makes "rebuild diverged" a loud pre-serve failure instead of a silent corruption.

**Alternatives considered**: build-in-place with a "rebuilding" read block — rejected (blocks
users for the whole rebuild; spec allows stale-but-consistent reads); shadow schema + rename —
rejected (DDL-heavy, RLS policy duplication, no benefit over a generation column at this scale).

## R5. Rebuild-equivalence verification (FR-002, "graph == relational truth")

**Decision**: `EquivalenceVerifier` computes, per workspace and node/edge type, an
order-independent digest (count + XOR/SHA-256 over canonical `natural_key` rows) on **both**
sides: the candidate graph generation, and the relational truth queried directly from the source
tables (outbox-derived facts, trace_link, dependency, snapshots). Digests must match exactly per
type; any mismatch reports the differing type + sample keys and fails the rebuild (kept
inspectable for diagnosis). The same verifier runs scheduled (weekly) against the live generation
as drift detection.

**Rationale**: Comparing digests of natural keys — the same keys the projector derives (R3) —
tests the projection end-to-end without a second projector implementation sneaking in as the
"oracle": the truth side is assembled from the *source tables*, not from replaying events again.
Per-type granularity makes divergence actionable (SC-001's 100% match, and the seeded-divergence
edge case, are directly assertable).

**Alternatives considered**: row-by-row diff — kept as the drill-down path on digest mismatch,
too slow as the primary check; trusting idempotency alone — rejected (idempotency proves
replay-stability, not correctness against truth).

## R6. Projection-sufficiency audit (FR-011)

**Decision**: `PayloadSufficiencyAuditor` validates every consumed outbox event against the
projector's declared requirements per event type (required fields to place nodes/edges — for gate
events, the Phase 5 `GateEventPayload` contract). An insufficient event is **not** projected
silently-partially: the auditor writes a `projection_gap` row (event id, type, missing fields),
raises an alert past a threshold, and the projector skips the unprojectable parts deterministically.
Gap rows surface in the admin API; a rebuild with open gaps reports them in its result. The audit
also runs in "dry sweep" mode across historical events (the E7.1 payload audit task).

**Rationale**: The spec's edge case verbatim — a thin event must be caught and reported, not
produce a silently incomplete graph. Declaring requirements per event type in the projector makes
the Phase 5 contract dependency executable and keeps the audit in lockstep with mapper changes.

**Alternatives considered**: failing the whole projection run on any thin event — rejected (one
bad historical event would wedge the pipeline; recorded gaps + alerting keep the system live and
honest); schema validation at emit time only — insufficient (emitters can drift; the consumer
must defend itself).

## R7. Traceability queries + self-contained UI panel (FR-005–007)

**Decision**: `TraceabilityQueryService` exposes `tracesTo(nodeRef, direction, maxDepth)` and
`dependsOn(nodeRef, direction, maxDepth)` as recursive CTEs over `graph_edge` (indexed
`(workspace_id, from_node, edge_type)` and `(workspace_id, to_node, edge_type)`), returning paths
with per-hop edge provenance; results paginate past a configurable node budget (default 500) —
predictable degradation per FR-013. RLS scopes every query; the leakage suite is extended over
graph queries (SC-005). A minimal Thymeleaf panel inside the `projection` module (search box →
lineage tree → node links back to the owning API resources) satisfies "queries in UI" without
depending on the Phase 6 studio — preserving the plan's "Phase 6 not required" independence.

**Rationale**: Multi-hop lineage is exactly what `WITH RECURSIVE` does well at this scale;
paginated bounded traversal converts the "very deep lineage" edge case into a UX behavior instead
of a timeout. Panel placement follows the dependency map, not module aesthetics.

**Alternatives considered**: shipping the panel in the studio — rejected (would silently create
the Phase 6 dependency the plan explicitly relaxes); GraphQL API — rejected (two REST endpoints
with depth parameters cover the stated queries; no client demands GraphQL flexibility yet).

## R8. Cycle detection — whole-graph, incremental + scheduled (FR-008/009)

**Decision**: `CycleDetector` runs (a) **incrementally**: on each projected `DEPENDS_ON` edge, a
bounded recursive CTE checks whether the new edge's target reaches its source (cycle closed ⇒
alert naming the member path); and (b) **scheduled**: a full-graph sweep (per workspace) using
iterative peeling (Kahn-style) to enumerate all cycles, catching anything the incremental path
missed (e.g., edges arriving during rebuild). Detection spans the whole workspace graph — not
per-case subgraphs — so cross-case cycles are found (US3). Alerts persist `in_app_notification`
rows through Phase 5's in-app notification store (in-app only in v1) + an outbox event; findings
are queryable via the admin API. Acyclic graphs
produce zero alerts (FR-009, asserted).

**Rationale**: Incremental check gives immediate feedback at edge-insert time for the common
case; the scheduled sweep is the completeness backstop. Reachability-on-insert is cheap because
it only traverses from one node.

**Alternatives considered**: scheduled-only — rejected (a cycle could sit undetected for a full
period); maintaining a topological order online — rejected (more state to keep consistent than
the problem warrants at this scale).

## R9. Knowledge-influence analytics on the graph (FR-010)

**Decision**: The projector materializes `INJECTED_INTO` edges (KnowledgeItem version →
OperationExecution, from Phase 3 injection snapshots) and `PRODUCED` edges (OperationExecution →
ArtifactRevision). `InfluenceAnalyticsService` joins these edges with `kpi_sample`
(`metric='knowledge_influence'`, read-only from observability) to serve the dashboard: per item —
influence readings, the operations/artifacts it touched, navigable along real lineage; per case —
which knowledge shaped which delivered artifacts. Items with no samples render as
not-yet-measurable (consistent with Phase 3's FR-018); the dashboard computes no new influence
values.

**Rationale**: US4 verbatim — influence explored *along the graph* rather than as a flat report;
reusing recorded KPI samples keeps Phase 3 the single owner of the metric's semantics
(Principle III: the dashboard is a view, not a second computation).

**Alternatives considered**: recomputing influence in the projection — rejected (two owners of
one metric drift; the projection projects, it does not measure).
