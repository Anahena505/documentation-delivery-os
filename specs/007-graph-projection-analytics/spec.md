# Feature Specification: Graph Projection + Analytics

**Feature Branch**: `007-graph-projection-analytics`

**Created**: 2026-07-07

**Status**: Draft

**Input**: Phase 7 of the D2OS phased implementation plan — stand up a derived, rebuildable graph read model projected from the append-only event outbox and the generalized edge tables, so that end-to-end traceability questions ("what does this artifact trace to?", "what depends on this requirement?") can be answered directly, dependency cycles are detected and alerted, and knowledge-influence analytics are visualized on the graph. The graph is never a source of truth — it is a projection that can be dropped and rebuilt to an exact copy of the relational record at any time.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - The graph is a rebuildable projection, never a source of truth (Priority: P1)

An operator must be able to drop the entire graph read model and rebuild it from scratch — replaying the
event outbox and re-reading the edge tables — and get a graph that is provably identical to what the
relational system-of-record says is true. Nothing is authored directly into the graph; every node and edge
in it exists because a relational fact or an emitted event put it there. This is the foundational
invariant that makes every query and dashboard built on top of the graph trustworthy: if the graph can
always be reconstructed from the record, it can never silently drift into a second, conflicting truth.

**Why this priority**: The whole phase rests on this. If the graph could hold state that the relational
record does not, every traceability answer and analytic drawn from it would be suspect and the audit
guarantees of earlier phases would be undermined. The rebuildable-projection property is therefore the
first thing that must hold, and everything else in the phase is built on it.

**Independent Test**: Run a set of cases to completion, snapshot the relational truth (cases, artifacts,
requirements, definitions, and their trace/dependency edges), drop the graph entirely, run the
rebuild-from-scratch job, and confirm the rebuilt graph is equivalent to the relational truth (same nodes,
same edges) with zero manual intervention.

**Acceptance Scenarios**:

1. **Given** a populated relational record with emitted events and edge rows, **When** the projector runs,
   **Then** it produces a graph whose nodes and edges correspond exactly to the relational facts, with no
   node or edge that lacks a backing fact.
2. **Given** an existing graph, **When** the graph is dropped and the rebuild-from-scratch job is run,
   **Then** the rebuilt graph is verifiably equivalent to the relational truth (rebuild-equivalence holds).
3. **Given** a rebuild that would produce a graph diverging from the relational truth, **When** the
   verification step runs, **Then** it fails loudly and reports the divergence rather than silently
   accepting a wrong graph.
4. **Given** any graph node or edge, **When** its provenance is inspected, **Then** it traces back to a
   specific event or edge-table row, so no graph element is unsourced.

---

### User Story 2 - Traceability questions are answerable across the whole record (Priority: P1)

A reviewer or architect needs to ask relationship questions that span artifacts, requirements,
definitions, and cases — "what does this security requirement trace to downstream?", "what artifacts
depend on this data entity?", "what is the full lineage of this delivered package?" — and get an answer
from a query panel rather than by manually walking tables. The graph exposes the trace and dependency
edges as first-class, navigable relationships (TRACES_TO, DEPENDS_ON) so these lineage and impact
questions are one query, not a research task.

**Why this priority**: Traceability is the reason a graph read model is worth building at all — the
relational edge tables already hold the relationships, but answering multi-hop lineage questions over them
is slow and awkward. Making these questions directly answerable is the phase's headline user-facing value,
co-equal P1 with the rebuildable projection that makes the answers trustworthy.

**Independent Test**: Seed a case whose artifacts and requirements have known multi-hop trace and
dependency links, then issue TRACES_TO and DEPENDS_ON queries from the UI panel and confirm the returned
lineage matches the seeded relationships exactly, including transitive (multi-hop) reachability.

**Acceptance Scenarios**:

1. **Given** artifacts, requirements, definitions, and cases connected by trace and dependency edges,
   **When** a user issues a TRACES_TO query from a starting node, **Then** the system returns the full
   downstream/upstream lineage, including transitive relationships, scoped to the user's workspace.
