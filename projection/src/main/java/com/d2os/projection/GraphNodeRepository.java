package com.d2os.projection;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * READ-side repository (T007) — runs against the app's normal ({@code d2os_app}, SELECT-only on
 * this table per V28) datasource/EntityManagerFactory, not the projector's write datasource. Every
 * finder is generation-scoped so callers (T016+, a later phase) can query exactly the live
 * generation via {@link ProjectionStateRepository}.
 */
public interface GraphNodeRepository extends JpaRepository<GraphNode, UUID> {

  Optional<GraphNode> findByWorkspaceIdAndGenerationAndNodeTypeAndNaturalKey(
      UUID workspaceId, int generation, String nodeType, String naturalKey);

  List<GraphNode> findByWorkspaceIdAndGeneration(UUID workspaceId, int generation);

  List<GraphNode> findByWorkspaceIdAndGenerationAndNodeType(
      UUID workspaceId, int generation, String nodeType);

  /**
   * T017 &mdash; the explicit {@code workspace_id = ?} predicate {@code TraceabilityController}'s
   * {@code GET /graph/nodes/{nodeId}} uses in addition to RLS (defense in depth, Principle IV): a
   * by-id lookup alone would already be RLS-scoped, but this makes the workspace boundary a literal
   * part of the query too, not just an ambient session setting.
   */
  Optional<GraphNode> findByIdAndWorkspaceId(UUID id, UUID workspaceId);
}
