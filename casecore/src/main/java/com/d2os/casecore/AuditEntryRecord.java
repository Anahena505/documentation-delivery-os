package com.d2os.casecore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Tamper-evident audit row (Principle V). Grants are append-only (T6-a, enforced at DB/role level). */
@Entity
@Table(name = "audit_entry")
public class AuditEntryRecord {

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "subject_type", nullable = false)
    private String subjectType;

    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    @Column(nullable = false)
    private String action;

    @Column(nullable = false)
    private String actor;

    @Column(name = "tx_time", nullable = false)
    private OffsetDateTime txTime = OffsetDateTime.now();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String details;

    protected AuditEntryRecord() {}

    public AuditEntryRecord(UUID id, UUID workspaceId, String subjectType, UUID subjectId,
                            String action, String actor, String details) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.action = action;
        this.actor = actor;
        this.details = details;
    }

    public UUID getId() { return id; }
    public UUID getWorkspaceId() { return workspaceId; }
    public String getSubjectType() { return subjectType; }
    public UUID getSubjectId() { return subjectId; }
    public String getAction() { return action; }
    public String getActor() { return actor; }
    public OffsetDateTime getTxTime() { return txTime; }
    public String getDetails() { return details; }
}
