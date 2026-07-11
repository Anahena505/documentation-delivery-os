package com.d2os.projection;

import com.d2os.tenancy.WorkspaceContext;
import com.d2os.tenancy.security.WorkspaceRlsBinder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * T010 — per-workspace, per-type equivalence check between a candidate graph generation and
 * "relational truth" assembled directly from source tables (research R5, FR-002). Used both by
 * {@link RebuildJob} (verify-then-flip) and, scheduled, as live-generation drift detection.
 *
 * <h2>Independence from {@link NodeEdgeMapper} (the whole point of R5)</h2>
 * This class NEVER calls {@link NodeEdgeMapper} or any {@code *Fact} record. Every truth-side
 * natural key/edge-identity tuple below is computed by hand-written SQL against the source tables,
 * duplicating (not reusing) the small amount of derivation logic the mapper also encodes — e.g.
 * "an artifact_revision whose owning artifact's {@code artifact_type} starts with {@code
 * requirement:} also counts as a REQUIREMENT". A bug shared between {@link RebuildJob}'s candidate
 * build and this class's truth computation WOULD go undetected if this class called the mapper too
 * (that would just prove the mapper agrees with itself, per R5's rationale) — so the two are kept
 * as two separately-written pieces of code that happen to target the same documented contract
 * (data-model.md's Node/Edge Mapping table), the same way two implementations of a spec can be
 * cross-checked against each other.
 *
 * <h2>Scope (matches {@link Projector}/{@link RebuildJob} exactly)</h2>
 * Node types: {@code CASE}, {@code FEATURE}, {@code ARTIFACT_REVISION}, {@code REQUIREMENT}, {@code
 * OPERATION_EXECUTION}, {@code GATE}, plus whatever generic node types {@code trace_link}'s and,
 * as of Phase 5 US3 (T021), {@code dependency}'s endpoints resolve to (commonly {@code
 * OPERATION_EXECUTION}, since {@code ConsistencyService#writeConflictEdge} — the only real
 * trace_link writer — links two of those; {@code dependency} itself is still writer-less at the
 * application layer, see {@link Projector}'s javadoc, but checked here in lockstep with the
 * projector/rebuild pair) plus, as of Phase 6 US4 (T025), {@code KNOWLEDGE_ITEM_VERSION} (every
 * {@code knowledge_injection_snapshot} row). Edge types: {@code BELONGS_TO}, {@code PRODUCED},
 * {@code GATED_BY}, {@code TRACES_TO}/{@code DERIVES_FROM}/{@code SATISFIES}, {@code DEPENDS_ON},
 * {@code INJECTED_INTO}. See {@link Projector}'s javadoc for why {@code PACKAGE}/{@code
 * DEFINITION_VERSION} are still deferred — this class only checks what the projector/rebuild pair
 * actually populate; checking a type nothing builds would either always spuriously fail (candidate
 * empty, truth non-empty) or always trivially pass (both empty), neither of which is a meaningful
 * check.
 *
 * <h2>Provenance convention</h2>
 * {@code source_ref} for every type here is the source row's own primary-key id (the "self-id"
 * convention documented on {@link Projector}) — required so a generation built incrementally
 * (outbox-driven) and a generation built by {@link RebuildJob} (table-scan-driven) produce
 * byte-identical edge identities for the SAME logical fact, and so this class's independently
 * computed truth matches either one.
 */
@Component
public class EquivalenceVerifier {

    private final JdbcTemplate jdbcTemplate;
    private final WorkspaceRlsBinder workspaceRlsBinder;
    private final PlatformTransactionManager transactionManager;

    public EquivalenceVerifier(JdbcTemplate jdbcTemplate, WorkspaceRlsBinder workspaceRlsBinder,
                               PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.workspaceRlsBinder = workspaceRlsBinder;
        this.transactionManager = transactionManager;
    }

    public record TypeDigest(String type, long count, String sha256Hex) {}

    public record TypeMismatch(String type, long candidateCount, long truthCount,
                               List<String> onlyInCandidateSample, List<String> onlyInTruthSample) {}

