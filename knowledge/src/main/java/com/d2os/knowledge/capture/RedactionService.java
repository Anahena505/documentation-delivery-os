package com.d2os.knowledge.capture;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saves a Curator redaction as a NEW candidate revision (T023, FR-011/FR-012). The prior revision
 * is preserved untouched for audit; the new row is {@code revision+1} with {@code revisionOf} = the
 * prior id, born {@code REDACTED}. The pre-filter must have run first — the prior revision must be
 * in {@code PREFILTERED} state, else the fixed-order pipeline rejects the call (surfaced as HTTP
 * 409).
 *
 * <p>The redaction is a promotion gate outcome: it records a CURATION-gate {@code PASS} in the same
 * transaction (via {@link PromotionGateService}) whose {@code actor} is the Curator/redaction
 * actor. The D4 gate (T024) reads that actor back to enforce non-self-satisfiability (the approver
 * must differ from the redaction actor). The new revision is advanced to {@code D4_PENDING} so it
 * awaits the D4 review.
 */
@Service
public class RedactionService {

  private final CaptureCandidateRepository candidateRepository;
  private final PromotionGateService promotionGateService;

  public RedactionService(
      CaptureCandidateRepository candidateRepository, PromotionGateService promotionGateService) {
    this.candidateRepository = candidateRepository;
    this.promotionGateService = promotionGateService;
  }

  /**
   * @param candidateId the PREFILTERED revision being redacted (the prior revision)
   * @param actor the Curator/redaction actor (recorded on the CURATION gate PASS; the D4 gate later
   *     requires the approver to differ from this actor)
   * @param curatorOperationExecutionId the Curator persona op snapshot id (may be null in a
   *     services-only flow); recorded on the new revision for provenance
   * @return the id of the NEW redacted revision (the row the D4 gate reviews)
   */
  @Transactional
  public UUID saveRedaction(
      UUID candidateId,
      String actor,
      String title,
      String content,
      List<String> tags,
      UUID curatorOperationExecutionId) {
    CaptureCandidate prior =
        candidateRepository
            .findById(candidateId)
            .orElseThrow(() -> new NoSuchElementException("candidate " + candidateId));

    if (prior.status() != CaptureCandidate.Status.PREFILTERED) {
      // Wrong state — the pipeline is fixed-order with no skip path. 409 at the controller.
      throw new IllegalCandidateTransitionException(
          prior.status(), CaptureCandidate.Status.REDACTED);
    }

    String[] tagArray = tags == null ? new String[0] : tags.toArray(new String[0]);
    CaptureCandidate revision =
        CaptureCandidate.redactionRevision(
            UUID.randomUUID(), prior, title, content, tagArray, curatorOperationExecutionId);
    candidateRepository.save(revision);

    // Record the CURATION gate PASS on the NEW revision, carrying the redaction actor so the D4
    // gate
    // can enforce approver ≠ redaction actor. Then advance the revision to D4_PENDING.
    promotionGateService.recordCurationPass(
        revision, actor, "curator redaction saved as revision " + revision.getRevision());
    revision.markD4Pending();
    candidateRepository.save(revision);

    return revision.getId();
  }
}
