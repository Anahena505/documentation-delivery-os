package com.d2os.persona.spi;

import java.util.List;
import java.util.UUID;

/**
 * Port letting persona read the sanitized summaries of a submission's attachments without depending
 * on intake or on the attachment raw-storage path (dependency inversion; FR-015 boundary enforced
 * by {@code ArchitectureRules}, T050). Implementations return ONLY {@code
 * attachment_summary.summary_text} for {@code SUMMARIZED} attachments — never raw bytes or
 * extracted text — so a persona can be given attachment context that is structurally incapable of
 * carrying smuggled instructions (AD-12 for uploads).
 */
public interface AttachmentSummaryPort {

  /** Summary texts for the submission's summarized attachments, in upload order (empty if none). */
  List<String> findSummaryTexts(UUID submissionId);
}
