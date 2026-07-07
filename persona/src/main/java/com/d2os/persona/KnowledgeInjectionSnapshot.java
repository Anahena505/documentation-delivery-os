package com.d2os.persona;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One injected KnowledgeItem's provenance for a single OperationExecution (T014, V14, FR-006, research
 * R5/R9). Written by {@link OperationExecutionRecorder} in the SAME transaction as its operation row, so
 * an execution never exists without its full knowledge provenance. Immutable / append-only.
 *
 * <p>{@code knowledgeItemId/key/version} are a SOFT reference (no FK): the snapshot must stay valid and
 * byte-identical even after the item is deprecated or superseded, and {@code knowledge_item} is
 * partitioned in another module (V14). {@code position} orders the item within the envelope's knowledge
 * slot so replay (T016) reconstructs the exact injected context.
 */
@Entity
@Table(name = "knowledge_injection_snapshot")
public class KnowledgeInjectionSnapshot {

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "operation_execution_id", nullable = false)
    private UUID operationExecutionId;

    @Column(name = "knowledge_item_id", nullable = false)
    private UUID knowledgeItemId;

    @Column(name = "knowledge_item_key", nullable = false)
    private String knowledgeItemKey;

    @Column(name = "knowledge_item_version", nullable = false)
    private int knowledgeItemVersion;

    @Column(name = "content_hash", nullable = false)
    private String contentHash;

    @Column(nullable = false)
    private int position;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    protected KnowledgeInjectionSnapshot() {}

    public KnowledgeInjectionSnapshot(UUID id, UUID workspaceId, UUID operationExecutionId,
                                      UUID knowledgeItemId, String knowledgeItemKey, int knowledgeItemVersion,
                                      String contentHash, int position) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.operationExecutionId = operationExecutionId;
        this.knowledgeItemId = knowledgeItemId;
        this.knowledgeItemKey = knowledgeItemKey;
        this.knowledgeItemVersion = knowledgeItemVersion;
        this.contentHash = contentHash;
        this.position = position;
    }

    public UUID getId() { return id; }
    public UUID getWorkspaceId() { return workspaceId; }
    public UUID getOperationExecutionId() { return operationExecutionId; }
    public UUID getKnowledgeItemId() { return knowledgeItemId; }
    public String getKnowledgeItemKey() { return knowledgeItemKey; }
    public int getKnowledgeItemVersion() { return knowledgeItemVersion; }
    public String getContentHash() { return contentHash; }
    public int getPosition() { return position; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
