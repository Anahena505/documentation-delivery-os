package com.d2os.governance.api;

import com.d2os.governance.IllegalGateTransitionException;
import com.d2os.governance.SelfReviewNotAllowedException;
import com.d2os.governance.reopen.ReopenNotAllowedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.NoSuchElementException;

/** Maps governance-domain exceptions to HTTP status codes (mirrors {@code CaseExceptionHandler}). */
@RestControllerAdvice
public class GateExceptionHandler {

    @ExceptionHandler(IllegalGateTransitionException.class)
    public ProblemDetail onIllegalTransition(IllegalGateTransitionException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(SelfReviewNotAllowedException.class)
    public ProblemDetail onSelfReview(SelfReviewNotAllowedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(ReopenNotAllowedException.class)
    public ProblemDetail onReopenNotAllowed(ReopenNotAllowedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ProblemDetail onNotFound(NoSuchElementException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }
}
