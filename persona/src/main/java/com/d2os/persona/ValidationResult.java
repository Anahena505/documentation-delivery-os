package com.d2os.persona;

import java.util.List;

/** Outcome of validating one persona output attempt (T033, FR-005). */
public record ValidationResult(double weightedScore, List<String> criticalFailures, boolean passed) {

    public static final double PASS_THRESHOLD = 0.80;

    public static ValidationResult of(double weightedScore, List<String> criticalFailures) {
        boolean passed = weightedScore >= PASS_THRESHOLD && criticalFailures.isEmpty();
        return new ValidationResult(weightedScore, criticalFailures, passed);
    }
}
