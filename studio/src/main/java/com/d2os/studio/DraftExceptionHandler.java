package com.d2os.studio;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.NoSuchElementException;

/**
 * Maps studio draft-domain and publish-lifecycle exceptions to HTTP status codes (tasks.md
 * T008/T018), same convention as {@code GateExceptionHandler}/{@code CaseExceptionHandler}/
 * {@code PackageExceptionHandler} (each module's own {@code @RestControllerAdvice}, all coexisting
 * in the same {@code :app} context — Spring resolves one matching handler per exception without
 * ambiguity across beans). Extended (rather than given a sibling handler) for {@link
 * PublishConflictException} (T018) since it maps to the exact same 409/{@code ProblemDetail}
 * shape as {@link DraftConflictException} — one class, no duplicated boilerplate.
 */
@RestControllerAdvice
public class DraftExceptionHandler {

    @ExceptionHandler(DraftConflictException.class)
    public ProblemDetail onConflict(DraftConflictException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    /**
     * {@code PublishConflictException} (tasks.md T016/T018): gate-not-approved, semver-ordering,
     * pinned-content-hash tamper, or duplicate-tuple conflicts from {@link PublishService} —
     * always a 409, same as {@link DraftConflictException}.
     */
    @ExceptionHandler(PublishConflictException.class)
    public ProblemDetail onPublishConflict(PublishConflictException e) {
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
