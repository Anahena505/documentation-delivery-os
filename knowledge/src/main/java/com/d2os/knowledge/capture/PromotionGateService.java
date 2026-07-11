package com.d2os.knowledge.capture;

import com.d2os.casecore.AuditWriter;
import com.d2os.casecore.DecisionRecord;
import com.d2os.casecore.DecisionRepository;
import com.d2os.knowledge.EmbeddingIndexer;
import com.d2os.knowledge.KnowledgeScope;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Enforces the default-deny promotion pipeline gates (T024, FR-009/013/019, Q5, Principle V):
 *
 * <ol>
 *   <li><b>Gate order</b> PREFILTER → CURATION → D4 — a gate cannot pass until its predecessors
 *       have (out-of-order → {@link GateOrderViolationException} → 409).
 *   <li><b>At-most-one PASS per (candidate, gate)</b> — checked here (belt) and enforced by the V13
 *       {@code uq_promotion_gate_pass} partial unique index (braces).
 *   <li><b>D4 is non-self-satisfiable</b> — the approver must hold the workspace-owner role AND
 *       differ from the redaction actor (→ {@link D4AuthorizationException} → 403).
 *   <li><b>On D4 APPROVE</b> — publish the KnowledgeItem version via {@link
 *       EmbeddingIndexer#publish} with {@code source_candidate_id} provenance (scope raised to the
 *       approved level, default WORKSPACE), and transition the candidate → PUBLISHED.
 *   <li><b>On any REJECT</b> — set status REJECTED + stage + reason, non-promotable, NO partial
 *       promotion.
 *   <li>Every gate outcome writes a {@code promotion_gate_record}; every D4 outcome additionally
 *       writes a Decision + AuditEntry — all in ONE transaction.
 * </ol>
 */
@Service
public class PromotionGateService {

  private final CaptureCandidateRepository candidateRepository;
  private final PromotionGateRecordRepository gateRepository;
  private final DecisionRepository decisionRepository;
  private final AuditWriter auditWriter;
  private final EmbeddingIndexer embeddingIndexer;

  public PromotionGateService(
      CaptureCandidateRepository candidateRepository,
      PromotionGateRecordRepository gateRepository,
      DecisionRepository decisionRepository,
      AuditWriter auditWriter,
      EmbeddingIndexer embeddingIndexer) {
    this.candidateRepository = candidateRepository;
    this.gateRepository = gateRepository;
    this.decisionRepository = decisionRepository;
    this.embeddingIndexer = embeddingIndexer;
    this.auditWriter = auditWriter;
  }

  // Gate records are keyed by the ROOT candidate id (revision 1) so a redaction revision's
  // CURATION/D4
  // rows share the trail with the root's PREFILTER row — otherwise the gate-order check across
  // revisions would never see the earlier gate. The root is revision 1 (revisionOf == null).

  // ---- PREFILTER gate --------------------------------------------------------------------------

  /** Record the automated PREFILTER PASS (first gate; {@code actor = system:prefilter}). */
  @Transactional
  public void recordPrefilterPass(CaptureCandidate candidate, String detail) {
    savePass(
        candidate,
        rootId(candidate),
        PromotionGateRecord.Gate.PREFILTER,
        "system:prefilter",
        null,
        detail);
  }

  // ---- CURATION gate ---------------------------------------------------------------------------

  /**
   * Record the CURATION PASS (second gate) with the redaction actor. The D4 gate reads this actor
   * back to enforce non-self-satisfiability. Requires the PREFILTER gate to have PASSed already.
   */
  @Transactional
  public void recordCurationPass(CaptureCandidate candidate, String redactionActor, String detail) {
    UUID root = rootId(candidate);
    requireGatePassed(root, PromotionGateRecord.Gate.PREFILTER);
    savePass(candidate, root, PromotionGateRecord.Gate.CURATION, redactionActor, null, detail);
  }

  // ---- D4 gate ---------------------------------------------------------------------------------

