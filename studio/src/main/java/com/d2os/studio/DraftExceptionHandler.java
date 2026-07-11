package com.d2os.studio;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.NoSuchElementException;

/**
 * Maps studio draft-domain exceptions to HTTP status codes (tasks.md T008), same convention as
 * {@code GateExceptionHandler}/{@code CaseExceptionHandler}/{@code PackageExceptionHandler}
 * (each module's own {@code @RestControllerAdvice}, all coexisting in the same {@code :app}
 * context — Spring resolves one matching handler per exception without ambiguity across beans).
 */
@RestControllerAdvice
public class DraftExceptionHandler {

    @ExceptionHandler(DraftConflictException.class)
    public ProblemDetail onConflict(DraftConflictException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail onBadRequest(IllegalArgumentException e) {
        // Typed-slot validation failures (RubricEditorModel/PromptEditorModel#validate, FR-003).
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ProblemDetail onNotFound(NoSuchElementException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }
}
