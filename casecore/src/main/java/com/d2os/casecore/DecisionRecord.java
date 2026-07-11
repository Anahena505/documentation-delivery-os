package com.d2os.casecore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A governance decision (V4 {@code decision}, Principle V). One row per gated human decision — e.g. the
 * D4 promotion gate on a knowledge capture candidate (Phase 3, US2). {@code inputsRef} points at the
 * decided-upon subject (the candidate id) so the decision is auditable and reproducible. Append-only in
 * spirit (never mutated once recorded).
 *
 * <p>{@code caseInstanceId} is nullable as of V19 (Phase 4, T011, US1): the case-type confirm/override
 * decision (D4) is recorded at {@code POST /submissions/{id}/case-type/confirm} time, which is
 * necessarily BEFORE a Case exists — Case creation is gated on that very confirmation (T012). Every
 * other decision-writing call site continues to pass a real case id.
 */
@Entity
@Table(name = "decision")
public class DecisionRecord {

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "case_instance_id")
    private UUID caseInstanceId;

    @Column(name = "decision_type", nullable = false)
    private String decisionType;

    @Column(name = "decided_by", nullable = false)
    private String decidedBy;

    @Column
    private String rationale;

    @Column(name = "inputs_ref")
    private String inputsRef;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    protected DecisionRecord() {}

    public DecisionRecord(UUID id, UUID workspaceId, UUID caseInstanceId, String decisionType,
                          String decidedBy, String rationale, String inputsRef) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.caseInstanceId = caseInstanceId;
        this.decisionType = decisionType;
        this.decidedBy = decidedBy;
        this.rationale = rationale;
        this.inputsRef = inputsRef;
    }

    public UUID getId() { return id; }
    public UUID getWorkspaceId() { return workspaceId; }
    public UUID getCaseInstanceId() { return caseInstanceId; }
    public String getDecisionType() { return decisionType; }
    public String getDecidedBy() { return decidedBy; }
    public String getRationale() { return rationale; }
    public String getInputsRef() { return inputsRef; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
