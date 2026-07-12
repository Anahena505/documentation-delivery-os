package com.d2os.intake;

/**
 * Phase 4 (T011, US1): the submission's case-type classification is already CONFIRMED — a second
 * {@code POST /submissions/{id}/case-type/confirm} is rejected rather than silently re-deciding
 * (contracts/api.yaml {@code /case-type/confirm} 409). Mapped by {@link SubmissionController}.
 */
public class AlreadyConfirmedException extends RuntimeException {
  public AlreadyConfirmedException(String message) {
    super(message);
  }
}