2. **Given** a starting node, **When** a user issues a DEPENDS_ON query, **Then** the system returns every
   node that depends on it (and, on request, what it depends on), so impact can be seen before a change.
3. **Given** a traceability result, **When** it is displayed in the UI panel, **Then** each returned
   relationship is navigable back to the underlying artifact/requirement/definition it represents.
4. **Given** a workspace boundary, **When** any traceability query runs, **Then** it returns only nodes and
   edges within the querying user's workspace — the projection preserves the tenancy isolation of the
   record.

---

### User Story 3 - Dependency cycles are detected and alerted (Priority: P2)

Because artifacts, requirements, and definitions depend on one another, it is possible for a chain of
dependencies to close into a cycle (A depends on B depends on C depends on A) — a structural defect that
makes ordering, impact analysis, and regeneration ill-defined. The system detects such cycles in the
dependency graph and raises an alert identifying the cycle members, so the defect is surfaced for
correction rather than lurking undetected.

**Why this priority**: Cycle detection is a concrete correctness safeguard that only becomes possible once
the dependency edges are in a graph, but it is a guardrail on the data rather than the primary
traceability deliverable — so it ships in this phase at P2, after the projection and core queries.

**Independent Test**: Introduce a dependency cycle across a set of nodes (including a cycle that spans two
different cases), run detection, and confirm the cycle is reported with all its members; confirm an
acyclic graph produces no false positive.

**Acceptance Scenarios**:

1. **Given** a dependency graph containing a cycle, **When** cycle detection runs, **Then** the cycle is
   detected and an alert is raised naming the nodes that form it.
2. **Given** a dependency cycle that spans two different cases, **When** detection runs, **Then** it is
   still detected — cycle detection is not confined to a single case's subgraph.
3. **Given** an acyclic dependency graph, **When** detection runs, **Then** no cycle alert is raised (no
   false positive).

---

### User Story 4 - Knowledge influence is visible on the graph (Priority: P3)

A catalog steward wants to see which knowledge is actually shaping delivered work — which KnowledgeItems
were injected into which operations and what measurable effect they had — presented as an analytics
dashboard over the graph rather than as a flat report. Reusing the knowledge-influence signal established
in the Knowledge Layer phase, the graph relates knowledge to the operations and artifacts it touched, so
influence can be explored along real lineage.

**Why this priority**: This turns the graph into an analytics surface and closes the loop on the knowledge
investment from Phase 3, but it is a reporting enhancement layered on the traceability core — valuable,
not foundational — so it is the lowest priority in the phase.

**Independent Test**: For a set of cases with recorded knowledge-influence signals, open the dashboard and
confirm it attributes influence to the correct KnowledgeItems and operations as recorded, navigable along
the graph's injection relationships.

**Acceptance Scenarios**:

1. **Given** operations with recorded knowledge-injection snapshots and influence signals, **When** the
   dashboard is opened, **Then** it shows which KnowledgeItems influenced which operations/artifacts and
   the recorded influence measure.
2. **Given** the dashboard, **When** a user drills into a KnowledgeItem, **Then** its influence is
   navigable along the graph to the specific operations and delivered artifacts it touched.

---

### Edge Cases

- An emitted event turns out to be **projection-insufficient** (it lacks a field the graph needs to place
  a node or edge) → the projection-sufficiency audit of the event stream catches it and reports the gap,
  rather than the projector silently producing an incomplete graph.
- A rebuild **diverges** from relational truth → the equivalence verification fails and reports the
  difference; a divergent rebuild is never accepted as valid.
- A node is **deleted or deprecated** in the record after being projected → a rebuild reflects the current
  record, and incremental projection removes/flags the node so the graph does not retain phantom nodes.
- The graph is **queried mid-rebuild** → the read either serves the last consistent graph or is clearly
  marked/blocked as rebuilding, never returning a half-built graph as if it were complete.
- A traceability query spans a **very deep or wide** lineage → the query still returns bounded, complete
  results within its latency target, or paginates, rather than timing out silently.
