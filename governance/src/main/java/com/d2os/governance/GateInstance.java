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

    /**
     * {@code subject_type} (V26, polymorphic gate subject, research R3, T4-b). {@code
     * ARTIFACT_REVISION} is the original Phase 5 subject kind (an {@code ArtifactRevision});
     * {@code DEFINITION_VERSION} is Phase 6's addition — a {@code DefinitionAsset} version under
     * studio publish review. Matches this package's existing enum style ({@link GateStatus}).
     */
    public enum GateSubjectType { ARTIFACT_REVISION, DEFINITION_VERSION }

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    /**
     * @deprecated-adjacent note (not deprecated, just relaxed): NOT NULL until V27 (tasks.md
     *     T013/T017). The studio's {@code DEFINITION_VERSION}-subject publish-review gates have no
     *     owning Case — they review a catalog definition version, never anything running inside a
     *     workflow instance — so this column is nullable for those rows. Every
     *     {@code ARTIFACT_REVISION}-subject gate (the only kind before Phase 6) still always sets
     *     a real case id. Same column-only, FK-preserving relaxation casecore's V19 ({@code
     *     decision.case_instance_id}) already established for the identical problem shape.
     */
    @Column(name = "case_instance_id")
    private UUID caseInstanceId;

    @Column(name = "gate_type", nullable = false)
    private String gateType;

    @Column(name = "gate_definition_key", nullable = false)
    private String gateDefinitionKey;

    @Column(name = "gate_definition_version", nullable = false)
    private int gateDefinitionVersion;

    /**
     * @deprecated V26 read alias, kept for backward compatibility with existing callers
     *     (e.g. {@code GateTaskBridge}) and rows written before the polymorphic subject existed.
     *     New code should read {@link #subjectType}/{@link #subjectId} instead; this field is only
     *     still WRITTEN for {@code ARTIFACT_REVISION}-subject gates so it stays a faithful alias,
     *     never a second source of truth.
     */
    @Deprecated
    @Column(name = "subject_artifact_revision_id")
    private UUID subjectArtifactRevisionId;

    /** {@code subject_type} (V26) — see {@link GateSubjectType}. Defaults to ARTIFACT_REVISION at
     * the DB level (matching every gate opened before this column existed). */
    @Column(name = "subject_type", nullable = false)
    private String subjectType = GateSubjectType.ARTIFACT_REVISION.name();

    /** {@code subject_id} (V26) — the polymorphic subject's row id (an ArtifactRevision id or a
     * DefinitionAsset id, per {@link #subjectType}). Nullable: mirrors {@code
     * subjectArtifactRevisionId}'s existing nullability (a gate can fail open with no resolvable
     * subject — see {@code GateTaskBridge#resolveSubjectArtifactRevision}). */
    @Column(name = "subject_id")
    private UUID subjectId;

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

    /**
     * Original Phase 5 constructor (ARTIFACT_REVISION subject only) — kept unchanged so every
     * existing caller (e.g. {@code GateTaskBridge}) compiles and behaves exactly as before V26.
     * Delegates to the polymorphic constructor below with {@code subjectType=ARTIFACT_REVISION}.
     */
    public GateInstance(UUID id, UUID workspaceId, UUID caseInstanceId, GateType gateType,
                        String gateDefinitionKey, int gateDefinitionVersion,
                        UUID subjectArtifactRevisionId, String inputsRef,
                        String escalationPolicyKey, Integer escalationPolicyVersion,
                        String engineTaskId) {
        this(id, workspaceId, caseInstanceId, gateType, gateDefinitionKey, gateDefinitionVersion,
                GateSubjectType.ARTIFACT_REVISION, subjectArtifactRevisionId, inputsRef,
                escalationPolicyKey, escalationPolicyVersion, engineTaskId);
    }

    /**
     * Polymorphic-subject constructor (V26, research R3, T4-b). {@code subjectType}/{@code
     * subjectId} are always set; the deprecated {@code subjectArtifactRevisionId} alias is ALSO
     * set when {@code subjectType == ARTIFACT_REVISION} (kept a faithful alias, never a second
     * source of truth for a DEFINITION_VERSION subject).
     */
    public GateInstance(UUID id, UUID workspaceId, UUID caseInstanceId, GateType gateType,
                        String gateDefinitionKey, int gateDefinitionVersion,
                        GateSubjectType subjectType, UUID subjectId, String inputsRef,
                        String escalationPolicyKey, Integer escalationPolicyVersion,
                        String engineTaskId) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.caseInstanceId = caseInstanceId;
        this.gateType = gateType.name();
        this.gateDefinitionKey = gateDefinitionKey;
        this.gateDefinitionVersion = gateDefinitionVersion;
        this.subjectType = subjectType.name();
        this.subjectId = subjectId;
        this.subjectArtifactRevisionId = subjectType == GateSubjectType.ARTIFACT_REVISION ? subjectId : null;
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
    /** @deprecated read {@link #getSubjectType()}/{@link #getSubjectId()} instead (V26). */
    @Deprecated
    public UUID getSubjectArtifactRevisionId() { return subjectArtifactRevisionId; }
    public GateSubjectType getSubjectType() { return GateSubjectType.valueOf(subjectType); }
    public UUID getSubjectId() { return subjectId; }
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
