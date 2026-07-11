package com.d2os.casecore;

import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps case-domain exceptions to HTTP status codes (contracts/api.yaml). */
@RestControllerAdvice
public class CaseExceptionHandler {

  @ExceptionHandler(CaseConflictException.class)
  public ProblemDetail onConflict(CaseConflictException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
  }

  /**
   * T028 (US4, FR-012/013, contracts `MutatingConflict`): the Q2 guard's optimistic acquire found
   * the Feature's mutating-case slot already occupied (or the version stale). {@code featureId} and
   * {@code activeCaseId} ride as ProblemDetail extension properties per the contract schema.
   */
  @ExceptionHandler(MutatingGuardConflictException.class)
  public ProblemDetail onMutatingConflict(MutatingGuardConflictException e) {
    ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    detail.setProperty("featureId", e.getFeatureId());
    detail.setProperty("activeCaseId", e.getActiveCaseId());
    return detail;
  }

  @ExceptionHandler(CaseCreationException.class)
  public ProblemDetail onUnprocessable(CaseCreationException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
  }

  @ExceptionHandler(ClassificationNotConfirmedException.class)
  public ProblemDetail onClassificationNotConfirmed(ClassificationNotConfirmedException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.PRECONDITION_FAILED, e.getMessage());
  }

  @ExceptionHandler(IllegalCaseTransitionException.class)
  public ProblemDetail onIllegalTransition(IllegalCaseTransitionException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
  }

  @ExceptionHandler(NoSuchElementException.class)
  public ProblemDetail onNotFound(NoSuchElementException e) {
    // Not-found and cross-workspace are indistinguishable by design (contracts/api.yaml).
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
  }
}
