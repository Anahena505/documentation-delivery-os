package com.d2os.artifacts;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps package-not-yet-delivered to HTTP 409 (contracts/api.yaml: "Case not yet Delivered").
 *
 * <p>Deliberately handles the dedicated {@link PackageNotDeliveredException}, NOT the generic
 * {@code NoSuchElementException} — an earlier version caught the latter globally and hijacked the
 * case-not-found → 404 mapping from casecore's handler (two global advices fighting over one
 * exception type; caught by the leakage suite, which then saw 409 instead of 404).
 */
@RestControllerAdvice
public class PackageExceptionHandler {

    @ExceptionHandler(PackageNotDeliveredException.class)
    public ProblemDetail onNotDelivered(PackageNotDeliveredException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }
}
