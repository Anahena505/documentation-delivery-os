package com.d2os.intake.dto;

import com.d2os.intake.ProblemSubmission;
import java.util.UUID;

/**
 * API view of a submission's Phase 4 case-type classification proposal + confirmation state
 * (contracts/api.yaml {@code #/CaseTypeClassification}, T011, US1). Surfaces UNDETERMINED as an
 * ordinary proposal value rather than an error — ambiguity is a UX state, not a special path
 * (research R5).
 */
public record CaseTypeClassificationResponse(
    UUID submissionId,
    String proposedCaseType,
    String confirmedCaseType,
    String classificationStatus,
    boolean overridden,
    UUID decisionId) {
  public static CaseTypeClassificationResponse from(ProblemSubmission s, UUID decisionId) {
    return new CaseTypeClassificationResponse(
        s.getId(),
        s.getProposedCaseType(),
        s.getConfirmedCaseType(),
        s.getClassificationStatus(),
        s.isClassificationOverridden(),
        decisionId);
  }
}
