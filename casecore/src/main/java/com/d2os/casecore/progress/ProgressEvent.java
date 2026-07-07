package com.d2os.casecore.progress;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One append-only user-visible liveness event for a Case (FR-011, NFR-3, V12). The monotonic
 * {@code id} is the cursor the {@code /cases/{id}/progress} long-poll pages on. Append-only at the
 * DB grant level (T6-a): the app role may INSERT/SELECT but never UPDATE/DELETE.
 */
@Entity
@Table(name = "progress_event")
public class ProgressEvent {

    public enum Kind {
        STEP_STARTED, STEP_COMPLETED, VALIDATION_ATTEMPT, BRANCH_FORKED, BRANCH_JOINED,
        HEARTBEAT, ESCALATED, SUSPENDED, RECONCILED, DELIVERED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(nullable = false)
    private String kind;

    @Column(name = "activity_id")
    private String activityId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column
    private String detail;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    protected ProgressEvent() {}

    public ProgressEvent(UUID workspaceId, UUID caseId, Kind kind, String activityId, String detail) {
        this.workspaceId = workspaceId;
        this.caseId = caseId;
        this.kind = kind.name();
        this.activityId = activityId;
        this.detail = detail;
    }

    public Long getId() { return id; }
    public UUID getWorkspaceId() { return workspaceId; }
    public UUID getCaseId() { return caseId; }
    public String getKind() { return kind; }
    public String getActivityId() { return activityId; }
    public String getDetail() { return detail; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
