package com.d2os.casecore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Audit row for a dual-state reconciliation that FOUND divergence between the workflow engine and
 * the domain Case state (FR-010, V12). Clean sweeps write nothing.
 */
@Entity
@Table(name = "reconciliation_run")
public class ReconciliationRun {

    public enum Divergence { MISSING_DOMAIN_TRANSITION, DEAD_LETTER_JOB, STATE_MISMATCH }

    public enum Action { REPAIRED, ESCALATED, IGNORED_TRANSIENT }

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(nullable = false)
    private String divergence;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "engine_state", nullable = false)
    private String engineState;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "domain_state", nullable = false)
    private String domainState;

    @Column(nullable = false)
    private String action;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    protected ReconciliationRun() {}

    public ReconciliationRun(UUID id, UUID workspaceId, UUID caseId, Divergence divergence,
                             String engineState, String domainState, Action action) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.caseId = caseId;
        this.divergence = divergence.name();
        this.engineState = engineState;
        this.domainState = domainState;
        this.action = action.name();
    }

    public UUID getId() { return id; }
    public UUID getCaseId() { return caseId; }
    public String getDivergence() { return divergence; }
    public String getAction() { return action; }
}