  /**
   * Decide the D4 gate on a redacted candidate revision.
   *
   * @param candidateId the D4_PENDING revision under review (the latest revision)
   * @param approver the deciding user's id
   * @param isWorkspaceOwner whether the approver holds the workspace-owner role (authorization
   *     input resolved by the caller; the gate requires it to be true)
   * @param approve true = APPROVE (publish), false = REJECT
   * @param scopeLevel the scope to publish at on APPROVE (default WORKSPACE)
   * @param reason required on REJECT; optional rationale on APPROVE
   * @return the published KnowledgeItem id on APPROVE, or {@code null} on REJECT
   */
  @Transactional
  public UUID decideD4(
      UUID candidateId,
      String approver,
      boolean isWorkspaceOwner,
      boolean approve,
      KnowledgeScope scopeLevel,
      String reason) {
    CaptureCandidate candidate =
        candidateRepository
            .findById(candidateId)
            .orElseThrow(() -> new NoSuchElementException("candidate " + candidateId));

    if (candidate.status() != CaptureCandidate.Status.D4_PENDING) {
      // Wrong state — not awaiting D4. 409.
      throw new GateOrderViolationException(
          "candidate " + candidateId + " is " + candidate.getStatus() + ", not D4_PENDING");
    }
    UUID root = rootId(candidate);
    // Gate order: PREFILTER and CURATION must already have PASSed before D4 can decide.
    requireGatePassed(root, PromotionGateRecord.Gate.PREFILTER);
    requireGatePassed(root, PromotionGateRecord.Gate.CURATION);

    // Non-self-satisfiable D4 (Q5): approver must be the workspace owner AND not the redaction
    // actor.
    if (!isWorkspaceOwner) {
      throw new D4AuthorizationException(
          "D4 approver " + approver + " lacks the workspace-owner role");
    }
    String redactionActor = curationActor(root);
    if (approver != null && approver.equals(redactionActor)) {
      throw new D4AuthorizationException(
          "D4 is non-self-satisfiable: approver " + approver + " is the redaction actor");
    }

    if (approve) {
      return approveAndPublish(candidate, approver, scopeLevel, reason);
    }
    rejectAtGate(
        candidate,
        PromotionGateRecord.Gate.D4,
        CaptureCandidate.RejectionStage.D4,
        approver,
        reason == null ? "rejected at D4" : reason);
    return null;
  }

  /**
   * Reject the candidate at an earlier gate (PREFILTER or CURATION). Sets status REJECTED with the
   * stage + reason, records a {@code REJECT} gate row and an AuditEntry — no partial promotion.
   * Used by the pre-filter / curation steps when a gate fails.
   */
  @Transactional
  public void rejectAtGate(
      UUID candidateId,
      PromotionGateRecord.Gate gate,
      CaptureCandidate.RejectionStage stage,
      String actor,
      String reason) {
    CaptureCandidate candidate =
        candidateRepository
            .findById(candidateId)
            .orElseThrow(() -> new NoSuchElementException("candidate " + candidateId));
    rejectAtGate(candidate, gate, stage, actor, reason);
  }

  // ---- internals -------------------------------------------------------------------------------

  private UUID approveAndPublish(
      CaptureCandidate candidate, String approver, KnowledgeScope scopeLevel, String rationale) {
    KnowledgeScope scope = scopeLevel == null ? KnowledgeScope.WORKSPACE : scopeLevel;
    // Scope ref: WORKSPACE items scope to the workspace; PROJECT items to the candidate's project.
    UUID scopeRef =
        scope == KnowledgeScope.PROJECT ? candidate.getProjectId() : candidate.getWorkspaceId();

    UUID itemId =
        embeddingIndexer.publish(
            candidate.getWorkspaceId(),
            UUID.randomUUID(),
            knowledgeKey(candidate),
            1,
            scope,
            scopeRef,
            List.of(candidate.getTags()),
            "en",
            candidate.getTitle(),
            candidate.getContent(),
            candidate.getId(), // source_candidate_id provenance (FR-021)
            null);

    // Decision + gate record + audit, all in this transaction (Principle V).
    UUID decisionId = UUID.randomUUID();
    decisionRepository.save(
        new DecisionRecord(
            decisionId,
            candidate.getWorkspaceId(),
            candidate.getCaseInstanceId(),
            "D4",
            approver,
            rationale,
            candidate.getId().toString()));
    savePass(
        candidate,
        rootId(candidate),
        PromotionGateRecord.Gate.D4,
        approver,
        decisionId,
        "D4 approved; published item " + itemId);
    // 008 US5 (T051): D4 approval is the cross-boundary promotion into the shared library
    // (FR-013) — stamp the authenticated approver + promotion-approver role (no-op/NULL in default
    // mode). "promotion-approver" mirrors the /d4 endpoint's @PreAuthorize gate (T052).
    auditWriter.recordDecision(
        candidate.getWorkspaceId(),
        "capture_candidate",
        candidate.getId(),
        "PUBLISHED",
        approver,
        "promotion-approver",
        Map.of(
            "gate",
            "D4",
            "outcome",
            "APPROVE",
            "knowledgeItemId",
            itemId.toString(),
            "scopeLevel",
            scope.name()));

    candidate.markPublished();
    candidateRepository.save(candidate);
    return itemId;
  }

