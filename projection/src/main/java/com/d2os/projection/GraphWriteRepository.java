package com.d2os.projection;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The WRITE path for the five graph/projector-owned tables (T007) — every method here runs through
 * {@link com.d2os.projection.config.ProjectorDataSourceConfig}'s {@code d2os_projector}-bound
 * {@code JdbcTemplate}, never the app's normal datasource, so every write satisfies the sole-writer
 * DB grant (V28, Principle III). Plain {@code JdbcTemplate} rather than a second JPA {@code
 * EntityManagerFactory} — see {@code ProjectorDataSourceConfig}'s javadoc for why.
 *
 * <p>Callers (the {@code Projector}/{@code RebuildJob}, T008/T009, a later phase) are responsible
 * for running inside a transaction bound to {@code projectorTransactionManager} and calling {@link
 * ProjectorRlsBinder#bindCurrentTransaction} first — this class does not manage transactions or RLS
 * binding itself, it only issues the SQL (same separation of concerns as {@code WorkspaceRlsBinder}
 * vs. the repositories it protects).
 *
 * <p>Upserts key on the same natural-key/identity columns as the V28 UNIQUE constraints ({@code
 * uq_graph_node_natural_key}, {@code uq_graph_edge_identity}), so replaying the same source fact
 * (incremental replay OR a rebuild) is idempotent by construction (research R3, FR-012) — the exact
 * property {@link NodeEdgeMapper}'s deterministic id derivation is designed to make cheap to
 * exploit here.
 */
@Repository
public class GraphWriteRepository {

  private final JdbcTemplate projectorJdbcTemplate;

  public GraphWriteRepository(
      @Qualifier("projectorJdbcTemplate") JdbcTemplate projectorJdbcTemplate) {
    this.projectorJdbcTemplate = projectorJdbcTemplate;
  }

  public void upsertNode(GraphNode node) {
    projectorJdbcTemplate.update(
        "INSERT INTO graph_node "
            + "(id, workspace_id, generation, node_type, natural_key, label, attributes, "
            + " source_kind, source_ref, projected_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?) "
            + "ON CONFLICT ON CONSTRAINT uq_graph_node_natural_key DO UPDATE SET "
            + "  label = EXCLUDED.label, attributes = EXCLUDED.attributes, "
            + "  source_kind = EXCLUDED.source_kind, source_ref = EXCLUDED.source_ref, "
            + "  projected_at = EXCLUDED.projected_at",
        node.getId(),
        node.getWorkspaceId(),
        node.getGeneration(),
        node.getNodeType(),
        node.getNaturalKey(),
        node.getLabel(),
        node.getAttributes(),
        node.getSourceKind(),
        node.getSourceRef(),
        toTimestamp(node.getProjectedAt()));
  }

  public void upsertEdge(GraphEdge edge) {
    projectorJdbcTemplate.update(
        "INSERT INTO graph_edge "
            + "(id, workspace_id, generation, edge_type, from_node, to_node, attributes, "
            + " source_kind, source_ref, projected_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?) "
            + "ON CONFLICT ON CONSTRAINT uq_graph_edge_identity DO UPDATE SET "
            + "  attributes = EXCLUDED.attributes, source_kind = EXCLUDED.source_kind, "
            + "  projected_at = EXCLUDED.projected_at",
        edge.getId(),
        edge.getWorkspaceId(),
        edge.getGeneration(),
        edge.getEdgeType(),
        edge.getFromNode(),
        edge.getToNode(),
        edge.getAttributes(),
        edge.getSourceKind(),
        edge.getSourceRef(),
        toTimestamp(edge.getProjectedAt()));
  }

  /** Deletes an entire generation for a workspace — the rebuild job's post-flip purge (T009). */
  public void purgeGeneration(UUID workspaceId, int generation) {
    projectorJdbcTemplate.update(
        "DELETE FROM graph_edge WHERE workspace_id = ? AND generation = ?",
        workspaceId,
        generation);
    projectorJdbcTemplate.update(
        "DELETE FROM graph_node WHERE workspace_id = ? AND generation = ?",
        workspaceId,
        generation);
  }

  public void upsertCheckpoint(String consumer, UUID workspaceId, long outboxWatermark) {
    projectorJdbcTemplate.update(
        "INSERT INTO projection_checkpoint (consumer, workspace_id, outbox_watermark, updated_at) "
            + "VALUES (?, ?, ?, now()) "
            + "ON CONFLICT (consumer, workspace_id) DO UPDATE SET "
            + "  outbox_watermark = EXCLUDED.outbox_watermark, updated_at = EXCLUDED.updated_at",
        consumer,
        workspaceId,
        outboxWatermark);
  }

  public void upsertState(
      UUID workspaceId,
      int liveGeneration,
      OffsetDateTime lastEquivalenceCheck,
      String lastEquivalenceResult) {
    projectorJdbcTemplate.update(
        "INSERT INTO projection_state "
            + "(workspace_id, live_generation, last_equivalence_check, last_equivalence_result) "
            + "VALUES (?, ?, ?, ?) "
            + "ON CONFLICT (workspace_id) DO UPDATE SET "
            + "  live_generation = EXCLUDED.live_generation, "
            + "  last_equivalence_check = EXCLUDED.last_equivalence_check, "
            + "  last_equivalence_result = EXCLUDED.last_equivalence_result",
        workspaceId,
        liveGeneration,
        toTimestamp(lastEquivalenceCheck),
        lastEquivalenceResult);
  }

  public void insertGap(ProjectionGap gap) {
    projectorJdbcTemplate.update(
        "INSERT INTO projection_gap "
            + "(id, workspace_id, event_id, event_type, missing_fields, detected_at, status) "
            + "VALUES (?, ?, ?, ?, ?::text[], ?, ?)",
        gap.getId(),
        gap.getWorkspaceId(),
        gap.getEventId(),
        gap.getEventType(),
        textArrayLiteral(gap.getMissingFields()),
        toTimestamp(gap.getDetectedAt()),
        gap.getStatus());
  }

  public void resolveGap(UUID gapId) {
    projectorJdbcTemplate.update(
        "UPDATE projection_gap SET status = ? WHERE id = ?",
        ProjectionGap.Status.RESOLVED.name(),
        gapId);
  }

  private static Timestamp toTimestamp(OffsetDateTime value) {
    return value == null ? null : Timestamp.from(value.toInstant());
  }

  /**
   * Postgres {@code text[]} array literal ({@code {"a","b"}}, each element quoted/escaped) — plain
   * {@code JdbcTemplate} can't bind a Java {@code String[]} to a {@code text[]} column directly, so
   * (matching knowledge module's {@code PgLiterals} convention) this builds the literal and the SQL
   * above casts it with {@code ?::text[]}.
   */
  private static String textArrayLiteral(String[] values) {
    StringBuilder sb = new StringBuilder();
    sb.append('{');
    for (int i = 0; i < values.length; i++) {
      if (i > 0) sb.append(',');
      String escaped = values[i].replace("\\", "\\\\").replace("\"", "\\\"");
      sb.append('"').append(escaped).append('"');
    }
    sb.append('}');
    return sb.toString();
  }
}