- Two independently valid cases each add an edge that, combined, **close a cross-case cycle** → detection
  operates on the whole dependency graph, not per-case, so the cross-case cycle is still found.
- The event stream is **replayed twice** (idempotency) → reprojecting the same events produces the same
  graph, with no duplicated nodes or edges.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST maintain the graph as a derived read model projected solely from the append-only
  event outbox and the generalized trace/dependency edge tables — never authored directly — so the
  relational record remains the single source of truth.
- **FR-002**: System MUST be able to rebuild the entire graph from scratch (drop and re-project) and MUST
  verify that the rebuilt graph is equivalent to the relational truth, failing loudly on any divergence.
- **FR-003**: System MUST ensure every graph node and edge is backed by a specific event or edge-table row,
  such that no graph element exists without a traceable source in the record.
- **FR-004**: System MUST project incrementally from newly emitted events and edge changes so the graph
  stays current without requiring a full rebuild for ordinary updates.
- **FR-005**: System MUST expose traceability queries over the graph — at minimum TRACES_TO and DEPENDS_ON
  — across artifacts, requirements, definitions, and cases, including transitive (multi-hop) relationships.
- **FR-006**: Users MUST be able to run traceability queries from a UI panel and navigate each returned
  relationship back to the underlying artifact, requirement, definition, or case it represents.
- **FR-007**: System MUST scope every graph query to the querying user's workspace, preserving the tenancy
  isolation guaranteed by the relational record; a query MUST never return nodes or edges from another
  workspace.
- **FR-008**: System MUST detect cycles in the dependency graph and raise an alert that identifies the
  nodes forming the cycle, including cycles that span more than one case.
- **FR-009**: System MUST NOT raise a cycle alert for an acyclic dependency graph (no false positives).
- **FR-010**: System MUST provide a knowledge-influence analytics view on the graph that attributes the
  recorded influence signal to the KnowledgeItems, operations, and artifacts involved, navigable along the
  graph's injection relationships.
- **FR-011**: System MUST audit the event stream for projection sufficiency and report any event whose
  payload is insufficient to project the nodes/edges it should, so payload gaps are surfaced rather than
  producing a silently incomplete graph.
- **FR-012**: System MUST make projection idempotent — replaying the same events yields the same graph with
  no duplicated nodes or edges.
- **FR-013**: System MUST keep traceability-query response within p95 ≤ 2 seconds at the benchmark data
  volume (~50k nodes / 200k edges), or degrade predictably (e.g., pagination), rather than failing
  silently.
- **FR-014**: System MUST handle deletion/deprecation of a source record such that a rebuild — and
  incremental projection — reflects the current record without retaining phantom nodes.
- **FR-015**: System MUST serve reads during a rebuild from the last consistent graph or clearly signal an
  in-progress rebuild, never returning a partially built graph as if complete.

### Key Entities *(include if feature involves data)*

- **Graph read model**: The derived, rebuildable projection of the record; a set of typed nodes (artifact,
  requirement, definition, case, KnowledgeItem, operation) and typed edges (TRACES_TO, DEPENDS_ON,
  injection/influence relationships). Holds no authoritative state of its own.
- **Projector**: The worker that reads the event outbox and edge tables and materializes/updates the graph,
  both incrementally and as a full rebuild; also runs the projection-sufficiency audit.
- **Trace edge / dependency edge**: The relationship records (from the generalized edge tables) that the
  projection turns into navigable graph edges; the source of truth for lineage and impact.
- **Rebuild-equivalence check**: The verification that a freshly rebuilt graph matches the relational truth;
  the correctness gate for any projection.
- **Dependency cycle**: A closed chain of DEPENDS_ON edges; a detectable structural defect that triggers an
  alert naming its members.
- **Knowledge-influence signal**: The per-item influence measure captured in the Knowledge Layer phase,
  projected onto the graph to relate knowledge to the work it shaped.
