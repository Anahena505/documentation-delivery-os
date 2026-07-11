package com.d2os.governance;

/**
 * Raised when a gate decision's actor fails the non-self-review check (T014, FR-018) — mirrors
 * {@code com.d2os.knowledge.capture.D4AuthorizationException}. Mapped to 403 by {@code
 * GateExceptionHandler} (T017).
 */
public class SelfReviewNotAllowedException extends RuntimeException {
  public SelfReviewNotAllowedException(String message) {
    super(message);
  }
}
