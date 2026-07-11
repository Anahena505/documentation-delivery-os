package com.d2os.intake;

/**
 * Phase 5 (T024, US3, research R4, FR-010, contracts {@code /case-type/confirm} 422): an
 * ENHANCEMENT confirm whose target Feature has no identifiable {@code featureId} in the
 * submission's formData, or whose named Feature has no Delivered baseline Case. Mapped to HTTP 422
 * by {@link SubmissionController}.
 */
public class NoBaselineException extends RuntimeException {

  public NoBaselineException(String message) {
    super(message);
  }
}
