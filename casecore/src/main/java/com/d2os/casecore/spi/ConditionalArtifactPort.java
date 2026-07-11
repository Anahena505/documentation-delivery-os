package com.d2os.casecore.spi;

import java.util.List;

/**
 * Port that lets casecore evaluate the conditional-artifacts DMN without depending on a Flowable-DMN
 * engine dependency itself (Phase 4 US5, T030-T032, research R6, FR-014/015) — same dependency-inversion
 * seam as {@link SubmissionLookup} (intake implements this too, since it already carries the
 * flowable-spring-boot-starter-dmn dependency for {@code case-type-classification.dmn}).
 */
public interface ConditionalArtifactPort {

    /**
     * Evaluate the {@code conditionalArtifacts} decision (COLLECT hit policy — zero, one, or many
     * matching rows) against a submission's structured form data. Returns the extra artifacts the
     * pinned {@link com.d2os.casecore.CaseDefinitionSnapshot}'s expected set must additionally require.
     */
    List<ConditionalArtifact> evaluate(String formDataJson);

    /** One CONDITIONAL required-artifact row: the template it binds to, the kind it produces, why. */
    record ConditionalArtifact(String templateKey, String artifactKind, String reason) {}
}
