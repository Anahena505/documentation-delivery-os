package com.d2os.casecore;

/** Raised when code attempts a Case state transition not permitted by {@link CaseStatus}. */
public class IllegalCaseTransitionException extends RuntimeException {
    public IllegalCaseTransitionException(CaseStatus from, CaseStatus to) {
        super("Illegal case transition: " + from + " → " + to);
    }
}
