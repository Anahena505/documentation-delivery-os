package com.d2os.persona.consistency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One cross-output discrepancy from the Consistency-Check subprocess (US3, V11). DETERMINISTIC
 * findings hard-block delivery (FR-007); SEMANTIC findings are advisory and escalate (FR-008).
 * Anchored to operation_execution ids because the check runs before artifacts are materialized.
 */
@Entity
@Table(name = "consistency_finding")
public class ConsistencyFinding {

    public enum Tier { DETERMINISTIC, SEMANTIC }

    public enum Kind { DANGLING_REFERENCE, ATTRIBUTE_CONTRADICTION, SEMANTIC_INCOHERENCE }

    public enum Status { OPEN, RESOLVED, WAIVED }

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(nullable = false)
    private String tier;

    @Column(nullable = false)
    private String kind;

    @Column(name = "subject_ref", nullable = false)
    private String subjectRef;

    @Column(name = "source_operation_id", nullable = false)
    private UUID sourceOperationId;

    @Column(name = "target_operation_id")
    private UUID targetOperationId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String detail = "{}";

    @Column(nullable = false)
    private String status = Status.OPEN.name();

    @Column(name = "resolved_by")
    private String resolvedBy;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    protected ConsistencyFinding() {}

    public ConsistencyFinding(UUID id, UUID workspaceId, UUID caseId, Tier tier, Kind kind,
                              String subjectRef, UUID sourceOperationId, UUID targetOperationId, String detail) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.caseId = caseId;
        this.tier = tier.name();
        this.kind = kind.name();
        this.subjectRef = subjectRef;
        this.sourceOperationId = sourceOperationId;
        this.targetOperationId = targetOperationId;
        this.detail = detail == null ? "{}" : detail;
    }

    public void resolve(Status status, String actor) {
        this.status = status.name();
        this.resolvedBy = actor;
        this.resolvedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getWorkspaceId() { return workspaceId; }
    public UUID getCaseId() { return caseId; }
    public String getTier() { return tier; }
    public String getKind() { return kind; }
    public String getSubjectRef() { return subjectRef; }
    public UUID getSourceOperationId() { return sourceOperationId; }
    public UUID getTargetOperationId() { return targetOperationId; }
    public String getStatus() { return status; }
    public String getDetail() { return detail; }
}
