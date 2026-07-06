package com.d2os.intake;

/**
 * Outcome of classifying a submission (T024). {@code needsHumanConfirm} is true when confidence is
 * below the configured threshold — the pipeline then requires a human confirm step (FR-002).
 */
public record ClassificationResult(String caseType, double confidence, boolean needsHumanConfirm) {
}
