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
 * A derived graph node (V28, data-model.md GraphNode, research R1-R3). Never authored directly —
 * the sole writer is the {@code d2os_projector}-bound write path (see {@link
 * com.d2os.projection.config.ProjectorDataSourceConfig} and {@link GraphWriteRepository}); this
 * entity is also used read-only via {@link GraphNodeRepository} against the app's normal
 * (SELECT-only-granted) datasource.
 *
 * <p>Identity is the natural key: {@code (workspaceId, generation, nodeType, naturalKey)} — see
 * {@link NodeEdgeMapper} for how {@link #id} is deterministically derived from those four fields
 * (research R3), so an edge referencing this node can compute the same surrogate id independently,
 * without a DB round-trip.
 */
@Entity
@Table(name = "graph_node")
public class GraphNode {

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(nullable = false)
    private int generation;

    @Column(name = "node_type", nullable = false)
    private String nodeType;

    @Column(name = "natural_key", nullable = false)
    private String naturalKey;

    @Column(nullable = false)
    private String label;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String attributes;

    @Column(name = "source_kind", nullable = false)
    private String sourceKind;

    @Column(name = "source_ref", nullable = false)
    private String sourceRef;

    @Column(name = "projected_at", nullable = false)
    private OffsetDateTime projectedAt = OffsetDateTime.now();

    protected GraphNode() {}

    public GraphNode(UUID id, UUID workspaceId, int generation, String nodeType, String naturalKey,
                      String label, String attributes, String sourceKind, String sourceRef,
                      OffsetDateTime projectedAt) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.generation = generation;
        this.nodeType = nodeType;
        this.naturalKey = naturalKey;
        this.label = label;
        this.attributes = attributes;
        this.sourceKind = sourceKind;
        this.sourceRef = sourceRef;
        this.projectedAt = projectedAt != null ? projectedAt : OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getWorkspaceId() { return workspaceId; }
    public int getGeneration() { return generation; }
    public String getNodeType() { return nodeType; }
    public String getNaturalKey() { return naturalKey; }
    public String getLabel() { return label; }
    public String getAttributes() { return attributes; }
    public String getSourceKind() { return sourceKind; }
    public String getSourceRef() { return sourceRef; }
    public OffsetDateTime getProjectedAt() { return projectedAt; }
}