    public record EquivalenceResult(boolean matched, List<TypeMismatch> nodeMismatches,
                                    List<TypeMismatch> edgeMismatches) {
        public boolean isPass() {
            return matched;
        }
    }

    private static final int SAMPLE_LIMIT = 10;

    /** Compares candidate generation {@code candidateGeneration} against freshly-queried relational truth. */
    public EquivalenceResult verify(UUID workspaceId, int candidateGeneration) {
        WorkspaceContext.set(workspaceId);
        try {
            TransactionTemplate readTx = requiresNew(transactionManager);
            return readTx.execute(status -> {
                workspaceRlsBinder.bindCurrentTransaction(workspaceId);

                Map<String, TreeSet<String>> candidateNodeKeys = candidateNodeKeys(workspaceId, candidateGeneration);
                Map<String, TreeSet<String>> truthNodeKeys = truthNodeKeys(workspaceId);
                List<TypeMismatch> nodeMismatches = compare(candidateNodeKeys, truthNodeKeys);

                Map<String, TreeSet<String>> candidateEdgeKeys = candidateEdgeKeys(workspaceId, candidateGeneration);
                Map<String, TreeSet<String>> truthEdgeKeys = truthEdgeKeys(workspaceId);
                List<TypeMismatch> edgeMismatches = compare(candidateEdgeKeys, truthEdgeKeys);

                boolean matched = nodeMismatches.isEmpty() && edgeMismatches.isEmpty();
                return new EquivalenceResult(matched, nodeMismatches, edgeMismatches);
            });
        } finally {
            WorkspaceContext.clear();
        }
    }

    // ---- candidate side (graph_node / graph_edge for the given generation) ------------------------

