package com.d2os.projection;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * READ-side repository (T007), same convention as {@link GraphNodeRepository}: app datasource,
 * generation-filtered finders both traversal directions (research R7 — both-direction indexes back
 * these two queries directly).
 */
public interface GraphEdgeRepository extends JpaRepository<GraphEdge, UUID> {

  List<GraphEdge> findByWorkspaceIdAndGenerationAndFromNodeAndEdgeType(
      UUID workspaceId, int generation, UUID fromNode, String edgeType);

  List<GraphEdge> findByWorkspaceIdAndGenerationAndToNodeAndEdgeType(
      UUID workspaceId, int generation, UUID toNode, String edgeType);

  List<GraphEdge> findByWorkspaceIdAndGeneration(UUID workspaceId, int generation);

  /**
   * T017 &mdash; all outgoing edges of a node regardless of type, for the node-detail adjacency
   * list.
   */
  List<GraphEdge> findByWorkspaceIdAndGenerationAndFromNode(
      UUID workspaceId, int generation, UUID fromNode);

  /**
   * T017 &mdash; all incoming edges of a node regardless of type, for the node-detail adjacency
   * list.
   */
  List<GraphEdge> findByWorkspaceIdAndGenerationAndToNode(
      UUID workspaceId, int generation, UUID toNode);
}