  private void rejectAtGate(
      CaptureCandidate candidate,
      PromotionGateRecord.Gate gate,
      CaptureCandidate.RejectionStage stage,
      String actor,
      String reason) {
    candidate.reject(stage, reason);
    candidateRepository.save(candidate);

    UUID decisionId = null;
    if (gate == PromotionGateRecord.Gate.D4) {
      decisionId = UUID.randomUUID();
      decisionRepository.save(
          new DecisionRecord(
              decisionId,
              candidate.getWorkspaceId(),
              candidate.getCaseInstanceId(),
              "D4",
              actor,
              reason,
              candidate.getId().toString()));
    }
    // REJECT rows are keyed by root id too, keeping the whole trail on one candidate chain.
    gateRepository.save(
        new PromotionGateRecord(
            UUID.randomUUID(),
            candidate.getWorkspaceId(),
            rootId(candidate),
            gate,
            PromotionGateRecord.Outcome.REJECT,
            decisionId,
            actor,
            reason));
    auditWriter.record(
        candidate.getWorkspaceId(),
        "capture_candidate",
        candidate.getId(),
        "REJECTED",
        actor,
        Map.of("gate", gate.name(), "stage", stage.name(), "reason", safe(reason)));
  }

  private void savePass(
      CaptureCandidate candidate,
      UUID candidateKey,
      PromotionGateRecord.Gate gate,
      String actor,
      UUID decisionId,
      String detail) {
    if (gateRepository.existsByCandidateIdAndGateAndOutcome(
        candidateKey, gate.name(), PromotionGateRecord.Outcome.PASS.name())) {
      throw new GateOrderViolationException(
          "gate " + gate + " already PASSed for candidate " + candidateKey);
    }
    try {
      gateRepository.save(
          new PromotionGateRecord(
              UUID.randomUUID(),
              candidate.getWorkspaceId(),
              candidateKey,
              gate,
              PromotionGateRecord.Outcome.PASS,
              decisionId,
              actor,
              detail));
      gateRepository.flush(); // surface the uq_promotion_gate_pass violation here, not at commit
    } catch (DataIntegrityViolationException e) {
      // The DB partial unique index caught a concurrent double-pass the in-code check missed.
      throw new GateOrderViolationException(
          "gate " + gate + " already PASSed for candidate " + candidateKey);
    }
  }

  /** The root candidate id of a revision chain (revision 1's id). Gate records key on this. */
  private UUID rootId(CaptureCandidate candidate) {
    return candidate.getRevisionOf() == null ? candidate.getId() : candidate.getRevisionOf();
  }

  private void requireGatePassed(UUID candidateId, PromotionGateRecord.Gate gate) {
    if (!gateRepository.existsByCandidateIdAndGateAndOutcome(
        candidateId, gate.name(), PromotionGateRecord.Outcome.PASS.name())) {
      throw new GateOrderViolationException(
          "gate " + gate + " has not PASSed for candidate " + candidateId + " (out-of-order)");
    }
  }

  /** The actor recorded on the CURATION PASS = the redaction actor (for the D4 non-self check). */
  private String curationActor(UUID candidateId) {
    return gateRepository.findByCandidateIdOrderByCreatedAtAsc(candidateId).stream()
        .filter(
            r ->
                PromotionGateRecord.Gate.CURATION.name().equals(r.getGate())
                    && PromotionGateRecord.Outcome.PASS.name().equals(r.getOutcome()))
        .map(PromotionGateRecord::getActor)
        .findFirst()
        .orElse(null);
  }

  /** Knowledge key derived from the candidate's provenance so published items are traceable. */
  private String knowledgeKey(CaptureCandidate candidate) {
    return "capture-" + candidate.getCaseInstanceId();
  }

  private String safe(String s) {
    return s == null ? "" : s;
  }
}
