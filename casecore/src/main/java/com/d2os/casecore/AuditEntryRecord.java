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

    // 008 US5 (T049/T050/T051, data-model.md §2): per-user accountability on trust-sensitive
    // decisions. Both NULLABLE and additive — pre-existing rows and every default-mode (non-OIDC)
    // decision leave them null. Distinct from the generic {@code actor} column above, which records
    // the acting component/persona; these record the AUTHENTICATED individual (IdP `sub`) and the
    // role they were authorized under (validated to be one they hold, in the service layer).
    @Column(name = "actor_user_id")
    private String actorUserId;

    @Column(name = "actor_role")
    private String actorRole;

    protected AuditEntryRecord() {}

    public AuditEntryRecord(UUID id, UUID workspaceId, String subjectType, UUID subjectId,
                            String action, String actor, String details) {
        this(id, workspaceId, subjectType, subjectId, action, actor, null, null, details);
    }

    /**
     * Actor-stamped constructor (008 US5, T051). {@code actorUserId}/{@code actorRole} are null in the
     * default (workspace-scoping) posture, in which case this row is byte-identical, under {@link
     * com.d2os.casecore.audit.AuditChainCanonicalizer}, to one built by the legacy 7-arg constructor.
     */
    public AuditEntryRecord(UUID id, UUID workspaceId, String subjectType, UUID subjectId,
                            String action, String actor, String actorUserId, String actorRole,
                            String details) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.action = action;
        this.actor = actor;
        this.actorUserId = actorUserId;
        this.actorRole = actorRole;
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
    public String getActorUserId() { return actorUserId; }
    public String getActorRole() { return actorRole; }
}
