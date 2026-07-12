package com.d2os.persona.spi;

import java.util.List;
import java.util.UUID;

/**
 * Port letting persona read an Enhancement case's resolved baseline reference set without depending
 * on orchestration or on the artifact-storage path (dependency inversion, same shape as {@link
 * AttachmentSummaryPort}; FR-017/FR-018, research R4). Implementations return short, human-readable
 * summaries of the pinned baseline ArtifactRevisions (artifact type, revision number, content hash)
 * — metadata only, never a re-fetch of the baseline's raw content bytes. This is a deliberate scope
 * decision (T023): actually streaming and truncating baseline document bytes from object storage at
 * every persona envelope build is real additional engineering (byte fetch, decoding, a truncation/
 * token-budget policy) beyond this phase's "catalog content + thin engineering" posture, and a
 * metadata-only reference is still genuine grounded context a persona can use to know WHAT baseline
 * it is being asked to delta/impact-analyze against, without the envelope literally
 * re-authoring/copying the baseline's content (R4's "reference, never copy" principle, applied
 * conservatively here too). Empty for a Case with no resolved baseline (every non-Enhancement case;
 * or no {@link BaselineContextPort} bean on the path, e.g. persona-only slice tests).
 */
public interface BaselineContextPort {

  /**
   * Baseline reference summaries for the case, in resolution order (empty if none/not Enhancement).
   */
  List<String> findBaselineSummaries(UUID caseId);
}
