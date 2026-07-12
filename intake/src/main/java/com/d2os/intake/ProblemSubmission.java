package com.d2os.intake;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A structured problem submission (E1.3). {@code formData} is stored as opaque JSON and is always
 * treated as DATA, never as instructions (AD-12).
 */
@Entity
@Table(name = "problem_submission")
public class ProblemSubmission {

  public enum Status {
    received,
    classified,
    confirmed
  }

  @Id private UUID id;

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

  // Phase 4 (V18, research R5, FR-019, T005/T010/T011). Distinct from the Phase 1-3 `status` /
  // `classificationCaseType` fields above: this is the real 3-way
  // (INITIATION/ASSESSMENT/ENHANCEMENT)
  // routing proposal + human confirm, with UNDETERMINED as an explicit possible proposal.
  @Column(name = "proposed_case_type")
  private String proposedCaseType;

  @Column(name = "confirmed_case_type")
  private String confirmedCaseType;

  @Column(name = "classification_status", nullable = false)
  private String classificationStatus = "PROPOSED";

  @Column(name = "classification_overridden", nullable = false)
  private boolean classificationOverridden = false;

  @Column(nullable = false)
  private String status = Status.received.name();

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt = OffsetDateTime.now();

  @Column(name = "created_by", nullable = false)
  private String createdBy;

  protected ProblemSubmission() {}

  public ProblemSubmission(
      UUID id, UUID workspaceId, String formData, String sensitivityTags, String createdBy) {
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
    // Phase 4 (T012): keep the new classification_status columns in sync so CaseService's
    // classification-confirmed gate (`classification_status = CONFIRMED`, reading
    // `confirmed_case_type` as the case-type key) admits submissions confirmed via this legacy
    // Phase 1-3 endpoint too — one authoritative source for Case-creation eligibility, whichever
    // confirm endpoint a caller used.
    this.confirmedCaseType =
        confirmedCaseType == null ? null : confirmedCaseType.toUpperCase(Locale.ROOT);
    this.classificationOverridden =
        this.proposedCaseType != null && !this.proposedCaseType.equalsIgnoreCase(confirmedCaseType);
    this.classificationStatus = "CONFIRMED";
  }

  /** Phase 4 (T010): record the case-type-classification DMN's advisory proposal (research R5). */
  public void applyCaseTypeProposal(String proposedCaseType) {
    this.proposedCaseType = proposedCaseType;
    // Re-classification before confirm is allowed to update the proposal; once CONFIRMED the
    // proposal is frozen (never overwritten — see confirmCaseType's invariant note).
    if (!"CONFIRMED".equals(this.classificationStatus)) {
      this.classificationStatus = "PROPOSED";
    }
  }

  /**
   * Phase 4 (T011, US1): human confirm/override of the proposed case type. Confirming with a type
   * different from the proposal records an override; the original proposal column is never
   * overwritten (data-model.md invariant). Throws {@link IllegalStateException} if already
   * CONFIRMED — the caller (SubmissionService) maps that to HTTP 409.
   */
  public void confirmCaseType(String caseType) {
    if ("CONFIRMED".equals(this.classificationStatus)) {
      throw new IllegalStateException("submission " + id + " case type is already confirmed");
    }
    this.classificationOverridden =
        this.proposedCaseType == null || !this.proposedCaseType.equalsIgnoreCase(caseType);
    this.confirmedCaseType = caseType.toUpperCase(Locale.ROOT);
    this.classificationStatus = "CONFIRMED";
  }

  public UUID getId() {
    return id;
  }

  public UUID getWorkspaceId() {
    return workspaceId;
  }

  public String getFormData() {
    return formData;
  }

  public String getSensitivityTags() {
    return sensitivityTags;
  }

  public String getClassificationCaseType() {
    return classificationCaseType;
  }

  public BigDecimal getClassificationConfidence() {
    return classificationConfidence;
  }

  public boolean isClassificationNeedsConfirm() {
    return classificationNeedsConfirm;
  }

  public String getClassificationConfirmedBy() {
    return classificationConfirmedBy;
  }

  public String getProposedCaseType() {
    return proposedCaseType;
  }

  public String getConfirmedCaseType() {
    return confirmedCaseType;
  }

  public String getClassificationStatus() {
    return classificationStatus;
  }

  public boolean isClassificationOverridden() {
    return classificationOverridden;
  }

  public String getStatus() {
    return status;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }
}
