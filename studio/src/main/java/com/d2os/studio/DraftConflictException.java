package com.d2os.studio;

/**
 * A draft mutation conflicts with either the {@code (type,key,version)} uniqueness constraint
 * (create) or the Draft-only edit guard ({@code DefinitionAsset#updateBody}, update while
 * InReview/Published/Deprecated) — HTTP 409 either way (tasks.md T008, contracts/api.yaml).
 */
public class DraftConflictException extends RuntimeException {
    public DraftConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