    private Map<String, TreeSet<String>> candidateNodeKeys(UUID workspaceId, int generation) {
        Map<String, TreeSet<String>> byType = new LinkedHashMap<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT node_type, natural_key FROM graph_node WHERE workspace_id = ? AND generation = ?",
                workspaceId, generation);
        for (Map<String, Object> row : rows) {
            byType.computeIfAbsent((String) row.get("node_type"), t -> new TreeSet<>())
                    .add((String) row.get("natural_key"));
        }
        return byType;
    }

    private Map<String, TreeSet<String>> candidateEdgeKeys(UUID workspaceId, int generation) {
        Map<String, TreeSet<String>> byType = new LinkedHashMap<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT ge.edge_type, gn1.node_type AS from_type, gn1.natural_key AS from_key, "
                        + "       gn2.node_type AS to_type, gn2.natural_key AS to_key, ge.source_ref "
                        + "FROM graph_edge ge "
                        + "JOIN graph_node gn1 ON gn1.id = ge.from_node "
                        + "JOIN graph_node gn2 ON gn2.id = ge.to_node "
                        + "WHERE ge.workspace_id = ? AND ge.generation = ?",
                workspaceId, generation);
        for (Map<String, Object> row : rows) {
            String edgeType = (String) row.get("edge_type");
            String key = edgeIdentity((String) row.get("from_type"), (String) row.get("from_key"),
                    (String) row.get("to_type"), (String) row.get("to_key"), (String) row.get("source_ref"));
            byType.computeIfAbsent(edgeType, t -> new TreeSet<>()).add(key);
        }
        return byType;
    }

    // ---- truth side (independent SQL against source tables, no NodeEdgeMapper involved) -----------

    private Map<String, TreeSet<String>> truthNodeKeys(UUID workspaceId) {
        Map<String, TreeSet<String>> byType = new LinkedHashMap<>();

        // CASE + FEATURE (case_instance.feature_id is NOT NULL, so every case implies exactly one
        // FEATURE node — matching NodeEdgeMapper#mapCaseLifecycle's unconditional-if-present branch,
        // which is unconditionally true here).
        List<Map<String, Object>> cases = jdbcTemplate.queryForList(
                "SELECT id, feature_id FROM case_instance WHERE workspace_id = ?", workspaceId);
        for (Map<String, Object> row : cases) {
            byType.computeIfAbsent(NodeEdgeMapper.NODE_CASE, t -> new TreeSet<>()).add(row.get("id").toString());
            byType.computeIfAbsent(NodeEdgeMapper.NODE_FEATURE, t -> new TreeSet<>())
                    .add(row.get("feature_id").toString());
        }

        // ARTIFACT_REVISION (+ REQUIREMENT subtype) + OPERATION_EXECUTION (from produced_by_...) +,
        // as of US6 (T058), TEMPLATE/DEFINITION_VERSION provenance nodes (from source_template_id +
        // template_version) — independently mirroring NodeEdgeMapper#mapArtifactRevision.
        List<Map<String, Object>> revisions = jdbcTemplate.queryForList(
                "SELECT ar.id, a.artifact_type, ar.produced_by_operation_execution_id, "
                        + "       ar.source_template_id, ar.template_version "
                        + "FROM artifact_revision ar JOIN artifact a ON a.id = ar.artifact_id "
                        + "WHERE ar.workspace_id = ?",
                workspaceId);
        for (Map<String, Object> row : revisions) {
            String revisionKey = row.get("id").toString();
            byType.computeIfAbsent(NodeEdgeMapper.NODE_ARTIFACT_REVISION, t -> new TreeSet<>()).add(revisionKey);
            if (NodeEdgeMapper.isRequirementType((String) row.get("artifact_type"))) {
                byType.computeIfAbsent(NodeEdgeMapper.NODE_REQUIREMENT, t -> new TreeSet<>()).add(revisionKey);
            }
            Object opExecId = row.get("produced_by_operation_execution_id");
            if (opExecId != null) {
                byType.computeIfAbsent(NodeEdgeMapper.NODE_OPERATION_EXECUTION, t -> new TreeSet<>())
                        .add(opExecId.toString());
            }
            Object sourceTemplateId = row.get("source_template_id");
            Object templateVersion = row.get("template_version");
            if (sourceTemplateId != null && templateVersion != null) {
                String templateKey = sourceTemplateId + ":" + templateVersion;
                byType.computeIfAbsent(NodeEdgeMapper.NODE_TEMPLATE, t -> new TreeSet<>()).add(templateKey);
                byType.computeIfAbsent(NodeEdgeMapper.NODE_DEFINITION_VERSION, t -> new TreeSet<>()).add(templateKey);
            }
        }

        // GATE (+ its subject node, resolved the same way NodeEdgeMapper#mapGateEvent does).
        List<Map<String, Object>> gates = jdbcTemplate.queryForList(
                "SELECT id, case_instance_id, subject_artifact_revision_id FROM gate_instance WHERE workspace_id = ?",
                workspaceId);
        for (Map<String, Object> row : gates) {
            byType.computeIfAbsent(NodeEdgeMapper.NODE_GATE, t -> new TreeSet<>()).add(row.get("id").toString());
            Object subjectRevisionId = row.get("subject_artifact_revision_id");
            Object caseId = row.get("case_instance_id");
            if (subjectRevisionId != null) {
                byType.computeIfAbsent(NodeEdgeMapper.NODE_ARTIFACT_REVISION, t -> new TreeSet<>())
                        .add(subjectRevisionId.toString());
            } else if (caseId != null) {
                byType.computeIfAbsent(NodeEdgeMapper.NODE_CASE, t -> new TreeSet<>()).add(caseId.toString());
            }
        }

        // trace_link endpoints (generic node_type resolution, mirroring NodeEdgeMapper's own table).
        List<Map<String, Object>> traceLinks = jdbcTemplate.queryForList(
                "SELECT from_type, from_id, to_type, to_id FROM trace_link WHERE workspace_id = ?", workspaceId);
        for (Map<String, Object> row : traceLinks) {
            byType.computeIfAbsent(nodeTypeForSourceTable((String) row.get("from_type")), t -> new TreeSet<>())
                    .add(row.get("from_id").toString());
            byType.computeIfAbsent(nodeTypeForSourceTable((String) row.get("to_type")), t -> new TreeSet<>())
                    .add(row.get("to_id").toString());
        }

        // dependency endpoints (Phase 5 US3, T021) — same generic node_type resolution as trace_link,
        // above; still writer-less at the application layer (see Projector's javadoc) but checked
        // whenever a row DOES exist (today, only via direct test SQL).
        List<Map<String, Object>> dependencies = jdbcTemplate.queryForList(
                "SELECT from_type, from_id, to_type, to_id FROM dependency WHERE workspace_id = ?", workspaceId);
        for (Map<String, Object> row : dependencies) {
            byType.computeIfAbsent(nodeTypeForSourceTable((String) row.get("from_type")), t -> new TreeSet<>())
                    .add(row.get("from_id").toString());
            byType.computeIfAbsent(nodeTypeForSourceTable((String) row.get("to_type")), t -> new TreeSet<>())
                    .add(row.get("to_id").toString());
        }

        // KNOWLEDGE_ITEM_VERSION + OPERATION_EXECUTION (Phase 6 US4, T025) — mirrors NodeEdgeMapper#mapInjectionSnapshot.
        List<Map<String, Object>> injections = jdbcTemplate.queryForList(
                "SELECT operation_execution_id, knowledge_item_key, knowledge_item_version "
                        + "FROM knowledge_injection_snapshot WHERE workspace_id = ?", workspaceId);
        for (Map<String, Object> row : injections) {
            String naturalKey = row.get("knowledge_item_key") + ":" + row.get("knowledge_item_version");
            byType.computeIfAbsent(NodeEdgeMapper.NODE_KNOWLEDGE_ITEM_VERSION, t -> new TreeSet<>()).add(naturalKey);
            byType.computeIfAbsent(NodeEdgeMapper.NODE_OPERATION_EXECUTION, t -> new TreeSet<>())
                    .add(row.get("operation_execution_id").toString());
        }

        return byType;
    }

    private Map<String, TreeSet<String>> truthEdgeKeys(UUID workspaceId) {
        Map<String, TreeSet<String>> byType = new LinkedHashMap<>();

        // BELONGS_TO: CASE -> FEATURE, sourceRef = case id (self-id convention).
        List<Map<String, Object>> cases = jdbcTemplate.queryForList(
                "SELECT id, feature_id FROM case_instance WHERE workspace_id = ?", workspaceId);
        for (Map<String, Object> row : cases) {
            String caseId = row.get("id").toString();
            String key = edgeIdentity(NodeEdgeMapper.NODE_CASE, caseId,
                    NodeEdgeMapper.NODE_FEATURE, row.get("feature_id").toString(), caseId);
            byType.computeIfAbsent(NodeEdgeMapper.EDGE_BELONGS_TO, t -> new TreeSet<>()).add(key);
        }

        // PRODUCED: OPERATION_EXECUTION -> ARTIFACT_REVISION, sourceRef = revision id (self-id). US6
        // (T058) adds PRODUCED_FROM: ARTIFACT_REVISION -> TEMPLATE, sourceRef = revision id, whenever
        // the revision carries provenance — mirroring NodeEdgeMapper#mapArtifactRevision.
        List<Map<String, Object>> revisions = jdbcTemplate.queryForList(
                "SELECT id, produced_by_operation_execution_id, source_template_id, template_version "
                        + "FROM artifact_revision WHERE workspace_id = ?",
                workspaceId);
        for (Map<String, Object> row : revisions) {
            String revisionId = row.get("id").toString();
            Object opExecId = row.get("produced_by_operation_execution_id");
            if (opExecId != null) {
                String key = edgeIdentity(NodeEdgeMapper.NODE_OPERATION_EXECUTION, opExecId.toString(),
                        NodeEdgeMapper.NODE_ARTIFACT_REVISION, revisionId, revisionId);
                byType.computeIfAbsent(NodeEdgeMapper.EDGE_PRODUCED, t -> new TreeSet<>()).add(key);
            }
            Object sourceTemplateId = row.get("source_template_id");
            Object templateVersion = row.get("template_version");
            if (sourceTemplateId != null && templateVersion != null) {
                String templateKey = sourceTemplateId + ":" + templateVersion;
                String key = edgeIdentity(NodeEdgeMapper.NODE_ARTIFACT_REVISION, revisionId,
                        NodeEdgeMapper.NODE_TEMPLATE, templateKey, revisionId);
                byType.computeIfAbsent(NodeEdgeMapper.EDGE_PRODUCED_FROM, t -> new TreeSet<>()).add(key);
            }
        }

        // GATED_BY: subject -> GATE, sourceRef = gate id (self-id).
        List<Map<String, Object>> gates = jdbcTemplate.queryForList(
                "SELECT id, case_instance_id, subject_artifact_revision_id FROM gate_instance WHERE workspace_id = ?",
                workspaceId);
        for (Map<String, Object> row : gates) {
            String gateId = row.get("id").toString();
            Object subjectRevisionId = row.get("subject_artifact_revision_id");
            Object caseId = row.get("case_instance_id");
            String key;
            if (subjectRevisionId != null) {
                key = edgeIdentity(NodeEdgeMapper.NODE_ARTIFACT_REVISION, subjectRevisionId.toString(),
                        NodeEdgeMapper.NODE_GATE, gateId, gateId);
            } else if (caseId != null) {
                key = edgeIdentity(NodeEdgeMapper.NODE_CASE, caseId.toString(),
                        NodeEdgeMapper.NODE_GATE, gateId, gateId);
            } else {
                continue;
            }
            byType.computeIfAbsent(NodeEdgeMapper.EDGE_GATED_BY, t -> new TreeSet<>()).add(key);
        }

        // TRACES_TO / DERIVES_FROM / SATISFIES: trace_link, sourceRef = link id (mapper's own convention).
        List<Map<String, Object>> traceLinks = jdbcTemplate.queryForList(
                "SELECT id, from_type, from_id, to_type, to_id, link_type FROM trace_link WHERE workspace_id = ?",
                workspaceId);
        for (Map<String, Object> row : traceLinks) {
            String edgeType = traceEdgeType((String) row.get("link_type"));
            String key = edgeIdentity(
                    nodeTypeForSourceTable((String) row.get("from_type")), row.get("from_id").toString(),
                    nodeTypeForSourceTable((String) row.get("to_type")), row.get("to_id").toString(),
                    row.get("id").toString());
            byType.computeIfAbsent(edgeType, t -> new TreeSet<>()).add(key);
        }

        // DEPENDS_ON: dependency, sourceRef = dependency row id (self-id convention, Phase 5 US3, T021).
        List<Map<String, Object>> dependencies = jdbcTemplate.queryForList(
                "SELECT id, from_type, from_id, to_type, to_id FROM dependency WHERE workspace_id = ?",
                workspaceId);
        for (Map<String, Object> row : dependencies) {
            String key = edgeIdentity(
                    nodeTypeForSourceTable((String) row.get("from_type")), row.get("from_id").toString(),
                    nodeTypeForSourceTable((String) row.get("to_type")), row.get("to_id").toString(),
                    row.get("id").toString());
            byType.computeIfAbsent(NodeEdgeMapper.EDGE_DEPENDS_ON, t -> new TreeSet<>()).add(key);
        }

        // INJECTED_INTO: KNOWLEDGE_ITEM_VERSION -> OPERATION_EXECUTION, sourceRef = snapshot id (self-id,
        // Phase 6 US4, T025) — mirrors NodeEdgeMapper#mapInjectionSnapshot.
        List<Map<String, Object>> injections = jdbcTemplate.queryForList(
                "SELECT id, operation_execution_id, knowledge_item_key, knowledge_item_version "
                        + "FROM knowledge_injection_snapshot WHERE workspace_id = ?", workspaceId);
        for (Map<String, Object> row : injections) {
            String itemKey = row.get("knowledge_item_key") + ":" + row.get("knowledge_item_version");
            String key = edgeIdentity(NodeEdgeMapper.NODE_KNOWLEDGE_ITEM_VERSION, itemKey,
                    NodeEdgeMapper.NODE_OPERATION_EXECUTION, row.get("operation_execution_id").toString(),
                    row.get("id").toString());
            byType.computeIfAbsent(NodeEdgeMapper.EDGE_INJECTED_INTO, t -> new TreeSet<>()).add(key);
        }

        return byType;
    }

    // ---- shared helpers -------------------------------------------------------------------------

    /** Mirrors {@code NodeEdgeMapper}'s (private) source-table -> node_type lookup, independently written. */
    private static String nodeTypeForSourceTable(String sourceTable) {
        if (sourceTable == null) return "UNKNOWN";
        return switch (sourceTable) {
            case "case_instance" -> NodeEdgeMapper.NODE_CASE;
            case "problem_submission" -> NodeEdgeMapper.NODE_SUBMISSION;
            case "artifact_revision" -> NodeEdgeMapper.NODE_ARTIFACT_REVISION;
            case "execution_package" -> NodeEdgeMapper.NODE_PACKAGE;
            case "definition_asset" -> NodeEdgeMapper.NODE_DEFINITION_VERSION;
            case "knowledge_item" -> NodeEdgeMapper.NODE_KNOWLEDGE_ITEM_VERSION;
            case "operation_execution" -> NodeEdgeMapper.NODE_OPERATION_EXECUTION;
            case "gate_instance" -> NodeEdgeMapper.NODE_GATE;
            case "feature" -> NodeEdgeMapper.NODE_FEATURE;
            case "project" -> NodeEdgeMapper.NODE_PROJECT;
            default -> sourceTable.toUpperCase();
        };
    }

    /** Mirrors {@code NodeEdgeMapper#mapTraceLink}'s edge_type resolution, independently written. */
    private static String traceEdgeType(String linkType) {
        String upper = linkType == null ? "" : linkType.toUpperCase();
        return switch (upper) {
            case "DERIVES_FROM" -> NodeEdgeMapper.EDGE_DERIVES_FROM;
            case "SATISFIES" -> NodeEdgeMapper.EDGE_SATISFIES;
            case "TRACES_TO" -> NodeEdgeMapper.EDGE_TRACES_TO;
            default -> NodeEdgeMapper.EDGE_TRACES_TO; // CONFLICTS_WITH and any other kind (see NodeEdgeMapper javadoc)
        };
    }

    private static String edgeIdentity(String fromType, String fromKey, String toType, String toKey, String sourceRef) {
        return fromType + ":" + fromKey + "|" + toType + ":" + toKey + "|" + sourceRef;
    }

    private List<TypeMismatch> compare(Map<String, TreeSet<String>> candidate, Map<String, TreeSet<String>> truth) {
        List<TypeMismatch> mismatches = new ArrayList<>();
        List<String> allTypes = new ArrayList<>();
        allTypes.addAll(candidate.keySet());
        for (String type : truth.keySet()) {
            if (!allTypes.contains(type)) allTypes.add(type);
        }
        for (String type : allTypes) {
            TreeSet<String> candidateKeys = candidate.getOrDefault(type, new TreeSet<>());
            TreeSet<String> truthKeys = truth.getOrDefault(type, new TreeSet<>());
            TypeDigest candidateDigest = digest(type, candidateKeys);
            TypeDigest truthDigest = digest(type, truthKeys);
            if (!candidateDigest.sha256Hex().equals(truthDigest.sha256Hex())
                    || candidateDigest.count() != truthDigest.count()) {
                TreeSet<String> onlyInCandidate = new TreeSet<>(candidateKeys);
                onlyInCandidate.removeAll(truthKeys);
                TreeSet<String> onlyInTruth = new TreeSet<>(truthKeys);
                onlyInTruth.removeAll(candidateKeys);
                mismatches.add(new TypeMismatch(type, candidateDigest.count(), truthDigest.count(),
                        onlyInCandidate.stream().limit(SAMPLE_LIMIT).toList(),
                        onlyInTruth.stream().limit(SAMPLE_LIMIT).toList()));
            }
        }
        return mismatches;
    }

    private TypeDigest digest(String type, TreeSet<String> sortedKeys) {
        String joined = String.join("\n", sortedKeys);
        return new TypeDigest(type, sortedKeys.size(), HashUtil.sha256Hex(joined));
    }

    private TransactionTemplate requiresNew(PlatformTransactionManager manager) {
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        def.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new TransactionTemplate(manager, def);
    }
}
