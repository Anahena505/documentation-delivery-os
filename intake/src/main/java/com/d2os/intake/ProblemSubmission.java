package com.d2os.intake;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A structured problem submission (E1.3). {@code formData} is stored as opaque JSON and is always
 * treated as DATA, never as instructions (AD-12).
 */
@Entity
@Table(name = "problem_submission")
public class ProblemSubmission {

    public enum Status { received, classified, confirmed }

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "form_data", nullable = false)
    private String formData;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sensitivity_tags", nullable = false)
    private String sensitivityTags = "[]";

    @Column(name = "classification_case_type")
    private String classificationCaseType;

    @Column(name = "classification_confidence")
    private BigDecimal classificationConfidence;

    @Column(name = "classification_needs_confirm", nullable = false)
    private boolean classificationNeedsConfirm = true;

    @Column(name = "classification_confirmed_by")
    private String classificationConfirmedBy;

    @Column(nullable = false)
    private String status = Status.received.name();

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    protected ProblemSubmission() {}

    public ProblemSubmission(UUID id, UUID workspaceId, String formData, String sensitivityTags,
                             String createdBy) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.formData = formData;
        this.sensitivityTags = sensitivityTags == null ? "[]" : sensitivityTags;
        this.createdBy = createdBy;
    }

    /** Apply a classification result (transition received -> classified). */
    public void applyClassification(ClassificationResult result) {
        this.classificationCaseType = result.caseType();
        this.classificationConfidence = BigDecimal.valueOf(result.confidence());
        this.classificationNeedsConfirm = result.needsHumanConfirm();
        this.status = Status.classified.name();
    }

    /** Record human confirmation (transition classified -> confirmed). FR-002. */
    public void confirm(String confirmedBy, String confirmedCaseType) {
        this.classificationCaseType = confirmedCaseType;
        this.classificationConfirmedBy = confirmedBy;
        this.classificationNeedsConfirm = false;
        this.status = Status.confirmed.name();
    }

    public UUID getId() { return id; }
    public UUID getWorkspaceId() { return workspaceId; }
    public String getFormData() { return formData; }
    public String getSensitivityTags() { return sensitivityTags; }
    public String getClassificationCaseType() { return classificationCaseType; }
    public BigDecimal getClassificationConfidence() { return classificationConfidence; }
    public boolean isClassificationNeedsConfirm() { return classificationNeedsConfirm; }
    public String getClassificationConfirmedBy() { return classificationConfirmedBy; }
    public String getStatus() { return status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
