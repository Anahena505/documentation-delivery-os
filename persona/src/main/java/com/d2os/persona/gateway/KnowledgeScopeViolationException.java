package com.d2os.persona.gateway;

/**
 * Thrown when a KnowledgeItem whose owning workspace differs from the executing case's workspace reaches
 * the injection seam (T015, T2-c). This is a hard defense-in-depth stop: even though retrieval already
 * filters by {@code workspace_id} (and the partition prunes to one workspace), an assertion at the
 * injection seam guarantees no cross-workspace content can ever be rendered into a persona prompt.
 * Unchecked — a violation is a bug/security event, never a recoverable condition.
 */
public class KnowledgeScopeViolationException extends RuntimeException {

    public KnowledgeScopeViolationException(String message) {
        super(message);
    }
}
