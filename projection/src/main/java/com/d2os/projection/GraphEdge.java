package com.d2os.projection;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A derived graph edge (V28, data-model.md GraphEdge, research R1-R3/R7). Never authored
 * directly — see {@link GraphNode}'s javadoc for the sole-writer contract this shares.
 *
 * <p>Identity is {@code (workspaceId, generation, edgeType, fromNode, toNode, sourceRef)} — {@link
 * #id} is deterministically derived from those six fields by {@link NodeEdgeMapper} (research R3),
 * matching the {@code uq_graph_edge_identity} constraint so replaying the same source fact is a
 * no-op upsert (FR-012).
 */
@Entity
@Table(name = "graph_edge")
public class GraphEdge {

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(nullable = false)
    private int generation;

    @Column(name = "edge_type", nullable = false)
    private String edgeType;

    @Column(name = "from_node", nullable = false)
    private UUID fromNode;

    @Column(name = "to_node", nullable = false)
    private UUID toNode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String attributes;

    @Column(name = "source_kind", nullable = false)
    private String sourceKind;

    @Column(name = "source_ref", nullable = false)
    private String sourceRef;

    @Column(name = "projected_at", nullable = false)
    private OffsetDateTime projectedAt = OffsetDateTime.now();

    protected GraphEdge() {}

    public GraphEdge(UUID id, UUID workspaceId, int generation, String edgeType, UUID fromNode,
                      UUID toNode, String attributes, String sourceKind, String sourceRef,
                      OffsetDateTime projectedAt) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.generation = generation;
        this.edgeType = edgeType;
        this.fromNode = fromNode;
        this.toNode = toNode;
        this.attributes = attributes;
        this.sourceKind = sourceKind;
        this.sourceRef = sourceRef;
        this.projectedAt = projectedAt != null ? projectedAt : OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getWorkspaceId() { return workspaceId; }
    public int getGeneration() { return generation; }
    public String getEdgeType() { return edgeType; }
    public UUID getFromNode() { return fromNode; }
    public UUID getToNode() { return toNode; }
    public String getAttributes() { return attributes; }
    public String getSourceKind() { return sourceKind; }
    public String getSourceRef() { return sourceRef; }
    public OffsetDateTime getProjectedAt() { return projectedAt; }
}
