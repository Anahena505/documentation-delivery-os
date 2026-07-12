package com.d2os.governance.reopen;

/**
 * {@code ReopenService.reopen} refused (T027, FR-007/008) — either no {@link ImpactAssessment}
 * exists yet for this gate/upstream-revision pair, or the candidate is transitive ({@code depth>1},
 * Q3/AD-5: never auto- or manually-reopenable through this path, only flaggable for {@code
 * MANUAL_REVIEW}). Mapped to 409 by {@code GateExceptionHandler}.
 */
public class ReopenNotAllowedException extends RuntimeException {
  public ReopenNotAllowedException(String message) {
    super(message);
  }
}
