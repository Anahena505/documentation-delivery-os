package com.d2os.governance;

/** Raised when code attempts a Gate state transition not permitted by {@link GateStatus}. */
public class IllegalGateTransitionException extends RuntimeException {
    public IllegalGateTransitionException(GateStatus from, GateStatus to) {
        super("Illegal gate transition: " + from + " → " + to);
    }
}
