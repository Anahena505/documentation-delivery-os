package com.d2os.governance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One runtime Review/Approval gate occurrence, bridged to the engine's user task (V20 {@code
 * gate_instance}, data-model.md, research R1). {@code inputsRef} captures the exact information the
 * decision is based on (artifact revisions, rubric scores, delta report id) at open time — the
 * reviewer view ({@code GET /gates/{id}}, Phase 3 T017) resolves this back for display.
 *
 * <p>State machine transitions are validated by {@link GateStatus#canTransitionTo}; every transition
 * writes a Decision + AuditEntry in the same transaction as the state change (Principle V) — that
 * write happens in {@code GateService}/{@code ReopenService} (later phases), not here. This entity
 * only guards the transition itself.
 */
@Entity
@Table(name = "gate_instance")
public class GateInstance {

    /** {@code gate_type} — REVIEW (advisory) or APPROVAL (binding) gate. */
    public enum GateType { REVIEW, APPROVAL }

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "case_instance_id", nullable = false)
    private UUID caseInstanceId;

    @Column(name = "gate_type", nullable = false)
    private String gateType;

    @Column(name = "gate_definition_key", nullable = false)
    private String gateDefinitionKey;

    @Column(name = "gate_definition_version", nullable = false)
    private int gateDefinitionVersion;

    @Column(name = "subject_artifact_revision_id")
    private UUID subjectArtifactRevisionId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "inputs_ref", nullable = false)
    private String inputsRef;

    @Column(name = "escalation_policy_key")
    private String escalationPolicyKey;

    @Column(name = "escalation_policy_version")
    private Integer escalationPolicyVersion;

    @Column(nullable = false)
    private String status = GateStatus.OPEN.name();

    @Column(name = "decision_id")
    private UUID decisionId;

    @Column(name = "reviewer_comments")
    private String reviewerComments;

    @Column(name = "delta_report_id")
    private UUID deltaReportId;

    @Column(name = "engine_task_id")
    private String engineTaskId;

    @Column(name = "opened_at", nullable = false)
    private OffsetDateTime openedAt = OffsetDateTime.now();

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    @Column(name = "reopened_at")
    private OffsetDateTime reopenedAt;

    protected GateInstance() {}

    public GateInstance(UUID id, UUID workspaceId, UUID caseInstanceId, GateType gateType,
                        String gateDefinitionKey, int gateDefinitionVersion,
                        UUID subjectArtifactRevisionId, String inputsRef,
                        String escalationPolicyKey, Integer escalationPolicyVersion,
                        String engineTaskId) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.caseInstanceId = caseInstanceId;
        this.gateType = gateType.name();
        this.gateDefinitionKey = gateDefinitionKey;
        this.gateDefinitionVersion = gateDefinitionVersion;
        this.subjectArtifactRevisionId = subjectArtifactRevisionId;
        this.inputsRef = inputsRef;
        this.escalationPolicyKey = escalationPolicyKey;
        this.escalationPolicyVersion = escalationPolicyVersion;
        this.engineTaskId = engineTaskId;
    }

    public GateStatus status() {
        return GateStatus.valueOf(status);
    }

    /** Transition to {@code target}, rejecting any move {@link GateStatus} forbids. */
    public void transitionTo(GateStatus target) {
        GateStatus current = status();
        if (!current.canTransitionTo(target)) {
            throw new IllegalGateTransitionException(current, target);
        }
        this.status = target.name();
    }

    public void correlateEngineTask(String engineTaskId) {
        this.engineTaskId = engineTaskId;
    }

    public void recordDecision(UUID decisionId, OffsetDateTime decidedAt) {
        this.decisionId = decisionId;
        this.decidedAt = decidedAt;
    }

    public void recordReviewerComments(String reviewerComments) {
        this.reviewerComments = reviewerComments;
    }

    public void attachDeltaReport(UUID deltaReportId) {
        this.deltaReportId = deltaReportId;
    }

    public void recordReopened(OffsetDateTime reopenedAt) {
        this.reopenedAt = reopenedAt;
    }

    public UUID getId() { return id; }
    public UUID getWorkspaceId() { return workspaceId; }
    public UUID getCaseInstanceId() { return caseInstanceId; }
    public GateType getGateType() { return GateType.valueOf(gateType); }
    public String getGateDefinitionKey() { return gateDefinitionKey; }
    public int getGateDefinitionVersion() { return gateDefinitionVersion; }
    public UUID getSubjectArtifactRevisionId() { return subjectArtifactRevisionId; }
    public String getInputsRef() { return inputsRef; }
    public String getEscalationPolicyKey() { return escalationPolicyKey; }
    public Integer getEscalationPolicyVersion() { return escalationPolicyVersion; }
    public String getStatus() { return status; }
    public UUID getDecisionId() { return decisionId; }
    public String getReviewerComments() { return reviewerComments; }
    public UUID getDeltaReportId() { return deltaReportId; }
    public String getEngineTaskId() { return engineTaskId; }
    public OffsetDateTime getOpenedAt() { return openedAt; }
    public OffsetDateTime getDecidedAt() { return decidedAt; }
    public OffsetDateTime getReopenedAt() { return reopenedAt; }
}
