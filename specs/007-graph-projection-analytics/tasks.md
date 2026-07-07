# Tasks: Graph Projection + Analytics

**Input**: Design documents from `/specs/007-graph-projection-analytics/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/api.yaml](contracts/api.yaml), [quickstart.md](quickstart.md)

**Tests**: Included — spec SC-001…SC-008 and the quickstart's six IT suites + benchmark require rebuild-equivalence, traceability, cycle-detection, payload-sufficiency, idempotency/mid-rebuild, influence, cross-workspace-leakage, and Phase 1–6 regression suites as acceptance evidence.

**Organization**: Grouped by user story (US1–US4, priority order from spec.md). Builds on the Phase 1–6 modular monolith and **adds the new `projection` module (the 14th)** — the sole writer of the derived graph tables (Principle III). **REQUIRES Phase 5 built** — the projector consumes the Phase 5 `GateEventPayload` outbox contract; until Phase 5 is built the payload-sufficiency audit (FR-011) would necessarily report gate-event gaps. **Phase 6 is NOT required** — this phase is independent of the Catalog Studio; the traceability UI panel lives inside `projection`, not the studio, and the two may run in parallel. Migration lands at **V23** (derived graph tables + `d2os_projector` role). Storage is PostgreSQL adjacency tables + recursive CTEs — **no graph DB** (PD-4; a dedicated graph store is adopted only if the benchmark's p95 target is missed).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: Maps to spec.md user stories US1–US4
- All descriptions include exact file paths per the module layout in [plan.md](plan.md)

## Path Conventions (from plan.md)

New 14th module `projection`: `projection/src/main/java/com/d2os/projection/…`, migration in `projection/src/main/resources/db/migration/`, the self-contained Thymeleaf panel in `projection/src/main/java/com/d2os/projection/ui/` (+ `projection/src/main/resources/templates/`), integration tests in `app/src/test/java/com/d2os/app/`. The module reads other modules' events/schemas read-only and writes only the graph tables (via the `d2os_projector` grant).

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Stand up the new `projection` module and its config — no business logic yet.

- [ ] T001 [P] Create `projection/build.gradle` — depends read-only on `casecore`, `artifacts`, `knowledge`, `governance` types (imports their event/schema types; writes none of them) plus Spring Data JPA/JDBC, Flyway, Thymeleaf + htmx (same conventions as Phase 6 but **no** dependency on the studio module — PD-4/R7)
- [ ] T002 [P] Register the new module in `settings.gradle` (`include 'projection'`) and add it to the aggregate `:app` test/runtime dependencies so its migration and beans load
- [ ] T003 [P] Add Phase 7 config keys to `app/src/main/resources/application.yml`: `d2os.projection.lag-threshold-seconds: 30` (incremental projection lag alarm), `d2os.projection.node-budget: 500` (traceability pagination default — R7), `d2os.projection.rebuild.schedule` (drift-detection equivalence cron — R5), `d2os.projection.cycle-sweep.cadence` (scheduled full-graph cycle sweep — R8), `d2os.projection.gap-alert-threshold: 10` (open `projection_gap` rows before the sufficiency auditor alerts — R6)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The V23 schema, sole-writer grants, and the natural-key/provenance mapper contract that every story depends on. MUST complete before any US phase. All V23 schema lands here; per-story logic follows.

- [ ] T004 Create the derived graph tables in `projection/src/main/resources/db/migration/V23__graph_projection.sql`: `graph_node` and `graph_edge` (each with `generation int`, `workspace_id`, `node_type`/`edge_type`, `natural_key`/`from_node`+`to_node`, `label`/`attributes jsonb`, provenance `source_kind`/`source_ref` NOT NULL — R2/FR-003), natural-key UNIQUE constraints (`(workspace_id, generation, node_type, natural_key)` on nodes; `(workspace_id, generation, edge_type, from_node, to_node, source_ref)` on edges — R3/FR-012), and **both-direction** edge indexes (`(workspace_id, generation, from_node, edge_type)` and `(workspace_id, generation, to_node, edge_type)` — R7); plus `projection_checkpoint` (outbox watermark per consumer/workspace), `projection_state` (`live_generation`, last-equivalence result — R4), and append-only `projection_gap` (R6/FR-011)
- [ ] T005 In the same `V23__graph_projection.sql`: enable RLS + per-workspace policies on all five tables (`graph_node`, `graph_edge`, `projection_checkpoint`, `projection_state`, `projection_gap`) and create the **`d2os_projector`** role with INSERT/UPDATE/DELETE on the graph tables only (SELECT on the read sources), granting `d2os_app` **SELECT-only** on the graph tables (grant-enforced sole writer — research R2, FR-001, Principle III)
- [ ] T006 Implement `NodeEdgeMapper` (deterministic natural-key derivation per node type + provenance `source_kind`/`source_ref` stamping, per the data-model Node/Edge Mapping table: case events→`CASE`/`BELONGS_TO`, artifact/package events→`ARTIFACT_REVISION`/`PACKAGE`/`PRODUCED`, requirement-type artifact revisions→`REQUIREMENT` (artifact subtype, FR-005), `trace_link`→`TRACES_TO`/`DERIVES_FROM`/`SATISFIES`, `dependency`→`DEPENDS_ON`, injection snapshots→`KNOWLEDGE_ITEM_VERSION`/`INJECTED_INTO`, gate events→`GATE`/`GATED_BY`, definition refs→`DEFINITION_VERSION`) in `projection/src/main/java/com/d2os/projection/NodeEdgeMapper.java` (research R3)
- [ ] T007 Bind the projector JPA/JDBC datasource to the `d2os_projector` role (write path) distinct from the app datasource, and add `GraphNode`/`GraphEdge`/`ProjectionCheckpoint`/`ProjectionState`/`ProjectionGap` entities + repositories (generation-filtered reads: `generation = live_generation`) in `projection/src/main/java/com/d2os/projection/`

**Checkpoint**: V23 schema, `d2os_projector` sole-writer grants, and the mapper/provenance contract ready — user story phases can begin.

---

## Phase 3: User Story 1 - The graph is a rebuildable projection, never a source of truth (Priority: P1) 🎯 MVP

**Goal**: Stand up the projector, generational rebuild with verify-then-flip, per-type equivalence verification, and the payload-sufficiency audit — so the graph can be dropped and rebuilt to a provable copy of relational truth.

**Independent Test**: Run cases to Delivered, snapshot relational truth, drop the graph, run rebuild-from-scratch → the rebuilt graph is verifiably equivalent (same nodes, same edges) with zero manual intervention; a seeded divergence fails loudly and is never served.

- [ ] T008 [US1] Implement `Projector` — outbox consumer + edge-table reader driving idempotent `ON CONFLICT DO UPDATE` upserts through `NodeEdgeMapper` into the live generation, tracking the `projection_checkpoint` watermark for incremental runs (replay/duplicate delivery is a no-op — research R3, FR-004/FR-012); source deletion/deprecation events project as node/edge removal or a status attribute in the live generation so neither incremental projection nor a rebuild retains phantom nodes (data-model State & Lifecycle, FR-014) in `projection/src/main/java/com/d2os/projection/Projector.java`
- [ ] T009 [US1] Implement `RebuildJob` — build generation N+1 from zero (full outbox replay + edge-table scan, ignoring checkpoints) → run the equivalence verifier → on PASS atomically flip `projection_state.live_generation` and purge N; on FAIL keep N live, drop N+1, raise a divergence alert (a divergent rebuild is never served) in `projection/src/main/java/com/d2os/projection/RebuildJob.java` (research R4, FR-002/FR-015)
- [ ] T010 [P] [US1] Implement `EquivalenceVerifier` — per-workspace, per-node/edge-type order-independent digests (count + SHA-256 over canonical `natural_key` rows) computed on **both** the candidate generation and the relational truth assembled from source tables (outbox-derived facts, `trace_link`, `dependency`, snapshots); any mismatch reports the differing type + sample keys and fails; also runnable scheduled as live-generation drift detection in `projection/src/main/java/com/d2os/projection/EquivalenceVerifier.java` (research R5, FR-002)
- [ ] T011 [P] [US1] Implement `PayloadSufficiencyAuditor` — validate each consumed outbox event against the projector's declared per-event-type field requirements (gate events against the **Phase 5 `GateEventPayload` contract**); a thin event writes a `projection_gap` row (event id/type + named missing fields), alerts past a threshold, and skips only the unprojectable parts deterministically; a "dry sweep" mode audits historical events (the E7.1 payload audit) in `projection/src/main/java/com/d2os/projection/PayloadSufficiencyAuditor.java` (research R6, FR-011)
- [ ] T012 [US1] Implement `GraphAdminController` — `POST /graph/admin/rebuild` (202 async; 409 if a rebuild is in progress), `GET /graph/admin/status` (live generation, watermark lag, last equivalence result, open-gap count), `GET /graph/admin/gaps` (OPEN/RESOLVED filter) in `projection/src/main/java/com/d2os/projection/api/GraphAdminController.java` (contracts/api.yaml)
- [ ] T013 [US1] Add `RebuildEquivalenceIT` in `app/src/test/java/com/d2os/app/RebuildEquivalenceIT.java`: run cases to Delivered → `POST /graph/admin/rebuild` → per-type digests match relational truth and node/edge sets are identical to pre-rebuild; seeded mapper-fault subtest → rebuild FAILS loudly, prior generation stays live, divergence reported; provenance sweep → every node/edge joins back to its `source_ref` (SC-001, SC-002); deletion subtest → deprecate/delete a source record → incremental projection removes (or status-flags) the element and a fresh rebuild contains no phantom node for it (FR-014)
- [ ] T014 [P] [US1] Add `PayloadSufficiencyIT` in `app/src/test/java/com/d2os/app/PayloadSufficiencyIT.java`: seed a gate event stripped of a required `GateEventPayload` field → `projection_gap` recorded (missing fields named), alert past threshold, no partial gate projected silently; `GET /graph/admin/gaps` lists it and the rebuild result reports open gaps (SC-007)
- [ ] T015 [P] [US1] Add `ProjectionIdempotencyIT` in `app/src/test/java/com/d2os/app/ProjectionIdempotencyIT.java`: replay the same outbox range twice → graph row-set identical (no duplicated nodes/edges); query during a test-paced slow rebuild → reads serve the last consistent generation and `/graph/admin/status` shows `rebuildInProgress=true`, never a half-built result (FR-012, FR-015)

**Checkpoint**: US1 independently testable — the graph is dropped and rebuilt to a verified copy of relational truth; thin events are caught, not silently absorbed.

---

## Phase 4: User Story 2 - Traceability questions are answerable across the whole record (Priority: P1)

**Goal**: Expose workspace-scoped multi-hop TRACES_TO / DEPENDS_ON traceability queries over the graph plus a self-contained UI panel, so lineage/impact is one query, not a research task.

**Independent Test**: Seed a case with known multi-hop trace/dependency links → TRACES_TO and DEPENDS_ON queries from the UI panel return lineage matching the seeded relationships exactly, including transitive hops, scoped to the querying workspace.

- [ ] T016 [US2] Implement `TraceabilityQueryService` — `tracesTo(nodeRef, direction, maxDepth)` and `dependsOn(nodeRef, direction, maxDepth)` as `WITH RECURSIVE` CTEs over `graph_edge` (both-direction indexes), returning paths with per-hop edge provenance and paginating past the configurable node budget (default 500) for predictable degradation; workspace-scoped via RLS + explicit predicate in `projection/src/main/java/com/d2os/projection/query/TraceabilityQueryService.java` (research R7, FR-005/FR-007/FR-013)
- [ ] T017 [US2] Implement `TraceabilityController` — `GET /graph/traceability` (nodeType/naturalKey/relation/direction/maxDepth/pageToken → `LineageResult` with `truncated`+`nextPageToken`; 404 if the starting node is absent from this workspace's live graph) and `GET /graph/nodes/{nodeId}` (attributes, provenance, adjacent edges, links back to owning API resources) in `projection/src/main/java/com/d2os/projection/api/TraceabilityController.java` (contracts/api.yaml, FR-006)
- [ ] T018 [P] [US2] Implement the self-contained traceability panel (search box → lineage tree → node links back to the owning API resources) in `projection/src/main/java/com/d2os/projection/ui/TraceabilityPanelController.java` + `projection/src/main/resources/templates/` (Thymeleaf + htmx, **no studio module on the classpath** — preserves the plan's Phase-6-independence — research R7, FR-006)
- [ ] T019 [US2] Add `TraceabilityQueryIT` in `app/src/test/java/com/d2os/app/TraceabilityQueryIT.java`: seed a known multi-hop lineage (submission → case → requirement → artifacts → package; DERIVES_FROM/SATISFIES/DEPENDS_ON edges incl. a `REQUIREMENT` node hop, FR-005) → `GET /graph/traceability` TRACES_TO and DEPENDS_ON both directions return paths equal to the seeded relationships incl. transitive hops, each hop navigable via `GET /graph/nodes/{id}`; UI smoke asserts the panel renders with no studio module on the classpath (SC-003)
- [ ] T020 [P] [US2] Extend the cross-workspace leakage suite over graph queries in `app/src/test/java/com/d2os/app/LeakageSuiteIT.java`: traceability and node queries from workspace A return zero of workspace B's nodes/edges regardless of relation depth — asserted both API- and SQL-level (RLS + explicit predicate — SC-005, Principle IV)

**Checkpoint**: US2 independently testable — multi-hop lineage/impact answerable from the panel, workspace-scoped, navigable back to source.

---

## Phase 5: User Story 3 - Dependency cycles are detected and alerted (Priority: P2)

**Goal**: Whole-graph, cross-case dependency-cycle detection (incremental on-insert + scheduled full sweep) that alerts through the audited notification/outbox path.

**Independent Test**: Introduce a dependency cycle spanning two cases → detection reports it with all members; an acyclic graph produces zero false positives.

- [ ] T021 [US3] Implement `CycleDetector` — (a) incremental: on each projected `DEPENDS_ON` edge a bounded recursive CTE checks whether the new target reaches its source (cycle ⇒ member-path finding); (b) scheduled full-graph sweep (per workspace, Kahn-style peeling) as the completeness backstop for edges arriving during rebuild; detection spans the **whole workspace graph, cross-case** (not per-case subgraphs); acyclic ⇒ zero findings in `projection/src/main/java/com/d2os/projection/cycle/CycleDetector.java` (research R8, FR-008/FR-009)
- [ ] T022 [US3] Raise cycle alerts by persisting `in_app_notification` rows through the Phase 5 in-app notification store (generic `source_module`/`type` contract — `source_module=projection`, `type=CYCLE_DETECTED`; in-app only in v1, no email/webhook) + an outbox event, and persist findings (member nodes in path order) in `projection/src/main/java/com/d2os/projection/cycle/` (audited alerting path — research R8, Principle V; Phase 5 data-model InAppNotification)
- [ ] T023 [P] [US3] Add `GET /graph/cycles` (detected cycles, each naming its member nodes in path order; empty for an acyclic graph) in `projection/src/main/java/com/d2os/projection/api/CycleController.java` (contracts/api.yaml)
- [ ] T024 [US3] Add `CycleDetectionIT` in `app/src/test/java/com/d2os/app/CycleDetectionIT.java`: project a dependency cycle spanning two cases → incremental detection alerts naming the member path and `GET /graph/cycles` lists it; acyclic control graph → zero findings (no false positives); scheduled-sweep subtest → a cycle introduced mid-rebuild is caught by the full sweep (SC-004)

**Checkpoint**: US3 independently testable — cross-case cycles detected and alerted; acyclic graphs stay silent.

---

## Phase 6: User Story 4 - Knowledge influence is visible on the graph (Priority: P3)

**Goal**: Materialize injection/production edges and serve a knowledge-influence dashboard that attributes recorded influence to KnowledgeItems, operations, and artifacts, navigable along real lineage.

**Independent Test**: For seeded cases with Phase 3 influence samples, the dashboard attributes recorded deltas to the correct item versions, navigable to the exact operations/artifacts touched; a never-injected item reports NOT_YET_MEASURABLE.

- [ ] T025 [US4] Ensure the projector materializes `INJECTED_INTO` edges (KnowledgeItem version → OperationExecution, from Phase 3 injection snapshots) and `PRODUCED` edges (OperationExecution → ArtifactRevision) via `NodeEdgeMapper`/`Projector` in `projection/src/main/java/com/d2os/projection/NodeEdgeMapper.java` (research R9, data-model mapping)
- [ ] T026 [US4] Implement `InfluenceAnalyticsService` — join `INJECTED_INTO`/`PRODUCED` edges with `kpi_sample` (`metric='knowledge_influence'`, **read-only** from observability — never recomputed here); per item render recorded readings + touched operations/artifacts navigable along the edges; items with no samples render `NOT_YET_MEASURABLE` (consistent with Phase 3 FR-018) in `projection/src/main/java/com/d2os/projection/influence/InfluenceAnalyticsService.java` (research R9, FR-010, Principle III)
- [ ] T027 [P] [US4] Add `GET /graph/influence` (`knowledgeKey`/`caseId` filters → per-item `InfluenceEntry`: state MEASURED/NOT_YET_MEASURABLE, readings, touchedOperations, touchedArtifacts) in `projection/src/main/java/com/d2os/projection/api/InfluenceController.java` (contracts/api.yaml)
- [ ] T028 [US4] Add `InfluenceDashboardIT` in `app/src/test/java/com/d2os/app/InfluenceDashboardIT.java`: seeded cases with Phase 3 influence samples → `GET /graph/influence` attributes recorded deltas to the correct item versions, navigable to the exact operations/artifacts touched (INJECTED_INTO/PRODUCED edges); a never-injected item reports NOT_YET_MEASURABLE (SC-006)

**Checkpoint**: US4 independently testable — influence explored along the graph, computing no new metric values.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Prove the latency posture, enforce the sole-writer boundary, and prove nothing regressed.

- [ ] T029 [P] Add the query-latency benchmark (`@Tag("slow")`) in `app/src/test/java/com/d2os/app/TraceabilityBenchmarkIT.java`: seed ~50k nodes / 200k edges, run the traceability query mix → assert p95 ≤ 2 s or predictable pagination; also assert the plan's operational targets — incremental projection lag ≤ 30 s under event load and full rebuild ≤ 10 min at the benchmark volume; a miss records the **PD-4 escalation decision** (dedicated graph store behind the same `TraceabilityQueryService` interface), never silently absorbed (SC-008)
- [ ] T030 [P] Add an ArchUnit rule that **only the projector writes the graph tables** (no other module references a graph-table writer/repository write path; sole-writer boundary — Principle III, research R2) in `test-support/src/main/java/com/d2os/testsupport/ArchitectureRules.java`
- [ ] T031 [P] Re-run the Phase 1–6 IT suites unchanged to confirm zero regression (the projection module adds only read-only consumers + a SELECT-only grant for `d2os_app`)
- [ ] T032 [P] Update the `quickstart.md` success checklist and run the full `:app:test` suite green (Phase 1–6 suites + all six new Phase 7 suites; benchmark tagged slow / nightly)

---

## Dependencies & Execution Order

- **Setup (T001–T003)** → **Foundational (T004–T007)** block everything.
- **⚠️ Phase 5 build dependency**: the projector (T008) and the payload-sufficiency audit (T011/T014) consume the Phase 5 `GateEventPayload` outbox contract — **implementation is blocked until Phase 5 is built**; until then the sufficiency audit would necessarily report gate-event gaps. The phase may be *planned* against the contract, but not *built* before it.
- **Phase 6 is NOT required**: this phase is independent of the Catalog Studio; the traceability panel (T018) lives in `projection` and asserts no studio module on the classpath. Phase 6 and Phase 7 may run in parallel.
- **US1 (T008–T015)** is the MVP and precedes the others (the projector + rebuild/verify/audit foundation the queries and analytics read against).
- **US2 (T016–T020)** depends on US1's projected live generation (the traceability CTEs read `generation = live_generation`).
- **US3 (T021–T024)** depends on the projected `DEPENDS_ON` edges (US1's projector) and reuses the traversal indexes from T004.
- **US4 (T025–T028)** depends on the projector materializing injection/production edges (extends T006/T008) and the Phase 3 `kpi_sample` stream.
- **Polish (T029–T032)** depends on all stories being present.

**Story independence**: US2, US3, US4 each deliver an isolable capability testable on top of US1's projection. Given staffing, US3 (cycles) and US4 (influence) can proceed in parallel after US1 and the relevant projected edges exist; US2 (traceability queries + panel) is the headline P1 value co-equal with US1.

## Parallel Execution Examples

- **Setup**: T001, T002, T003 all `[P]` — build file, settings include, and config keys are distinct files.
- **US1**: T010 (equivalence verifier) and T011 (sufficiency auditor) `[P]` — different files — after T008/T009; then the ITs T013, T014, T015 (T014/T015 `[P]`, distinct suites).
- **US2**: T018 (UI panel) `[P]` with the controller/service work; T020 (leakage extension) `[P]` — distinct test file.
- **US3**: T023 (cycles endpoint) `[P]` alongside detector/alert wiring.
- **US4**: T027 (influence endpoint) `[P]` alongside the analytics service.
- **Polish**: T029, T030, T031, T032 all `[P]` — independent test/config files.

## Implementation Strategy

**MVP = US1 only** (Phase 1–3): the rebuildable, provenance-carrying, equivalence-verified graph projection with the payload-sufficiency audit. This is the foundational invariant (Principle III) that makes every query and dashboard on top of the graph trustworthy — demonstrable value on its own before any query surface ships.

**Incremental delivery**: US1 (trustworthy rebuildable projection) → US2 (multi-hop traceability queries + panel, the headline user-facing value) → then P2 US3 (cycle detection guardrail) and P3 US4 (influence dashboard) → Polish (latency benchmark, sole-writer ArchUnit, Phase 1–6 regression). Each phase is independently testable and leaves the system shippable.

---

**Total: 32 tasks** — Setup 3 · Foundational 4 · US1 8 · US2 5 · US3 4 · US4 4 · Polish 4.
