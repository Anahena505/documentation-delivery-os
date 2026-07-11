/**
 * Graph Projection + Analytics (Phase 7, the 14th bounded-context module — plan.md, research
 * R1-R9).
 *
 * <p>This module owns a derived, rebuildable graph read model ({@code graph_node}/{@code
 * graph_edge}, V28 migration) projected from the transactional outbox and the polymorphic edge
 * tables ({@code trace_link}, {@code dependency}) owned by other modules. It reads other modules'
 * events/schemas read-only and is the sole writer of the graph tables — enforced structurally by
 * module boundary (nothing depends on this module) and by DB grant (the {@code d2os_projector}
 * role, see {@link com.d2os.projection.config.ProjectorDataSourceConfig}).
 *
 * <p>Phase 1/2 scope landed here (T001-T007): module scaffolding, config, the V28 schema, the
 * pure {@link com.d2os.projection.NodeEdgeMapper} mapping contract, and the JPA/JDBC entity +
 * repository layer. The projector worker, rebuild job, equivalence verifier, sufficiency audit,
 * traceability queries, cycle detection, and influence dashboard (T008+) are later phases.
 */
package com.d2os.projection;
