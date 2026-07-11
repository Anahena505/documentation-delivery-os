package com.d2os.projection;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

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

    List<GraphNode> findByWorkspaceIdAndGenerationAndNodeType(UUID workspaceId, int generation, String nodeType);
}
