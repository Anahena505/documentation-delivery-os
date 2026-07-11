package com.d2os.studio;

/**
 * Publish-lifecycle conflict (tasks.md T016/T018): gate not (yet) APPROVED, semver-ordering
 * violation, pinned-content-hash tamper mismatch, or a duplicate {@code (type,key,version)} --
 * HTTP 409 either way. Mirrors {@link DraftConflictException}'s "one exception type, several 409
 * causes" style, kept as a sibling type rather than folded into it (T018): draft-CRUD conflicts
 * and publish-lifecycle conflicts are different concerns even though they map to the same status
 * code, and {@code PublishService} should not need to import a class named for drafts.
 */
public class PublishConflictException extends RuntimeException {
    public PublishConflictException(String message) {
        super(message);
    }

    public PublishConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
