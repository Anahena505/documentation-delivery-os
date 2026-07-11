package com.d2os.projection.influence;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * T026 — knowledge-influence dashboard data (research R9, FR-010, Principle III). Joins the
 * projected {@code INJECTED_INTO}/{@code PRODUCED} edges (T025) with the Phase 3 {@code kpi_sample}
 * stream (read-only — {@code metric = 'knowledge_influence'}; this class never computes a new
 * influence value, only attributes already-recorded ones to the item version and the operations/
 * artifacts navigable from it along real graph edges).
 *
 * <p>Driven off the live graph generation, not the source tables directly, so "navigable to the
 * exact operations/artifacts touched" (T026's own wording) means literally following {@code
 * INJECTED_INTO}/{@code PRODUCED} edges, not re-deriving the same relationships independently. The
 * one exception is the optional {@code caseId} filter: the graph carries no direct edge from CASE
 * to the OPERATION_EXECUTION nodes an item was injected into (no such edge is defined anywhere in
 * data-model.md's mapping table), so case membership is resolved with a direct join against {@code
 * operation_execution}/{@code persona_invocation} — a scoping filter, not new graph structure.
 *
 * <p>Runs synchronously inside an HTTP request ({@link
 * com.d2os.projection.api.InfluenceController}), same posture as {@link
 * com.d2os.projection.query.TraceabilityQueryService}: no explicit RLS binding here because {@code
 * WorkspaceContextFilter} already bound the session for this request (unlike the background jobs —
 * {@code Projector}/{@code RebuildJob}/{@code CycleDetector} — which must bind it themselves).
 */
@Service
public class InfluenceAnalyticsService {

  private final JdbcTemplate jdbcTemplate;

  public InfluenceAnalyticsService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public enum State {
    MEASURED,
    NOT_YET_MEASURABLE
  }

  /**
   * {@code delta}/{@code recordedAt} match contracts/api.yaml's {@code InfluenceEntry.readings}
   * shape ({@code kpi_sample.value}/{@code at} respectively). The contract's third field, {@code
   * operationDefinitionKey}, is NOT recorded anywhere in {@code kpi_sample} (verified — V9/ V15
   * carry no such column, and {@code dimensions} only ever holds {@code key}/{@code version});
   * omitted here rather than fabricated.
   */
  public record Reading(double delta, OffsetDateTime recordedAt) {}

  /**
   * {@code touchedOperations}/{@code touchedArtifacts} are {@link GraphNode} ids (the surrogate
   * {@code graph_node.id}, NOT the business {@code operation_execution.id}/{@code
   * artifact_revision.id}) &mdash; the caller ({@link com.d2os.projection.api.InfluenceController})
   * resolves them straight into {@code GraphNodeView}s via {@link GraphNodeRepository}, matching
   * contracts/api.yaml's {@code InfluenceEntry.touchedOperations}/{@code touchedArtifacts} being
   * {@code GraphNode} arrays, not bare ids.
   */
  public record InfluenceEntry(
      String knowledgeKey,
      int knowledgeVersion,
      State state,
      List<Reading> readings,
      List<UUID> touchedOperations,
      List<UUID> touchedArtifacts) {}

  /**
   * {@code knowledgeKey}/{@code caseId} are both optional filters (contracts/api.yaml). An item
   * with recorded {@code kpi_sample} rows is {@link State#MEASURED}; an item that was injected but
   * never sampled reports {@link State#NOT_YET_MEASURABLE} (consistent with Phase 3 FR-018 — never
   * silently omitted, never a fabricated zero).
   */
  public List<InfluenceEntry> analyze(UUID workspaceId, String knowledgeKey, UUID caseId) {
    Integer generation = liveGeneration(workspaceId);
    if (generation == null) {
      return List.of();
    }

    List<Map<String, Object>> itemNodes =
        knowledgeKey == null
            ? jdbcTemplate.queryForList(
                "SELECT id, natural_key FROM graph_node "
                    + "WHERE workspace_id = ? AND generation = ? AND node_type = 'KNOWLEDGE_ITEM_VERSION'",
                workspaceId,
                generation)
            : jdbcTemplate.queryForList(
                "SELECT id, natural_key FROM graph_node "
                    + "WHERE workspace_id = ? AND generation = ? AND node_type = 'KNOWLEDGE_ITEM_VERSION' "
                    + "AND natural_key LIKE ?",
                workspaceId,
                generation,
                knowledgeKey + ":%");

    List<InfluenceEntry> entries = new ArrayList<>();
    for (Map<String, Object> itemRow : itemNodes) {
      UUID itemNodeId = (UUID) itemRow.get("id");
      String naturalKey = (String) itemRow.get("natural_key");
      int sep = naturalKey.lastIndexOf(':');
      String key = naturalKey.substring(0, sep);
      int version = Integer.parseInt(naturalKey.substring(sep + 1));

      List<UUID> operationBusinessIds = injectedOperationIds(workspaceId, generation, itemNodeId);
      if (caseId != null) {
        operationBusinessIds = scopeToCase(workspaceId, caseId, operationBusinessIds);
        if (operationBusinessIds.isEmpty()) {
          continue; // this item was never injected within the requested case — omit it
        }
      }
      List<UUID> artifactBusinessIds =
          producedArtifactIds(workspaceId, generation, operationBusinessIds);

      List<UUID> touchedOperations =
          resolveGraphNodeIds(workspaceId, generation, "OPERATION_EXECUTION", operationBusinessIds);
      List<UUID> touchedArtifacts =
          resolveGraphNodeIds(workspaceId, generation, "ARTIFACT_REVISION", artifactBusinessIds);

      List<Reading> readings = readings(workspaceId, key, version);
      State state = readings.isEmpty() ? State.NOT_YET_MEASURABLE : State.MEASURED;

      entries.add(
          new InfluenceEntry(key, version, state, readings, touchedOperations, touchedArtifacts));
    }
    return entries;
  }