- **Traceability query**: A workspace-scoped request over the graph (TRACES_TO / DEPENDS_ON, single- or
  multi-hop) returning navigable lineage/impact results.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: The graph can be dropped and rebuilt from scratch with zero manual intervention, and the
  rebuild-equivalence check confirms the rebuilt graph matches the relational truth 100% of the time.
- **SC-002**: Every node and edge in the graph is traceable to a backing event or edge-table row (zero
  unsourced graph elements).
- **SC-003**: TRACES_TO and DEPENDS_ON queries return complete, correct lineage — including transitive
  relationships — for seeded cases, verified against the known relational relationships.
- **SC-004**: A seeded dependency cycle (including a cross-case cycle) is detected and alerted 100% of the
  time, and an acyclic graph produces zero false-positive cycle alerts.
- **SC-005**: Every graph query returns only within-workspace results; a cross-workspace leakage test over
  the graph passes with zero leaks.
- **SC-006**: The knowledge-influence dashboard attributes influence to the correct KnowledgeItems and
  operations for seeded cases, navigable along the graph.
- **SC-007**: A seeded projection-insufficient event is caught and reported by the payload audit rather than
  producing an incomplete graph.
- **SC-008**: Traceability queries stay within p95 ≤ 2 seconds at the benchmark data volume (~50k nodes /
  200k edges), or degrade predictably, with no silent failures.

## Assumptions

- The graph read model is **behavioral, not a storage mandate**: the specification requires a rebuildable,
  workspace-scoped, queryable projection with the properties above; the underlying storage begins on the
  relational engine's recursive-query capability and adopts a dedicated graph store only if query
  performance targets are not met (a planning decision, out of scope for this spec).
- The **relational record remains the single source of truth** (system-of-record invariant established in
  Phase 1). The graph never accepts a write that does not originate from the record, and holds no
  authoritative state.
- The **edge semantics** (trace_link, dependency) and the **event outbox** already exist from Phase 1; this
  phase consumes them and does not redefine them.
- The **knowledge-influence signal** is produced by the Knowledge Layer (Phase 3); this phase visualizes it
  and does not recompute the underlying rubric-delta measure.
- Content and tenancy assumptions are unchanged from earlier phases: single-region cloud, logical
  per-workspace isolation carried through into the projection.
- Latency and volume targets for traceability queries are the standing non-functional targets; the phase
  includes a benchmark to confirm them and to trigger the storage-escalation decision if they are missed.

## Dependencies

- **⚠️ BLOCKING for implementation — Phase 5 (Governance & Review Gates) must be built first.** Phase 7's
  entry criterion is "Phase 5 exit (gates emit full event payloads)." Per the plan's cross-phase dependency
  map, **Phase 5 blocks Phase 7 because gate events complete the projection-sufficient payload set (E7.1)**
  — the projector cannot build a complete, projection-sufficient graph until the governance gates emit
  their full event payloads into the outbox. *Update 2026-07-07*: Phase 5 is now **specified and planned**
  (spec `005-governance-review-gates`; its plan defines the `GateEventPayload` outbox contract this phase
  consumes), so this phase may be planned against that contract — but **implementation remains blocked
  until Phase 5 is built**; until then the projection-sufficiency audit (FR-011) would necessarily report
  gate-event gaps.
- **Phase 6 does NOT block Phase 7.** The plan is explicit that Phase 6 (Catalog Studio) is not required
  for Phase 7 and the two may run in parallel if staffing allows — Phase 7 depends on Phase 5, not Phase 6.
- **Phase 1 (spec `001`) — satisfied.** Provides the append-only event outbox (E1.4) and the generalized
  trace/dependency edge tables (E1.7) that the projector consumes, and the workspace tenancy the projection
  preserves.
- **Phase 3 (spec `003`, authored in this batch) — satisfied.** Provides the knowledge-influence signal
  (E3.4) that the graph analytics dashboard (US4/FR-010) visualizes.
- **Open questions**: none block this phase; the storage-choice decision (recursive queries first, dedicated
  graph store only on benchmark failure) is a planning decision, not a spec clarification.
