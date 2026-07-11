package com.d2os.casecore;

/**
 * Phase 4 (T012, US1): the submission's case-type classification is not yet CONFIRMED (contracts/api.yaml
 * {@code /cases} 412 — "Submission classification not yet confirmed"). Distinct from
 * {@link CaseCreationException} (422 — other case-creation preconditions, e.g. feature not found, no
 * published case type) because this specific precondition has its own documented status code.
 */
public class ClassificationNotConfirmedException extends RuntimeException {
    public ClassificationNotConfirmedException(String message) {
        super(message);
    }
}
