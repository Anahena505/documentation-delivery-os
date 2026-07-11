package com.d2os.casecore.spi;

import java.util.Optional;
import java.util.UUID;

/**
 * Port that lets casecore read the minimum it needs about a submission without depending on the
 * intake module (dependency inversion — intake implements this). Keeps the module graph acyclic.
 */
public interface SubmissionLookup {

  Optional<SubmissionInfo> find(UUID submissionId);

  /**
   * Minimal projection casecore needs to open a Case from a confirmed submission. {@code formData}
   * (Phase 4 US5, T032) is the submission's opaque JSON form payload — never instructions, AD-12 —
   * read only for structured flags like {@code personalData} that drive the conditional-artifacts
   * DMN; never interpolated into a persona prompt from here.
   */
  record SubmissionInfo(
      UUID id, UUID workspaceId, String caseTypeKey, boolean confirmed, String formData) {}
}
