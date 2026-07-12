package com.d2os.orchestration;

import com.d2os.casecore.AuditEntryRecord;
import com.d2os.casecore.AuditEntryRepository;
import com.d2os.persona.spi.BaselineContextPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Implements {@link BaselineContextPort} by reading back the {@code BASELINE_RESOLVED} audit entry
 * {@link BaselineResolutionDelegate} wrote (T023, US3, research R4) — the same durable-record idiom
 * {@code CaseController}'s {@code GET /cases/{id}/baseline} (T025) reads from. Lives in
 * orchestration (not persona, which defines the port, nor casecore, which owns the audit table)
 * because persona already depends on casecore and orchestration depends on both — the same
 * dependency-inversion shape {@code IntakeAttachmentSummaryPort} uses for {@link
 * com.d2os.persona.spi.AttachmentSummaryPort}.
 */
@Component
public class BaselineContextPortImpl implements BaselineContextPort {

  private final AuditEntryRepository auditEntryRepository;
  private final ObjectMapper objectMapper;

  public BaselineContextPortImpl(
      AuditEntryRepository auditEntryRepository, ObjectMapper objectMapper) {
    this.auditEntryRepository = auditEntryRepository;
    this.objectMapper = objectMapper;
  }

  @Override
  public List<String> findBaselineSummaries(UUID caseId) {
    return auditEntryRepository
        .findFirstBySubjectTypeAndSubjectIdAndActionOrderByTxTimeDesc(
            "case_instance", caseId, BaselineResolutionDelegate.BASELINE_RESOLVED_ACTION)
        .map(this::summarize)
        .orElse(List.of());
  }

  private List<String> summarize(AuditEntryRecord entry) {
    List<String> summaries = new ArrayList<>();
    try {
      JsonNode revisions = objectMapper.readTree(entry.getDetails()).path("revisions");
      for (JsonNode r : revisions) {
        summaries.add(
            r.path("artifactType").asText()
                + " (revision "
                + r.path("revisionNo").asInt()
                + ", content-hash "
                + r.path("contentHash").asText()
                + ")");
      }
    } catch (Exception e) {
      return List.of();
    }
    return summaries;
  }
}
