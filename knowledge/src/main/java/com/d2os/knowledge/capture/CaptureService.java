package com.d2os.knowledge.capture;

import com.d2os.casecore.CaseInstance;
import com.d2os.casecore.CaseInstanceRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Harvests lessons-learned {@link CaptureCandidate}s from a delivered Case (T021, FR-008). Each
 * candidate is born {@code CAPTURED}, PROJECT-confidential, {@code revision=1}, and non-promotable
 * — only the fixed-order promotion pipeline (pre-filter → curation → D4) can ever publish it.
 *
 * <p>v1 harvest is deterministic and testable: one candidate per delivered case, summarizing it.
 * This gives the pipeline a single well-defined subject per case (SC-005/SC-006) without depending
 * on the {@code artifacts} module (the knowledge module's dependency set is
 * tenancy/catalog/casecore/persona, not artifacts — see knowledge/build.gradle). A richer
 * per-artifact harvest is a later refinement; the state machine and gate discipline this service
 * feeds are unaffected by the harvest granularity.
 *
 * <p>The case's project is resolved from the feature chain ({@code case_instance.feature_id →
 * feature.project_version_id → project_version.project_id}) via {@code JdbcTemplate} — there is no
 * {@code ProjectVersion} JPA entity in reach here, and the chain is a two-hop lookup under the
 * caller's RLS context.
 */
@Service
public class CaptureService {

  private static final String PROJECT_ID_SQL =
      """
            SELECT pv.project_id
              FROM case_instance ci
              JOIN feature f          ON f.id = ci.feature_id
              JOIN project_version pv ON pv.id = f.project_version_id
             WHERE ci.id = ?
            """;

  private final CaseInstanceRepository caseInstanceRepository;
  private final CaptureCandidateRepository candidateRepository;
  private final JdbcTemplate jdbcTemplate;

  public CaptureService(
      CaseInstanceRepository caseInstanceRepository,
      CaptureCandidateRepository candidateRepository,
      JdbcTemplate jdbcTemplate) {
    this.caseInstanceRepository = caseInstanceRepository;
    this.candidateRepository = candidateRepository;
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Harvest candidates from the delivered case. Idempotent per case: if candidates already exist
   * for this case (a re-delivery or a re-run of the capture process) it returns the existing ids
   * rather than harvesting duplicates, so the outbox trigger's at-least-once delivery cannot
   * double-capture.
   *
   * @return the ids of the candidates that now exist for this case (born or pre-existing).
   */
  @Transactional
  public List<UUID> captureFrom(UUID caseInstanceId) {
    List<CaptureCandidate> existing = candidateRepository.findByCaseInstanceId(caseInstanceId);
    if (!existing.isEmpty()) {
      return existing.stream().map(CaptureCandidate::getId).toList();
    }

    CaseInstance kase =
        caseInstanceRepository
            .findById(caseInstanceId)
            .orElseThrow(() -> new NoSuchElementException("case " + caseInstanceId));

    UUID projectId = jdbcTemplate.queryForObject(PROJECT_ID_SQL, UUID.class, caseInstanceId);
    if (projectId == null) {
      throw new NoSuchElementException("no project resolvable for case " + caseInstanceId);
    }

    // Deterministic v1 harvest: a single lessons-learned candidate summarizing the delivered case.
    // Content is the raw, un-redacted summary — the pre-filter (T022) is what strips sensitive
    // spans
    // before any human/Curator sees it, so capture stays default-deny end to end.
    String title = "Lessons learned — case " + caseInstanceId;
    String content =
        "Delivered case "
            + caseInstanceId
            + " (type "
            + kase.getCaseTypeKey()
            + " v"
            + kase.getCaseTypeVersion()
            + "). Capture summary of the delivered documentation "
            + "package for promotion review.";
    String[] tags = new String[] {"lessons-learned", kase.getCaseTypeKey()};

    CaptureCandidate candidate =
        new CaptureCandidate(
            UUID.randomUUID(),
            kase.getWorkspaceId(),
            caseInstanceId,
            projectId,
            title,
            content,
            tags);
    candidateRepository.save(candidate);

    List<UUID> ids = new ArrayList<>();
    ids.add(candidate.getId());
    return ids;
  }
}
