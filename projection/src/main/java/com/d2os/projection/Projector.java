package com.d2os.projection;

import com.d2os.observability.JobMetrics;
import com.d2os.projection.cycle.CycleDetector;
import com.d2os.tenancy.WorkspaceContext;
import com.d2os.tenancy.security.WorkspaceRlsBinder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * T008 — the continuous outbox consumer + edge-table reader (research R3/R4, FR-004/012/014).
 * Sweeps every active workspace on a fixed cadence ({@code d2os.projection.consumer-interval-ms}),
 * mapping source facts through {@link NodeEdgeMapper} and upserting them into the LIVE generation
 * via {@link GraphWriteRepository} (the {@code d2os_projector}-bound write path). Every write is an
 * idempotent upsert keyed on the mapper's deterministic natural keys, so replaying the same event
 * or re-reading the same source row twice is a no-op by construction (FR-012).
 *
 * <h2>Two read strategies, matching what actually gets published</h2>
 * Verified by grepping every {@code auditWriter.record(} call site in the repo: {@code
 * case_instance} lifecycle transitions and {@code gate_instance} events ARE published to {@code
 * event_outbox} (consumed incrementally, watermark-tracked below); artifact-revision creation and
 * {@code trace_link} rows are NOT — no outbox event exists anywhere for either (confirmed; the only
 * artifact-related outbox write is {@code ARTIFACT_WRITE_REFUSED}, a refusal, not a creation). So
 * those two are read directly off their source tables every sweep (a full per-workspace scan, not
 * watermark-tracked — see "Full-rescan tradeoff" below).
 *
 * <h2>Phase 3 node/edge type scope (deliberately narrower than {@link NodeEdgeMapper}'s full
 * repertoire)</h2>
 * Wired here: {@code CASE}/{@code FEATURE}/{@code BELONGS_TO} (case events), {@code
 * ARTIFACT_REVISION}/{@code REQUIREMENT}/{@code OPERATION_EXECUTION}/{@code PRODUCED} (artifact_revision
 * scan), {@code GATE}/{@code GATED_BY} (gate events), {@code TRACES_TO}/{@code DERIVES_FROM}/{@code
 * SATISFIES} (trace_link scan), and — as of Phase 5 US3 (T021, {@link com.d2os.projection.cycle.CycleDetector})
 * — {@code DEPENDS_ON} (dependency scan). Deliberately DEFERRED, not wired in this phase: {@code
 * PACKAGE}/{@code execution_package} (tasks.md T009's own "minimum" scope for the rebuild-equivalence
 * pair names only {@code case_instance}, {@code artifact_revision}, {@code gate_instance}, {@code
 * trace_link}); {@code KNOWLEDGE_ITEM_VERSION}/{@code INJECTED_INTO} (explicitly assigned
 * to Phase 6/US4 by tasks.md T025 — "ensure the projector materializes INJECTED_INTO edges..."
 * implies it is NOT yet wired, until this same phase wires it below); {@code DEFINITION_VERSION}
 * (no task in Phase 3-6 names a concrete source table for it).
 *
 * <h3>{@code knowledge_injection_snapshot}/{@code INJECTED_INTO} — wired (Phase 6 US4, T025)</h3>
 * Full-table rescan (no outbox event exists for injection-snapshot creation), same convention as
 * {@link #scanTraceLinks}/{@link #scanDependencies}. Read via raw JDBC against the table directly —
 * this module has no Gradle dependency on {@code persona} (the table's owning module); the same
 * posture already used for every other cross-module source table this class reads.
 *
 * <h3>{@code dependency}/{@code DEPENDS_ON} — wired, still writer-less (Phase 5 US3, T021)</h3>
 * {@link NodeEdgeMapper#mapDependency} was implemented in Phase 2 as dead code because no
 * application path wrote {@code dependency} rows (verified then, still true now — this task did
 * not add one; that remains a separate, out-of-scope gap). {@link com.d2os.projection.cycle.CycleDetector}
 * (T021) needs {@code DEPENDS_ON} edges to exist to have anything to check, so this phase wires the
 * scan on anyway: whenever something DOES write a {@code dependency} row (today, only integration
 * tests via direct SQL — see {@code CycleDetectionIT}), this sweep picks it up like any other
 * source table, exactly as {@link #scanTraceLinks} already does for {@code trace_link}. {@link
 * RebuildJob} and {@link EquivalenceVerifier} are wired identically in this same phase so a
 * rebuild does not silently drop the type and equivalence does not spuriously flag it (see this
 * class's own reasoning below on keeping the three in lockstep).
 * source query for it). This keeps the incremental Projector, {@link RebuildJob}, and {@link
 * EquivalenceVerifier} covering EXACTLY the same type set — wiring a type into the incremental
 * projector that the rebuild/equivalence pair does not also check would make every rebuild silently
 * drop that type's nodes (RebuildJob purges the old generation) and would make {@link
 * EquivalenceVerifier} either ignore it or spuriously flag it — internal consistency over breadth.
 *
 * <h2>The "self-id as sourceRef" provenance convention</h2>
 * {@link NodeEdgeMapper}'s {@code *Fact} records all take a {@code sourceEventId}/{@code linkId}
 * parameter that becomes the node/edge's {@code source_ref}. For facts assembled from an outbox
 * event (case/gate events) this class passes the ENTITY's own id (the case id / the gate id from
 * the payload) rather than the literal {@code event_outbox.id} — deliberately, so the identical
 * fact produces the identical {@code source_ref} whether it was assembled from an outbox event
 * (this class, generation 0+) or from a direct table read ({@link RebuildJob}, any later
 * generation). {@link EquivalenceVerifier}'s independently-computed relational truth follows the
 * same convention. Without this, the SAME logical edge would carry a different {@code source_ref}
 * depending on which of the two code paths produced it, and no rebuilt generation could ever match
 * an incrementally-projected one under FR-002's "graph == relational truth" equivalence check. Note
 * this only changes what counts as {@code source_ref} — FR-003's "no unsourced element" invariant is
 * unaffected: {@code source_ref} still always resolves to a real, dereferenceable row.
 *
 * <h2>Known inherited provenance-label caveat</h2>
 * {@link NodeEdgeMapper#mapArtifactRevision}/{@code mapPackage} hardcode {@code source_kind =
 * OUTBOX_EVENT} even though — per the above — no outbox event for artifact-revision creation
 * exists; this class's {@code artifact_revision} table scan still calls that method as-is (T006's
 * mapper is Phase 2, already committed, out of this phase's scope to modify). The {@code
 * source_kind} label on those rows is therefore slightly inaccurate (says "outbox event", is really
 * "read directly off the table"); {@code source_ref} itself is still a real, valid, dereferenceable
 * id, so FR-003 holds — this is a labeling quirk, not a functional defect.
 *
 * <h2>Full-rescan tradeoff (artifact_revision / trace_link)</h2>
 * Since neither table is watermark-trackable via a monotonic outbox-style cursor (no {@code seq}
 * column exists on them, and V28 only added one to {@code event_outbox}), this class re-reads BOTH
 * tables in full, per workspace, every sweep, rather than maintaining a second per-table cursor.
 * Because every write is an idempotent upsert (R3), this is always correct — merely not optimized
 * for very large tables. Scale/perf tuning (a created_at-based incremental cursor, or a dedicated
 * `seq` column added the same way V28 did for {@code event_outbox}) is left to Phase 7's polish
 * pass (T029, the latency benchmark) if the benchmark shows it is actually needed at the ~tens of
 * thousands of nodes/workspace scale research R1 targets.
 *
 * <h2>Bootstrap (generation 0)</h2>
 * {@code projection_state} has no seed row per workspace (V28 does not insert one). The FIRST sweep
 * that encounters a workspace with no {@code projection_state} row bootstraps it to {@code
 * live_generation = 0} and begins writing directly into that generation — this class is generation
 * 0's sole author; {@link RebuildJob} only ever builds N+1 and flips an ALREADY-live generation
 * (research R4's state machine), so something has to create generation 0 in the first place.
 */
@Component
public class Projector {

    private static final Logger log = LoggerFactory.getLogger(Projector.class);

    private static final String CONSUMER = "graph-projector";

    private static final Set<String> CASE_STATUS_EVENT_TYPES =
            Set.of("Planned", "Running", "Suspended", "Escalated", "Delivered", "Cancelled");

    private final JdbcTemplate jdbcTemplate;
    private final WorkspaceRlsBinder workspaceRlsBinder;
    private final PlatformTransactionManager transactionManager;
    private final NodeEdgeMapper mapper;
    private final GraphWriteRepository graphWriteRepository;
    private final ProjectorRlsBinder projectorRlsBinder;
    private final PlatformTransactionManager projectorTransactionManager;
    private final PayloadSufficiencyAuditor sufficiencyAuditor;
    private final ObjectMapper objectMapper;
    private final CycleDetector cycleDetector;
    private final JobMetrics jobMetrics;
    private final ProjectionMetrics projectionMetrics;

    public Projector(JdbcTemplate jdbcTemplate,
                     WorkspaceRlsBinder workspaceRlsBinder,
                     PlatformTransactionManager transactionManager,
                     NodeEdgeMapper mapper,
                     GraphWriteRepository graphWriteRepository,
                     ProjectorRlsBinder projectorRlsBinder,
                     @Qualifier("projectorTransactionManager") PlatformTransactionManager projectorTransactionManager,
                     PayloadSufficiencyAuditor sufficiencyAuditor,
                     ObjectMapper objectMapper,
                     CycleDetector cycleDetector,
                     JobMetrics jobMetrics,
                     ProjectionMetrics projectionMetrics) {
        this.jdbcTemplate = jdbcTemplate;
        this.workspaceRlsBinder = workspaceRlsBinder;
        this.transactionManager = transactionManager;
        this.mapper = mapper;
        this.graphWriteRepository = graphWriteRepository;
        this.projectorRlsBinder = projectorRlsBinder;
        this.projectorTransactionManager = projectorTransactionManager;
        this.sufficiencyAuditor = sufficiencyAuditor;
        this.objectMapper = objectMapper;
        this.cycleDetector = cycleDetector;
        this.jobMetrics = jobMetrics;
        this.projectionMetrics = projectionMetrics;
    }

    @Scheduled(fixedDelayString = "${d2os.projection.consumer-interval-ms:5000}",
               initialDelayString = "${d2os.projection.consumer-interval-ms:5000}")
    @SchedulerLock(name = "projector-sweep", lockAtMostFor = "PT2M")
    public void sweep() {
        jobMetrics.time("projector-sweep", () -> {
            List<UUID> workspaceIds = jdbcTemplate.queryForList("SELECT id FROM list_active_workspace_ids()", UUID.class);
            double maxLagSeconds = 0.0;
            long totalOpenGaps = 0L;
            for (UUID workspaceId : workspaceIds) {
                try {
                    processWorkspace(workspaceId);
                } catch (Exception e) {
                    // One bad workspace must never stop the sweep — same posture as ReconciliationJob.
                    log.warn("projector sweep failed for workspace {}: {}", workspaceId, e.toString());
                }
                try {
                    WorkspaceObservation obs = observe(workspaceId);
                    maxLagSeconds = Math.max(maxLagSeconds, obs.lagSeconds());
                    totalOpenGaps += obs.openGaps();
                } catch (Exception e) {
                    // Metrics are best-effort — a failed observation never affects projection correctness.
                    log.debug("projection metrics observation failed for workspace {}: {}", workspaceId, e.toString());
                }
            }
            // T020: publish the sweep's aggregate to the gauges (d2os.projection.lag.seconds / .gap.open).
            projectionMetrics.publishSweepObservation(maxLagSeconds, totalOpenGaps);
        });
    }

    /**
     * T020 — computes this workspace's projection lag and OPEN-gap count for {@link ProjectionMetrics},
     * inside a short RLS-bound read transaction (both source tables are RLS-scoped). These are exactly
     * the values {@code GraphAdminController#status} exposes per workspace: lag = seconds since the
     * oldest still-unconsumed outbox row, open gaps = {@code projection_gap} rows with {@code status='OPEN'}.
     */
    private WorkspaceObservation observe(UUID workspaceId) {
        WorkspaceContext.set(workspaceId);
        try {
            TransactionTemplate readTx = requiresNew(transactionManager);
            return readTx.execute(status -> {
                workspaceRlsBinder.bindCurrentTransaction(workspaceId);
                long watermark = currentWatermark(workspaceId);
                Double lag = jdbcTemplate.queryForObject(
                        "SELECT COALESCE(EXTRACT(EPOCH FROM (now() - MIN(created_at))), 0) FROM event_outbox "
                                + "WHERE workspace_id = ? AND seq > ?",
                        Double.class, workspaceId, watermark);
                Long gaps = jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM projection_gap WHERE workspace_id = ? AND status = 'OPEN'",
                        Long.class, workspaceId);
                return new WorkspaceObservation(lag == null ? 0.0 : lag, gaps == null ? 0L : gaps);
            });
        } finally {
            WorkspaceContext.clear();
        }
    }

    private record WorkspaceObservation(double lagSeconds, long openGaps) {}

    /** Package-visible for {@link RebuildEquivalenceIT}-style callers that want to drive one workspace directly. */
    void processWorkspace(UUID workspaceId) {
        int generation = resolveLiveGeneration(workspaceId);

        ReadBatch batch;
        WorkspaceContext.set(workspaceId);
        try {
            TransactionTemplate readTx = requiresNew(transactionManager);
            batch = readTx.execute(status -> {
                workspaceRlsBinder.bindCurrentTransaction(workspaceId);
                long watermark = currentWatermark(workspaceId);
                return readBatch(workspaceId, generation, watermark);
            });
        } finally {
            WorkspaceContext.clear();
        }

        if (batch == null || batch.isNoOp()) {
            return;
        }

        TransactionTemplate writeTx = requiresNew(projectorTransactionManager);
        writeTx.executeWithoutResult(status -> {
            projectorRlsBinder.bindCurrentTransaction(workspaceId);
            for (GraphNode node : batch.nodes()) graphWriteRepository.upsertNode(node);
            for (GraphEdge edge : batch.edges()) graphWriteRepository.upsertEdge(edge);
            for (ProjectionGap gap : batch.gaps()) graphWriteRepository.insertGap(gap);
            graphWriteRepository.upsertCheckpoint(CONSUMER, workspaceId, batch.newWatermark());
        });

        if (!batch.gaps().isEmpty()) {
            sufficiencyAuditor.alertIfPastThreshold(workspaceId);
        }

        List<GraphEdge> newDependsOnEdges = batch.edges().stream()
                .filter(e -> NodeEdgeMapper.EDGE_DEPENDS_ON.equals(e.getEdgeType()))
                .toList();
        if (!newDependsOnEdges.isEmpty()) {
            cycleDetector.checkIncremental(workspaceId, generation, newDependsOnEdges);
        }
    }

    // ---- generation bootstrap ----------------------------------------------------------------------

    private int resolveLiveGeneration(UUID workspaceId) {
        WorkspaceContext.set(workspaceId);
        Integer existing;
        try {
            TransactionTemplate readTx = requiresNew(transactionManager);
            existing = readTx.execute(status -> {
                workspaceRlsBinder.bindCurrentTransaction(workspaceId);
                return currentLiveGeneration(workspaceId);
            });
        } finally {
            WorkspaceContext.clear();
        }
        if (existing != null) {
            return existing;
        }

        // Bootstrap: first-ever projection run for this workspace (see class javadoc).
        TransactionTemplate writeTx = requiresNew(projectorTransactionManager);
        writeTx.executeWithoutResult(status -> {
            projectorRlsBinder.bindCurrentTransaction(workspaceId);
            graphWriteRepository.upsertState(workspaceId, 0, null, null);
        });
        log.info("bootstrapped projection_state for workspace {} at generation 0", workspaceId);
        return 0;
    }

    private Integer currentLiveGeneration(UUID workspaceId) {
        List<Integer> rows = jdbcTemplate.queryForList(
                "SELECT live_generation FROM projection_state WHERE workspace_id = ?", Integer.class, workspaceId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private long currentWatermark(UUID workspaceId) {
        List<Long> rows = jdbcTemplate.queryForList(
                "SELECT outbox_watermark FROM projection_checkpoint WHERE consumer = ? AND workspace_id = ?",
                Long.class, CONSUMER, workspaceId);
        return rows.isEmpty() ? 0L : rows.get(0);
    }

    // ---- read side (app/d2os_app datasource, RLS-bound per workspace) -----------------------------

    private record ReadBatch(List<GraphNode> nodes, List<GraphEdge> edges, List<ProjectionGap> gaps,
                             long watermarkBefore, long newWatermark) {
        boolean isNoOp() {
            return nodes.isEmpty() && edges.isEmpty() && gaps.isEmpty() && newWatermark == watermarkBefore;
        }
    }

    private ReadBatch readBatch(UUID workspaceId, int generation, long watermark) {
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        List<ProjectionGap> gaps = new ArrayList<>();
        long newWatermark = watermark;

        List<Map<String, Object>> events = jdbcTemplate.queryForList(
                "SELECT seq, id, aggregate_type, aggregate_id, event_type, payload, created_at FROM event_outbox "
                        + "WHERE workspace_id = ? AND seq > ? ORDER BY seq",
                workspaceId, watermark);

        for (Map<String, Object> row : events) {
            long seq = ((Number) row.get("seq")).longValue();
            newWatermark = Math.max(newWatermark, seq);
            String aggregateType = (String) row.get("aggregate_type");
            String eventType = (String) row.get("event_type");
            UUID aggregateId = (UUID) row.get("aggregate_id");
            UUID eventId = (UUID) row.get("id");
            OffsetDateTime occurredAt = toOffsetDateTime(row.get("created_at"));

            if ("case_instance".equals(aggregateType) && CASE_STATUS_EVENT_TYPES.contains(eventType)) {
                mapCaseEvent(workspaceId, generation, aggregateId, eventType, occurredAt, nodes, edges);
            } else if ("gate_instance".equals(aggregateType)) {
                String payloadJson = String.valueOf(row.get("payload"));
                PayloadSufficiencyAuditor.AuditResult audit =
                        sufficiencyAuditor.auditGateEvent(aggregateType, eventType, payloadJson);
                if (audit.declared()) {
                    if (!audit.missingFields().isEmpty()) {
                        gaps.add(new ProjectionGap(UUID.randomUUID(), workspaceId, eventId, eventType,
                                audit.missingFields().toArray(new String[0]), OffsetDateTime.now(),
                                ProjectionGap.Status.OPEN));
                    }
                    if (audit.projectable()) {
                        mapGateEvent(workspaceId, generation, payloadJson, eventType, occurredAt, nodes, edges);
                    }
                }
                // else: not a declared/projected gate event type (e.g. GATE_APPROVE/REJECT/
                // REQUEST_CHANGES — GateService's own thin decision-verb audit row, superseded by
                // the GATE_DECIDED event GateEventPublisher emits in the same transaction) — skip.
            }
            // else: not a recognized aggregate_type in Phase 3's scope (definition/knowledge/etc
            // events) — the watermark still advances past it (idempotent replay is a no-op either
            // way; there is nothing to "catch up on" for an event type this projector never maps).
        }

        scanTraceLinks(workspaceId, generation, nodes, edges);
        scanArtifactRevisions(workspaceId, generation, nodes, edges);
        scanDependencies(workspaceId, generation, nodes, edges);
        scanInjectionSnapshots(workspaceId, generation, nodes, edges);

        return new ReadBatch(nodes, edges, gaps, watermark, newWatermark);
    }

    private void mapCaseEvent(UUID workspaceId, int generation, UUID caseId, String status, OffsetDateTime occurredAt,
                              List<GraphNode> nodes, List<GraphEdge> edges) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT feature_id FROM case_instance WHERE id = ?", caseId);
        if (rows.isEmpty()) {
            return; // defensive: the case row must exist by the time its own lifecycle event is read
        }
        UUID featureId = (UUID) rows.get(0).get("feature_id");
        NodeEdgeMapper.CaseLifecycleFact fact = new NodeEdgeMapper.CaseLifecycleFact(
                workspaceId, caseId, featureId, status, caseId, occurredAt);
        NodeEdgeMapper.MappingResult result = mapper.mapCaseLifecycle(fact, generation);
        nodes.addAll(result.nodes());
        edges.addAll(result.edges());
    }

    @SuppressWarnings("unchecked")
    private void mapGateEvent(UUID workspaceId, int generation, String payloadJson, String eventType,
                              OffsetDateTime occurredAt, List<GraphNode> nodes, List<GraphEdge> edges) {
        Map<String, Object> p;
        try {
            p = objectMapper.readValue(payloadJson, Map.class);
        } catch (Exception e) {
            return; // structurally required fields already validated by the auditor; malformed JSON is defensive-only
        }
        UUID gateId = uuid(p.get("gateId"));
        if (gateId == null) return;
        String gateType = (String) p.get("gateType");
        String gateDefinitionKey = (String) p.get("gateDefinitionKey");
        Object versionRaw = p.get("gateDefinitionVersion");
        int gateDefinitionVersion = versionRaw instanceof Number n ? n.intValue() : 0;
        UUID caseInstanceId = uuid(p.get("caseInstanceId"));
        UUID subjectArtifactRevisionId = uuid(p.get("subjectArtifactRevisionId"));
        String decisionVerb = (String) p.get("decisionVerb");
        String deciderId = (String) p.get("deciderId");

        NodeEdgeMapper.GateEventFact fact = new NodeEdgeMapper.GateEventFact(workspaceId, gateId, gateType,
                gateDefinitionKey, gateDefinitionVersion, caseInstanceId, subjectArtifactRevisionId, eventType,
                decisionVerb, deciderId, gateId, occurredAt);
        NodeEdgeMapper.MappingResult result = mapper.mapGateEvent(fact, generation);
        nodes.addAll(result.nodes());
        edges.addAll(result.edges());
    }

    private void scanTraceLinks(UUID workspaceId, int generation, List<GraphNode> nodes, List<GraphEdge> edges) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, from_type, from_id, to_type, to_id, link_type, created_at "
                        + "FROM trace_link WHERE workspace_id = ?",
                workspaceId);
        for (Map<String, Object> row : rows) {
            NodeEdgeMapper.TraceLinkFact fact = new NodeEdgeMapper.TraceLinkFact(workspaceId,
                    (UUID) row.get("id"), (String) row.get("from_type"), (UUID) row.get("from_id"),
                    (String) row.get("to_type"), (UUID) row.get("to_id"), (String) row.get("link_type"),
                    toOffsetDateTime(row.get("created_at")));
            NodeEdgeMapper.MappingResult result = mapper.mapTraceLink(fact, generation);
            nodes.addAll(result.nodes());
            edges.addAll(result.edges());
        }
    }

    /** Phase 5 US3 (T021) — see class javadoc's "dependency/DEPENDS_ON — wired, still writer-less" section. */
    private void scanDependencies(UUID workspaceId, int generation, List<GraphNode> nodes, List<GraphEdge> edges) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, from_type, from_id, to_type, to_id, dep_type, created_at "
                        + "FROM dependency WHERE workspace_id = ?",
                workspaceId);
        for (Map<String, Object> row : rows) {
            NodeEdgeMapper.DependencyFact fact = new NodeEdgeMapper.DependencyFact(workspaceId,
                    (UUID) row.get("id"), (String) row.get("from_type"), (UUID) row.get("from_id"),
                    (String) row.get("to_type"), (UUID) row.get("to_id"), (String) row.get("dep_type"),
                    toOffsetDateTime(row.get("created_at")));
            NodeEdgeMapper.MappingResult result = mapper.mapDependency(fact, generation);
            nodes.addAll(result.nodes());
            edges.addAll(result.edges());
        }
    }

    /** Phase 6 US4 (T025) — see class javadoc's "knowledge_injection_snapshot/INJECTED_INTO — wired" section. */
    private void scanInjectionSnapshots(UUID workspaceId, int generation, List<GraphNode> nodes, List<GraphEdge> edges) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, operation_execution_id, knowledge_item_id, knowledge_item_key, "
                        + "       knowledge_item_version, position, created_at "
                        + "FROM knowledge_injection_snapshot WHERE workspace_id = ?",
                workspaceId);
        for (Map<String, Object> row : rows) {
            NodeEdgeMapper.InjectionSnapshotFact fact = new NodeEdgeMapper.InjectionSnapshotFact(workspaceId,
                    (UUID) row.get("id"), (UUID) row.get("operation_execution_id"),
                    (UUID) row.get("knowledge_item_id"), (String) row.get("knowledge_item_key"),
                    ((Number) row.get("knowledge_item_version")).intValue(),
                    ((Number) row.get("position")).intValue(), toOffsetDateTime(row.get("created_at")));
            NodeEdgeMapper.MappingResult result = mapper.mapInjectionSnapshot(fact, generation);
            nodes.addAll(result.nodes());
            edges.addAll(result.edges());
        }
    }

    private void scanArtifactRevisions(UUID workspaceId, int generation, List<GraphNode> nodes, List<GraphEdge> edges) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT ar.id AS revision_id, ar.artifact_id, ar.revision_no, a.artifact_type, "
                        + "       ar.produced_by_operation_execution_id, ar.source_template_id, "
                        + "       ar.template_version, ar.created_at "
                        + "FROM artifact_revision ar JOIN artifact a ON a.id = ar.artifact_id "
                        + "WHERE ar.workspace_id = ?",
                workspaceId);
        for (Map<String, Object> row : rows) {
            UUID revisionId = (UUID) row.get("revision_id");
            NodeEdgeMapper.ArtifactRevisionFact fact = new NodeEdgeMapper.ArtifactRevisionFact(workspaceId,
                    (UUID) row.get("artifact_id"), revisionId, ((Number) row.get("revision_no")).intValue(),
                    (String) row.get("artifact_type"), (UUID) row.get("produced_by_operation_execution_id"),
                    (UUID) row.get("source_template_id"), (String) row.get("template_version"),
                    revisionId, toOffsetDateTime(row.get("created_at")));
            NodeEdgeMapper.MappingResult result = mapper.mapArtifactRevision(fact, generation);
            nodes.addAll(result.nodes());
            edges.addAll(result.edges());
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private static UUID uuid(Object value) {
        if (value == null) return null;
        if (value instanceof UUID u) return u;
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static OffsetDateTime toOffsetDateTime(Object value) {
        if (value instanceof OffsetDateTime odt) return odt;
        if (value instanceof Timestamp ts) return ts.toInstant().atOffset(ZoneOffset.UTC);
        return OffsetDateTime.now();
    }

    private TransactionTemplate requiresNew(PlatformTransactionManager manager) {
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        def.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new TransactionTemplate(manager, def);
    }
}
