# Quickstart: Graph Projection + Analytics — Validation Guide

**Feature**: 007-graph-projection-analytics · **Date**: 2026-07-07
Proves SC-001…SC-008. References: [data-model.md](data-model.md),
[contracts/api.yaml](contracts/api.yaml), [research.md](research.md).

## Prerequisites

- Docker (PostgreSQL 16 + pgvector, MinIO); Testcontainers; no AI provider needed
- Build: `./gradlew` (or Docker `gradle:8.10-jdk21`)
- **Phase 5 built and merged** — gate events must emit the `GateEventPayload` contract the
  projector consumes (the phase's blocking upstream dependency). Phase 6 is NOT required.

## One-command validation

```bash
./gradlew :app:test
```

All Phase 1–6 suites re-run unchanged plus the six new suites below (query-latency benchmark
tagged slow — nightly CI).

## Scenario walkthroughs

### 1. Rebuild-equivalence — graph == relational truth (SC-001, SC-002) — `RebuildEquivalenceIT`

1. Run cases to Delivered (with gates, knowledge injection, trace links); incremental projection
   populates the live generation.
2. `POST /graph/admin/rebuild` → generation N+1 built from zero, per-type digests match
   relational truth, live generation flips. Assert node/edge sets identical to pre-rebuild.
3. Divergence subtest: seed a deliberate mapper fault (test hook) → rebuild FAILS loudly, prior
   generation stays live, divergence reported — never served (SC-001 100%).
4. Provenance sweep: every node/edge joins back to its `source_ref` (SC-002: zero unsourced
   elements).

### 2. Traceability queries (SC-003) — `TraceabilityQueryIT`

1. Seed a known multi-hop lineage (submission → case → artifacts → package; DERIVES_FROM /
   SATISFIES / DEPENDS_ON edges).
2. `GET /graph/traceability` TRACES_TO and DEPENDS_ON, both directions → returned paths equal the
   seeded relational relationships, including transitive hops; each hop navigable via
   `GET /graph/nodes/{id}`.
3. UI smoke: the self-contained panel renders the lineage tree (no studio module on the
   classpath — independence assertion).

### 3. Cycle detection (SC-004) — `CycleDetectionIT`

1. Project a dependency cycle spanning two cases → incremental detection alerts naming the member
   path; `GET /graph/cycles` lists it.
2. Acyclic control graph → zero findings (no false positives).
3. Scheduled-sweep subtest: cycle introduced mid-rebuild is caught by the full sweep.

### 4. Workspace isolation on the graph (SC-005) — leakage-suite extension

Traceability and node queries from workspace A return zero of workspace B's nodes/edges,
regardless of relation depth — RLS + explicit predicate, asserted both API- and SQL-level.

### 5. Payload sufficiency (SC-007) — `PayloadSufficiencyIT`

Seed a gate event stripped of a required field → projector records a `projection_gap` (missing
fields named), alerts past threshold, and does NOT project a partial gate silently;
`GET /graph/admin/gaps` lists it; rebuild result reports open gaps.

### 6. Idempotency + mid-rebuild reads — `ProjectionIdempotencyIT`

1. Replay the same outbox range twice → graph row-set identical (no duplicates).
2. Query during a slow rebuild (test-paced) → reads serve the last consistent generation;
   `/graph/admin/status` shows `rebuildInProgress=true`; never a half-built result.

### 7. Influence dashboard (SC-006) — `InfluenceDashboardIT`

For seeded cases with Phase 3 influence samples: `GET /graph/influence` attributes the recorded
deltas to the correct item versions, navigable to the exact operations/artifacts touched
(INJECTED_INTO / PRODUCED edges); a never-injected item reports `NOT_YET_MEASURABLE`.

### 8. Query-latency benchmark (SC-008) — [tagged slow]

Seed ~50k nodes / 200k edges; run the traceability query mix → p95 ≤ 2 s or predictable
pagination. A miss triggers the PD-4 escalation decision (dedicated graph store behind the same
service interface) — recorded, not silently absorbed.

## Expected results summary

| Scenario | Proves | Suite |
|---|---|---|
| 1 | SC-001, SC-002 | RebuildEquivalenceIT |
| 2 | SC-003 | TraceabilityQueryIT |
| 3 | SC-004 | CycleDetectionIT |
| 4 | SC-005 | leakage-suite extension |
| 5 | SC-007 | PayloadSufficiencyIT |
| 6 | FR-012/015 | ProjectionIdempotencyIT |
| 7 | SC-006 | InfluenceDashboardIT |
| 8 | SC-008 | latency benchmark [slow] |
