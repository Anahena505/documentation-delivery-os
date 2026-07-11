package com.d2os.projection;

import com.d2os.observability.JobMetrics;
import com.d2os.tenancy.WorkspaceContext;
import com.d2os.tenancy.security.WorkspaceRlsBinder;
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

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * T009 — generation N+1 build (from source tables directly, never by replaying {@code
 * event_outbox}) -> {@link EquivalenceVerifier} check -> atomic flip on PASS / drop-and-alert on
 * FAIL (research R4/R5, FR-002/015). Scheduled per {@code d2os.projection.rebuild.schedule}
 * (weekly drift-detection default); also triggerable on demand via {@code POST
 * /graph/admin/rebuild} ({@link GraphAdminController}).
 *
 * <h2>Why source tables, not outbox replay (deviates from R4's literal wording, per this task's
 * explicit instruction)</h2>
 * research R4 literally says "full outbox replay + edge-table scan". This class instead queries
 * {@code case_instance}/{@code artifact_revision}/{@code gate_instance}/{@code trace_link}
 * directly, per this phase's explicit task instruction and R5's own rationale: equivalence-checking
 * must compare the candidate against relational truth queried directly from source tables, not
 * against a second run of the SAME event-derivation logic the incremental {@link Projector} already
 * used — replaying the outbox a second time would just prove {@link NodeEdgeMapper} agrees with
 * itself on the same inputs, not that the graph matches the database.
 *
 * <h2>Scope — matches {@link Projector}/{@link EquivalenceVerifier} exactly</h2>
 * See {@link Projector}'s javadoc for the full reasoning; the short version: {@code CASE}/{@code
 * FEATURE}/{@code BELONGS_TO} (every {@code case_instance} row), {@code ARTIFACT_REVISION}/{@code
 * REQUIREMENT}/{@code OPERATION_EXECUTION}/{@code PRODUCED} (every {@code artifact_revision} row),
 * {@code GATE}/{@code GATED_BY} (every {@code gate_instance} row), {@code TRACES_TO}/{@code
 * DERIVES_FROM}/{@code SATISFIES} (every {@code trace_link} row), and — as of Phase 5 US3 (T021) —
 * {@code DEPENDS_ON} (every {@code dependency} row; still writer-less at the application layer, see
 * {@link Projector}'s javadoc, but wired here in lockstep with the incremental projector so a
 * rebuild does not silently drop it), and {@code KNOWLEDGE_ITEM_VERSION}/{@code INJECTED_INTO}
 * (every {@code knowledge_injection_snapshot} row, Phase 6/US4 T025, same lockstep reasoning).
 * {@code PACKAGE}/{@code execution_package} and {@code DEFINITION_VERSION} are deliberately NOT
 * rebuilt in this phase — a generation this class purges-and-replaces never had those types in it
 * to begin with (the incremental {@link Projector} does not project them either), so nothing is
 * lost across a flip.
 *
 * <h2>Provenance convention</h2>
 * Every {@code *Fact} built here uses the source row's own id as {@code sourceEventId}/{@code
 * linkId} (the "self-id" convention — see {@link Projector}'s javadoc) so a rebuilt generation's
 * edge identities exactly match what {@link EquivalenceVerifier}'s independently-computed truth
 * expects, and what an incrementally-projected generation would also have produced for the same
 * fact.
 *
 * <h2>Known accepted race (documented, not hardened in Phase 3)</h2>
 * There is no cross-job lock between {@link Projector}'s per-workspace sweep and this class's
 * flip-and-purge. If a {@link Projector} sweep resolves {@code generation = N} and then this class
 * flips {@code live_generation} to {@code N+1} and purges {@code N} before that sweep's write
 * transaction commits, the sweep's rows land in a generation number that no longer exists as far as
 * live reads are concerned (harmless — dead rows, not visible via any {@code generation =
 * live_generation} read, cleaned up by the NEXT rebuild's purge of that number whenever it recurs).
 * Not a correctness bug for what gets SERVED, just wasted writes in a narrow window; real
 * cross-job coordination (e.g. an advisory lock) is left for later hardening if this proves to
 * matter at production scale.
 */
@Component
public class RebuildJob {

    private static final Logger log = LoggerFactory.getLogger(RebuildJob.class);

    private final JdbcTemplate jdbcTemplate;
    private final WorkspaceRlsBinder workspaceRlsBinder;
    private final PlatformTransactionManager transactionManager;
    private final NodeEdgeMapper mapper;
    private final GraphWriteRepository graphWriteRepository;
    private final ProjectorRlsBinder projectorRlsBinder;
    private final PlatformTransactionManager projectorTransactionManager;
    private final EquivalenceVerifier equivalenceVerifier;
    private final JobMetrics jobMetrics;
    private final ProjectionMetrics projectionMetrics;

    private final Set<UUID> inProgress = ConcurrentHashMap.newKeySet();
    private final ExecutorService onDemandExecutor = Executors.newFixedThreadPool(2,
            r -> new Thread(r, "graph-rebuild-on-demand"));

    public RebuildJob(JdbcTemplate jdbcTemplate,
                      WorkspaceRlsBinder workspaceRlsBinder,
                      PlatformTransactionManager transactionManager,
                      NodeEdgeMapper mapper,
                      GraphWriteRepository graphWriteRepository,
                      ProjectorRlsBinder projectorRlsBinder,
                      @Qualifier("projectorTransactionManager") PlatformTransactionManager projectorTransactionManager,
                      EquivalenceVerifier equivalenceVerifier,
                      JobMetrics jobMetrics,
                      ProjectionMetrics projectionMetrics) {
        this.jdbcTemplate = jdbcTemplate;
        this.workspaceRlsBinder = workspaceRlsBinder;
        this.transactionManager = transactionManager;
        this.mapper = mapper;
        this.graphWriteRepository = graphWriteRepository;
        this.projectorRlsBinder = projectorRlsBinder;
        this.projectorTransactionManager = projectorTransactionManager;
        this.equivalenceVerifier = equivalenceVerifier;
        this.jobMetrics = jobMetrics;
        this.projectionMetrics = projectionMetrics;
    }

    public boolean isInProgress(UUID workspaceId) {
        return inProgress.contains(workspaceId);
    }

    /** {@code POST /graph/admin/rebuild} — returns false (409) if a rebuild is already running for this workspace. */
    public boolean triggerAsync(UUID workspaceId) {
        if (!inProgress.add(workspaceId)) {
            return false;
        }
        onDemandExecutor.submit(() -> {
            try {
                rebuild(workspaceId);
            } catch (Exception e) {
                log.error("on-demand rebuild failed for workspace {}: {}", workspaceId, e.toString(), e);
            } finally {
                inProgress.remove(workspaceId);
            }
        });
        return true;
    }

    @Scheduled(cron = "${d2os.projection.rebuild.schedule:0 0 3 * * SUN}")
    @SchedulerLock(name = "graph-rebuild", lockAtMostFor = "PT15M")
    public void scheduledSweep() {
        jobMetrics.time("graph-rebuild", () -> {
            List<UUID> workspaceIds = jdbcTemplate.queryForList("SELECT id FROM list_active_workspace_ids()", UUID.class);
            for (UUID workspaceId : workspaceIds) {
                if (!inProgress.add(workspaceId)) {
                    continue; // an on-demand or overlapping scheduled rebuild is already running
                }
                try {
                    rebuild(workspaceId);
                } catch (Exception e) {
                    log.error("scheduled rebuild failed for workspace {}: {}", workspaceId, e.toString(), e);
                } finally {
                    inProgress.remove(workspaceId);
                }
            }
        });
    }

    public enum Outcome { PASS, FAIL, SKIPPED_NOT_BOOTSTRAPPED }

    public record RebuildResult(Outcome outcome, Integer fromGeneration, Integer toGeneration,
                                EquivalenceVerifier.EquivalenceResult equivalence) {}

    /** Synchronous build -> verify -> flip-or-drop for one workspace. Package-visible for direct test invocation. */
    RebuildResult rebuild(UUID workspaceId) {
        Integer currentGeneration = currentLiveGeneration(workspaceId);
        if (currentGeneration == null) {
            // Nothing to rebuild FROM — the Projector has not bootstrapped generation 0 for this
            // workspace yet (research R4: RebuildJob only ever flips an ALREADY-live generation).
            log.info("skipping rebuild for workspace {}: no live generation yet (not bootstrapped)", workspaceId);
            return new RebuildResult(Outcome.SKIPPED_NOT_BOOTSTRAPPED, null, null, null);
        }
        int newGeneration = currentGeneration + 1;

        BuiltGeneration built = buildGeneration(workspaceId, newGeneration);

        TransactionTemplate writeTx = requiresNew(projectorTransactionManager);
        writeTx.executeWithoutResult(status -> {
            projectorRlsBinder.bindCurrentTransaction(workspaceId);
            for (GraphNode node : built.nodes()) graphWriteRepository.upsertNode(node);
            for (GraphEdge edge : built.edges()) graphWriteRepository.upsertEdge(edge);
        });

        EquivalenceVerifier.EquivalenceResult equivalence = equivalenceVerifier.verify(workspaceId, newGeneration);

        // T020: feed the d2os.rebuild.equivalence.divergent gauge — this workspace is divergent iff the
        // equivalence check did not pass (cleared again on the next PASS rebuild).
        projectionMetrics.recordRebuildResult(workspaceId, !equivalence.isPass());

        if (equivalence.isPass()) {
            TransactionTemplate flipTx = requiresNew(projectorTransactionManager);
            flipTx.executeWithoutResult(status -> {
                projectorRlsBinder.bindCurrentTransaction(workspaceId);
                graphWriteRepository.upsertState(workspaceId, newGeneration, OffsetDateTime.now(), "PASS");
                graphWriteRepository.purgeGeneration(workspaceId, currentGeneration);
            });
            log.info("rebuild PASS for workspace {}: generation {} -> {}", workspaceId, currentGeneration, newGeneration);
        } else {
            TransactionTemplate failTx = requiresNew(projectorTransactionManager);
            failTx.executeWithoutResult(status -> {
                projectorRlsBinder.bindCurrentTransaction(workspaceId);
                graphWriteRepository.purgeGeneration(workspaceId, newGeneration); // drop the divergent N+1
                graphWriteRepository.upsertState(workspaceId, currentGeneration, OffsetDateTime.now(), "FAIL");
                graphWriteRepository.insertGap(divergenceAlert(workspaceId, newGeneration, equivalence));
            });
            log.error("rebuild FAIL for workspace {}: generation {} stays live, {} dropped — {} node type "
                            + "mismatch(es), {} edge type mismatch(es)",
                    workspaceId, currentGeneration, newGeneration,
                    equivalence.nodeMismatches().size(), equivalence.edgeMismatches().size());
        }

        Outcome outcome = equivalence.isPass() ? Outcome.PASS : Outcome.FAIL;
        return new RebuildResult(outcome, currentGeneration, newGeneration, equivalence);
    }

    /**
     * Reuses the {@code projection_gap} table (rather than standing up a separate cross-datasource
     * write to {@code in_app_notification}, which lives in governance's {@code d2os_app}-writable
     * schema — a different datasource/transaction than the one this flip-or-drop decision already
     * runs in, per T007's dual-datasource design) as the divergence alert record, per this task's
     * own suggested "log + a projection_gap-style record" framing. {@code event_type =
     * REBUILD_DIVERGENCE}; {@code missing_fields} repurposed to carry the mismatching node/edge type
     * names (not literally "missing JSON fields" here, but the same "what's wrong, named" shape).
     */
    private ProjectionGap divergenceAlert(UUID workspaceId, int candidateGeneration,
                                          EquivalenceVerifier.EquivalenceResult equivalence) {
        List<String> mismatchTypes = new ArrayList<>();
        equivalence.nodeMismatches().forEach(m -> mismatchTypes.add("node:" + m.type()));
        equivalence.edgeMismatches().forEach(m -> mismatchTypes.add("edge:" + m.type()));
        if (mismatchTypes.isEmpty()) mismatchTypes.add("unknown");
        return new ProjectionGap(UUID.randomUUID(), workspaceId, UUID.randomUUID(),
                "REBUILD_DIVERGENCE:generation-" + candidateGeneration,
                mismatchTypes.toArray(new String[0]), OffsetDateTime.now(), ProjectionGap.Status.OPEN);
    }

    private Integer currentLiveGeneration(UUID workspaceId) {
        WorkspaceContext.set(workspaceId);
        try {
            TransactionTemplate readTx = requiresNew(transactionManager);
            return readTx.execute(status -> {
                workspaceRlsBinder.bindCurrentTransaction(workspaceId);
                List<Integer> rows = jdbcTemplate.queryForList(
                        "SELECT live_generation FROM projection_state WHERE workspace_id = ?",
                        Integer.class, workspaceId);
                return rows.isEmpty() ? null : rows.get(0);
            });
        } finally {
            WorkspaceContext.clear();
        }
    }

    // ---- build generation N+1 directly from source tables (never event_outbox) ---------------------

    private record BuiltGeneration(List<GraphNode> nodes, List<GraphEdge> edges) {}

    private BuiltGeneration buildGeneration(UUID workspaceId, int generation) {
        WorkspaceContext.set(workspaceId);
        try {
            TransactionTemplate readTx = requiresNew(transactionManager);
            return readTx.execute(status -> {
                workspaceRlsBinder.bindCurrentTransaction(workspaceId);
                List<GraphNode> nodes = new ArrayList<>();
                List<GraphEdge> edges = new ArrayList<>();
                buildCaseNodes(workspaceId, generation, nodes, edges);
                buildArtifactRevisionNodes(workspaceId, generation, nodes, edges);
                buildGateNodes(workspaceId, generation, nodes, edges);
                buildTraceLinkNodes(workspaceId, generation, nodes, edges);
                buildDependencyNodes(workspaceId, generation, nodes, edges);
                buildInjectionSnapshotNodes(workspaceId, generation, nodes, edges);
                return new BuiltGeneration(nodes, edges);
            });
        } finally {
            WorkspaceContext.clear();
        }
    }

    private void buildCaseNodes(UUID workspaceId, int generation, List<GraphNode> nodes, List<GraphEdge> edges) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, feature_id, status FROM case_instance WHERE workspace_id = ?", workspaceId);
        for (Map<String, Object> row : rows) {
            UUID caseId = (UUID) row.get("id");
            NodeEdgeMapper.CaseLifecycleFact fact = new NodeEdgeMapper.CaseLifecycleFact(workspaceId, caseId,
                    (UUID) row.get("feature_id"), (String) row.get("status"), caseId, OffsetDateTime.now());
            NodeEdgeMapper.MappingResult result = mapper.mapCaseLifecycle(fact, generation);
            nodes.addAll(result.nodes());
            edges.addAll(result.edges());
        }
    }

    private void buildArtifactRevisionNodes(UUID workspaceId, int generation, List<GraphNode> nodes, List<GraphEdge> edges) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT ar.id AS revision_id, ar.artifact_id, ar.revision_no, a.artifact_type, "
                        + "       ar.produced_by_operation_execution_id "
                        + "FROM artifact_revision ar JOIN artifact a ON a.id = ar.artifact_id "
                        + "WHERE ar.workspace_id = ?",
                workspaceId);
        for (Map<String, Object> row : rows) {
            UUID revisionId = (UUID) row.get("revision_id");
            NodeEdgeMapper.ArtifactRevisionFact fact = new NodeEdgeMapper.ArtifactRevisionFact(workspaceId,
                    (UUID) row.get("artifact_id"), revisionId, ((Number) row.get("revision_no")).intValue(),
                    (String) row.get("artifact_type"), (UUID) row.get("produced_by_operation_execution_id"),
                    revisionId, OffsetDateTime.now());
            NodeEdgeMapper.MappingResult result = mapper.mapArtifactRevision(fact, generation);
            nodes.addAll(result.nodes());
            edges.addAll(result.edges());
        }
    }

    private void buildGateNodes(UUID workspaceId, int generation, List<GraphNode> nodes, List<GraphEdge> edges) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, case_instance_id, gate_type, gate_definition_key, gate_definition_version, "
                        + "       subject_artifact_revision_id "
                        + "FROM gate_instance WHERE workspace_id = ?",
                workspaceId);
        for (Map<String, Object> row : rows) {
            UUID gateId = (UUID) row.get("id");
            NodeEdgeMapper.GateEventFact fact = new NodeEdgeMapper.GateEventFact(workspaceId, gateId,
                    (String) row.get("gate_type"), (String) row.get("gate_definition_key"),
                    ((Number) row.get("gate_definition_version")).intValue(),
                    (UUID) row.get("case_instance_id"), (UUID) row.get("subject_artifact_revision_id"),
                    "REBUILD_SNAPSHOT", null, null, gateId, OffsetDateTime.now());
            NodeEdgeMapper.MappingResult result = mapper.mapGateEvent(fact, generation);
            nodes.addAll(result.nodes());
            edges.addAll(result.edges());
        }
    }

    private void buildTraceLinkNodes(UUID workspaceId, int generation, List<GraphNode> nodes, List<GraphEdge> edges) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, from_type, from_id, to_type, to_id, link_type, created_at "
                        + "FROM trace_link WHERE workspace_id = ?",
                workspaceId);
        for (Map<String, Object> row : rows) {
            NodeEdgeMapper.TraceLinkFact fact = new NodeEdgeMapper.TraceLinkFact(workspaceId,
                    (UUID) row.get("id"), (String) row.get("from_type"), (UUID) row.get("from_id"),
                    (String) row.get("to_type"), (UUID) row.get("to_id"), (String) row.get("link_type"),
                    OffsetDateTime.now());
            NodeEdgeMapper.MappingResult result = mapper.mapTraceLink(fact, generation);
            nodes.addAll(result.nodes());
            edges.addAll(result.edges());
        }
    }

    /** Phase 5 US3 (T021) — see {@link Projector}'s javadoc's "dependency/DEPENDS_ON — wired, still writer-less" section. */
    private void buildDependencyNodes(UUID workspaceId, int generation, List<GraphNode> nodes, List<GraphEdge> edges) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, from_type, from_id, to_type, to_id, dep_type, created_at "
                        + "FROM dependency WHERE workspace_id = ?",
                workspaceId);
        for (Map<String, Object> row : rows) {
            NodeEdgeMapper.DependencyFact fact = new NodeEdgeMapper.DependencyFact(workspaceId,
                    (UUID) row.get("id"), (String) row.get("from_type"), (UUID) row.get("from_id"),
                    (String) row.get("to_type"), (UUID) row.get("to_id"), (String) row.get("dep_type"),
                    OffsetDateTime.now());
            NodeEdgeMapper.MappingResult result = mapper.mapDependency(fact, generation);
            nodes.addAll(result.nodes());
            edges.addAll(result.edges());
        }
    }

    /** Phase 6 US4 (T025) — see {@link Projector}'s javadoc's "knowledge_injection_snapshot/INJECTED_INTO" section. */
    private void buildInjectionSnapshotNodes(UUID workspaceId, int generation, List<GraphNode> nodes, List<GraphEdge> edges) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, operation_execution_id, knowledge_item_id, knowledge_item_key, "
                        + "       knowledge_item_version, position "
                        + "FROM knowledge_injection_snapshot WHERE workspace_id = ?",
                workspaceId);
        for (Map<String, Object> row : rows) {
            NodeEdgeMapper.InjectionSnapshotFact fact = new NodeEdgeMapper.InjectionSnapshotFact(workspaceId,
                    (UUID) row.get("id"), (UUID) row.get("operation_execution_id"),
                    (UUID) row.get("knowledge_item_id"), (String) row.get("knowledge_item_key"),
                    ((Number) row.get("knowledge_item_version")).intValue(),
                    ((Number) row.get("position")).intValue(), OffsetDateTime.now());
            NodeEdgeMapper.MappingResult result = mapper.mapInjectionSnapshot(fact, generation);
            nodes.addAll(result.nodes());
            edges.addAll(result.edges());
        }
    }

    private TransactionTemplate requiresNew(PlatformTransactionManager manager) {
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        def.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new TransactionTemplate(manager, def);
    }
}
