package com.d2os.knowledge;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A deprecation flag on a past {@code operation_execution} whose injection snapshot referenced a now-
 * deprecated KnowledgeItem version (US3, FR-015, V13 {@code knowledge_affected_execution}). It records
 * that history <em>referenced</em> the retired item — it NEVER mutates the flagged execution, its
 * snapshot, or its output, so replay still reproduces the original byte-for-byte (FR-016, SC-007).
 *
 * <p>Append-only except the single {@link #markReviewed()} acknowledgement flip (V13 REVOKEs DELETE but
 * permits the {@code review_status} UPDATE). Rows are inserted by {@link DeprecationService} via an
 * insert-select over {@code knowledge_injection_snapshot}, so this entity is otherwise read/flip only.
 */
@Entity
@Table(name = "knowledge_affected_execution")
public class KnowledgeAffectedExecution {

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "knowledge_item_key", nullable = false)
    private String knowledgeItemKey;

    @Column(name = "knowledge_item_version", nullable = false)
    private int knowledgeItemVersion;

    @Column(name = "operation_execution_id", nullable = false)
    private UUID operationExecutionId;

    @Column(name = "case_instance_id", nullable = false)
    private UUID caseInstanceId;

    @Column(name = "review_status", nullable = false)
    private String reviewStatus = "OPEN";

    @Column(name = "flagged_at", nullable = false)
    private OffsetDateTime flaggedAt = OffsetDateTime.now();

    protected KnowledgeAffectedExecution() {}

    /** Acknowledge the flag: OPEN → REVIEWED. Never touches the flagged execution or its snapshot. */
    public void markReviewed() {
        this.reviewStatus = "REVIEWED";
    }

    public UUID getId() { return id; }
    public UUID getWorkspaceId() { return workspaceId; }
    public String getKnowledgeItemKey() { return knowledgeItemKey; }
    public int getKnowledgeItemVersion() { return knowledgeItemVersion; }
    public UUID getOperationExecutionId() { return operationExecutionId; }
    public UUID getCaseInstanceId() { return caseInstanceId; }
    public String getReviewStatus() { return reviewStatus; }
    public OffsetDateTime getFlaggedAt() { return flaggedAt; }
}
