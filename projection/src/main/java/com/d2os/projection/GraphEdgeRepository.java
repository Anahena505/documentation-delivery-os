package com.d2os.projection;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * READ-side repository (T007), same convention as {@link GraphNodeRepository}: app datasource,
 * generation-filtered finders both traversal directions (research R7 — both-direction indexes
 * back these two queries directly).
 */
public interface GraphEdgeRepository extends JpaRepository<GraphEdge, UUID> {

    List<GraphEdge> findByWorkspaceIdAndGenerationAndFromNodeAndEdgeType(
            UUID workspaceId, int generation, UUID fromNode, String edgeType);

    List<GraphEdge> findByWorkspaceIdAndGenerationAndToNodeAndEdgeType(
            UUID workspaceId, int generation, UUID toNode, String edgeType);

    List<GraphEdge> findByWorkspaceIdAndGeneration(UUID workspaceId, int generation);
}