  private Integer liveGeneration(UUID workspaceId) {
    List<Integer> rows =
        jdbcTemplate.queryForList(
            "SELECT live_generation FROM projection_state WHERE workspace_id = ?",
            Integer.class,
            workspaceId);
    return rows.isEmpty() ? null : rows.get(0);
  }

  /**
   * Resolves business ids (natural keys) into their surrogate {@code graph_node.id} for a given
   * node type/generation &mdash; the last step before returning {@link InfluenceEntry}'s
   * touched-operation/-artifact lists, so the controller can look them up directly by id.
   */
  private List<UUID> resolveGraphNodeIds(
      UUID workspaceId, int generation, String nodeType, List<UUID> businessIds) {
    if (businessIds.isEmpty()) {
      return List.of();
    }
    String placeholders = String.join(",", businessIds.stream().map(id -> "?").toList());
    Object[] args = new Object[3 + businessIds.size()];
    args[0] = workspaceId;
    args[1] = generation;
    args[2] = nodeType;
    for (int i = 0; i < businessIds.size(); i++) args[i + 3] = businessIds.get(i).toString();
    return jdbcTemplate.queryForList(
        "SELECT id FROM graph_node WHERE workspace_id = ? AND generation = ? AND node_type = ? "
            + "AND natural_key IN ("
            + placeholders
            + ")",
        UUID.class,
        args);
  }

  /**
   * INJECTED_INTO edges: {@code itemNode -> OPERATION_EXECUTION node}, natural_key IS the operation
   * execution id.
   */
  private List<UUID> injectedOperationIds(UUID workspaceId, int generation, UUID itemNodeId) {
    List<String> naturalKeys =
        jdbcTemplate.queryForList(
            "SELECT gn.natural_key FROM graph_edge ge JOIN graph_node gn ON gn.id = ge.to_node "
                + "WHERE ge.workspace_id = ? AND ge.generation = ? AND ge.edge_type = 'INJECTED_INTO' "
                + "AND ge.from_node = ?",
            String.class,
            workspaceId,
            generation,
            itemNodeId);
    return naturalKeys.stream().map(UUID::fromString).toList();
  }

  private List<UUID> scopeToCase(UUID workspaceId, UUID caseId, List<UUID> operationIds) {
    if (operationIds.isEmpty()) {
      return List.of();
    }
    String placeholders = String.join(",", operationIds.stream().map(id -> "?").toList());
    Object[] args = new Object[2 + operationIds.size()];
    args[0] = workspaceId;
    args[1] = caseId;
    for (int i = 0; i < operationIds.size(); i++) args[i + 2] = operationIds.get(i);
    return jdbcTemplate.queryForList(
        "SELECT oe.id FROM operation_execution oe "
            + "JOIN persona_invocation pi ON pi.id = oe.persona_invocation_id "
            + "WHERE oe.workspace_id = ? AND pi.case_instance_id = ? AND oe.id IN ("
            + placeholders
            + ")",
        UUID.class,
        args);
  }

  /**
   * PRODUCED edges FROM the given OPERATION_EXECUTION nodes -> ARTIFACT_REVISION nodes (business
   * ids both sides).
   */
  private List<UUID> producedArtifactIds(
      UUID workspaceId, int generation, List<UUID> operationIds) {
    if (operationIds.isEmpty()) {
      return List.of();
    }
    String placeholders = String.join(",", operationIds.stream().map(id -> "?").toList());
    Object[] args = new Object[2 + operationIds.size()];
    args[0] = workspaceId;
    args[1] = generation;
    for (int i = 0; i < operationIds.size(); i++) args[i + 2] = operationIds.get(i).toString();
    List<String> naturalKeys =
        jdbcTemplate.queryForList(
            "SELECT gn2.natural_key FROM graph_edge ge "
                + "JOIN graph_node gn1 ON gn1.id = ge.from_node "
                + "JOIN graph_node gn2 ON gn2.id = ge.to_node "
                + "WHERE ge.workspace_id = ? AND ge.generation = ? AND ge.edge_type = 'PRODUCED' "
                + "AND gn1.node_type = 'OPERATION_EXECUTION' AND gn1.natural_key IN ("
                + placeholders
                + ")",
            String.class,
            args);
    return naturalKeys.stream().map(UUID::fromString).toList();
  }

  private List<Reading> readings(UUID workspaceId, String key, int version) {
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "SELECT value, at FROM kpi_sample WHERE workspace_id = ? AND metric = 'knowledge_influence' "
                + "AND dimensions->>'key' = ? AND dimensions->>'version' = ? ORDER BY at",
            workspaceId,
            key,
            String.valueOf(version));
    List<Reading> readings = new ArrayList<>(rows.size());
    for (Map<String, Object> row : rows) {
      readings.add(
          new Reading(((Number) row.get("value")).doubleValue(), toOffsetDateTime(row.get("at"))));
    }
    return readings;
  }

  private static OffsetDateTime toOffsetDateTime(Object value) {
    if (value instanceof OffsetDateTime odt) return odt;
    if (value instanceof Timestamp ts) return ts.toInstant().atOffset(ZoneOffset.UTC);
    return null;
  }
}
