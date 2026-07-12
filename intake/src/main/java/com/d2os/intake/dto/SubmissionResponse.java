package com.d2os.intake.dto;

import com.d2os.intake.ProblemSubmission;
import java.math.BigDecimal;
import java.util.UUID;

/** API view of a submission and its classification (contracts/api.yaml #/ProblemSubmission). */
public record SubmissionResponse(
    UUID id,
    String status,
    String caseType,
    BigDecimal confidence,
    boolean needsHumanConfirm,
    String confirmedBy) {
  public static SubmissionResponse from(ProblemSubmission s) {
    return new SubmissionResponse(
        s.getId(),
        s.getStatus(),
        s.getClassificationCaseType(),
        s.getClassificationConfidence(),
        s.isClassificationNeedsConfirm(),
        s.getClassificationConfirmedBy());
  }
}
