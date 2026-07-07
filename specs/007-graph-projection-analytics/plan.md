# Implementation Plan: Graph Projection + Analytics

**Branch**: `007-graph-projection-analytics` | **Date**: 2026-07-07 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/007-graph-projection-analytics/spec.md`

## Summary

Phase 7 adds a derived, rebuildable **graph read model** in a new `projection` module: typed
`graph_node` / `graph_edge` tables (PostgreSQL, recursive CTEs — PD-4: a dedicated graph DB only
if the benchmark fails) populated exclusively by a **projector** consuming the event outbox
(including Phase 5's `GateEventPayload` contract) and the polymorphic edge tables
(`trace_link`, `dependency`) plus knowledge-injection snapshots. Every node/edge carries its
source provenance (event id or edge row); projection is **idempotent** (deterministic natural
keys, upserts) and **generational** — a rebuild materializes generation N+1 while reads serve N,
then flips atomically, so a rebuild is a drop-and-replay that must pass a **rebuild-equivalence
check** (per-type digests of graph vs. relational truth; divergence fails loudly). A
**projection-sufficiency audit** validates event payloads against the projector's requirements and
records gaps as findings instead of building a silently incomplete graph. On top: workspace-scoped
**TRACES_TO / DEPENDS_ON** traceability queries (recursive CTEs, multi-hop, API + a self-contained
UI panel — independent of the Phase 6 studio), **dependency-cycle detection** (whole-graph,
cross-case, alerting by persisting rows through Phase 5's in-app notification store — in-app only
in v1, no email/webhook), and a **knowledge-influence dashboard**
joining injection edges with the Phase 3 `knowledge_influence` KPI samples.

## Technical Context

**Language/Version**: Java 21 (Gradle toolchain; build runs via Docker `gradle:8.10-jdk21`)

**Primary Dependencies**: Spring Boot 3.3.5, Spring Data JPA + JDBC (recursive CTE queries),
Flyway; Thymeleaf + htmx for the self-contained traceability panel (same conventions as Phase 6
but no dependency on the studio module); no graph database (PD-4)

**Storage**: PostgreSQL 16 (RLS-enforced): derived `graph_node`/`graph_edge` (generational),
`projection_checkpoint`, `projection_gap` — all rebuildable, never a source of truth (V23)

**Testing**: JUnit 5 + Testcontainers; existing suites re-run unchanged; new RebuildEquivalenceIT
(drop + replay ⇒ digest match; seeded divergence ⇒ loud failure), TraceabilityQueryIT (multi-hop
TRACES_TO/DEPENDS_ON incl. workspace scoping), CycleDetectionIT (cross-case cycle; acyclic ⇒ zero
false positives), PayloadSufficiencyIT (seeded thin event ⇒ gap finding), InfluenceDashboardIT,
idempotency/replay-twice tests; query-latency benchmark at seeded scale [tagged slow]

**Target Platform**: Linux server (single-region cloud), local dev via docker-compose

**Project Type**: Modular-monolith web service (adds a 14th module: `projection`)

**Performance Goals**: Traceability queries within their latency target at seeded scale
(benchmark: p95 ≤ 2 s at ~50k nodes / 200k edges, PD-4 escalation trigger if exceeded);
incremental projection lag ≤ 30 s behind the outbox; full rebuild of the benchmark dataset
≤ 10 min

**Constraints**: Graph never authored directly — sole writer is the projector (enforced by
module boundary + DB grants: only the projector role writes graph tables); every element carries
source provenance (FR-003); reads during rebuild serve the last consistent generation, never a
half-built graph (FR-015); projection idempotent under event replay (FR-012); workspace isolation
preserved in every query (FR-007); deletion/deprecation reflected without phantom nodes (FR-014)

**Scale/Scope**: 1 new module, 5 derived tables (V23: graph_node, graph_edge, projection_checkpoint, projection_state, projection_gap), 1 projector worker + rebuild job +
equivalence verifier + sufficiency audit, ~6 API endpoints + 1 UI panel, 6 new IT suites +
1 benchmark

## Constitution Check

*GATE: evaluated against Constitution v1.0.0 before Phase 0 research; re-checked after Phase 1 design.*

| # | Principle | Status | How this plan complies |
|---|---|---|---|
| I | Definition/Instance Immutability | ✅ PASS | No definitions are created or mutated; graph nodes referencing definitions carry their exact `(key, version)` from the source events/edges. The projector ships as code, not authorable definitions. |
| II | Reproducible, Bounded AI (NON-NEGOTIABLE) | ✅ PASS | No AI participates in this phase. The influence dashboard *reads* Phase 3's recorded KPI samples and injection snapshots; it computes nothing new about AI behavior and gates nothing. |
| III | System of Record Integrity | ✅ PASS | This phase *implements* Principle III's second half: the graph is an explicitly derived, rebuildable CQRS projection from outbox + generalized edge tables — provenance on every element, rebuild-equivalence verified against relational truth, divergence fails loudly, and DB grants make the projector the only writer so the graph structurally cannot become a second source of truth. |
| IV | Workspace Isolation & Provenance | ✅ PASS | Graph tables carry `workspace_id` + standard RLS (projected from source rows); every traceability query is workspace-bound and the leakage suite is extended over the graph (SC-005). Node/edge provenance columns answer "where did this come from" for every element. |
| V | Default-Deny Security & Auditable Gates | ✅ PASS | No new trust-sensitive transitions are introduced; cycle and tamper-adjacent findings (gaps, divergence) alert through the audited notification/outbox path. The graph exposes gate history (from Phase 5 events) read-only — it cannot decide or reopen anything. |

**Post-design re-check (after data-model + contracts)**: no violations introduced — V23 tables
are derived-only (TRUNCATE-safe), RLS-policied, written solely by the projector grant. **GATE:
PASS.**

## Project Structure

### Documentation (this feature)

```text
specs/007-graph-projection-analytics/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── api.yaml         # Phase 1 output (delta over Phase 6 api.yaml)
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created by /speckit-plan)
```

### Source Code (repository root)

New 14th module `projection`; no changes to source-of-truth modules beyond reading them:

```text
projection/                    # NEW MODULE: derived graph read model + analytics
├── src/main/java/com/d2os/projection/
│   ├── Projector.java                 # outbox consumer + edge-table reader → upserts (idempotent)
│   ├── NodeEdgeMapper.java            # event/edge → typed nodes/edges w/ natural keys + provenance
│   ├── RebuildJob.java                # generation N+1 build → equivalence check → atomic flip
│   ├── EquivalenceVerifier.java       # per-type digests: graph vs. relational truth (FR-002)
│   ├── PayloadSufficiencyAuditor.java # event payload vs. projector requirements → projection_gap
│   ├── query/                         # TraceabilityQueryService (recursive CTEs, TRACES_TO/DEPENDS_ON)
│   ├── cycle/                         # CycleDetector (whole-graph, cross-case) + alerting
│   ├── influence/                     # InfluenceAnalyticsService (injection edges × kpi_sample)
│   ├── api/                           # TraceabilityController, GraphAdminController, InfluenceController
│   └── ui/                            # self-contained traceability panel (Thymeleaf — NOT studio-dependent)
├── src/main/resources/db/migration/V23__graph_projection.sql
└── build.gradle                       # reads casecore/artifacts/knowledge/governance types; writes none of them
app/
└── src/test/java/com/d2os/app/
    ├── RebuildEquivalenceIT.java      # US1: drop+replay ⇒ identical; seeded divergence ⇒ loud fail
    ├── TraceabilityQueryIT.java       # US2: multi-hop lineage; workspace scoping; navigability
    ├── CycleDetectionIT.java          # US3: cross-case cycle alerted; acyclic ⇒ no false positive
    ├── PayloadSufficiencyIT.java      # FR-011: thin event ⇒ gap finding, not silent incompleteness
    ├── ProjectionIdempotencyIT.java   # FR-012: replay-twice ⇒ same graph; mid-rebuild reads (FR-015)
    └── InfluenceDashboardIT.java      # US4: correct attribution along injection edges
```

**Structure Decision**: `projection` is the read-model bounded context (the plan's "Projection
worker, Analytics API"); it depends on other modules' *events and schemas read-only* and owns the
only write path to the graph tables (separate DB grant). The traceability UI panel lives inside
`projection` so Phase 7 keeps its plan-stated independence from Phase 6 (the studio can link to
it later). Migration **V23** continues the ordered stream.

## Complexity Tracking

> No constitution violations — table intentionally empty. (The 14th module is the named
> projection/analytics service; putting a write-isolated read model inside a source-of-truth
> module would blur exactly the boundary Principle III depends on.)

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| — | — | — |
