package com.d2os.casecore.spi;

import java.util.Optional;
import java.util.UUID;

/**
 * Port that lets casecore read the minimum it needs about a submission without depending on the
 * intake module (dependency inversion — intake implements this). Keeps the module graph acyclic.
 */
public interface SubmissionLookup {

    Optional<SubmissionInfo> find(UUID submissionId);

    /** Minimal projection casecore needs to open a Case from a confirmed submission. */
    record SubmissionInfo(UUID id, UUID workspaceId, String caseTypeKey, boolean confirmed) {}
}
