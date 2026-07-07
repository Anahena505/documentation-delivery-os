package com.d2os.knowledge;

/**
 * Thrown when a deprecation targets a KnowledgeItem version that is already {@code DEPRECATED} (US3).
 * Maps to HTTP 409 at the controller — deprecation is idempotent-by-refusal: it will not re-flag
 * executions for an already-retired item.
 */
public class AlreadyDeprecatedException extends RuntimeException {
    public AlreadyDeprecatedException(String message) {
        super(message);
    }
}
