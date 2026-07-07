package com.d2os.knowledge.capture;

/**
 * Thrown when a {@link CaptureCandidate} is asked to make a transition the fixed-order promotion state
 * machine forbids (e.g. skipping a gate). Surfaced as HTTP 409 by {@code CandidateController}.
 */
public class IllegalCandidateTransitionException extends RuntimeException {

    public IllegalCandidateTransitionException(CaptureCandidate.Status from, CaptureCandidate.Status to) {
        super("illegal capture-candidate transition " + from + " → " + to);
    }
}
