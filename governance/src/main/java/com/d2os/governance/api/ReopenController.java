package com.d2os.governance.api;

import com.d2os.governance.GateInstance;
import com.d2os.governance.reopen.GateReopenCandidate;
import com.d2os.governance.reopen.GateReopenCandidateRepository;
import com.d2os.governance.reopen.ImpactAssessment;
import com.d2os.governance.reopen.ReopenService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reopen-candidate worklist and reopen action (T028, contracts/api.yaml, FR-006/007/008, US3).
 * Service exceptions map through {@link GateExceptionHandler}: {@code ReopenNotAllowedException} →
 * 409, {@code NoSuchElementException} → 404 — same convention {@link GateController} already uses.
 */
@RestController
public class ReopenController {

  private final GateReopenCandidateRepository candidateRepository;
  private final ReopenService reopenService;

  public ReopenController(
      GateReopenCandidateRepository candidateRepository, ReopenService reopenService) {
    this.candidateRepository = candidateRepository;
    this.reopenService = reopenService;
  }

  @GetMapping("/api/v1/reopen-candidates")
  public List<ReopenCandidateView> list(@RequestParam(required = false) String disposition) {
    List<GateReopenCandidate> candidates =
        disposition != null
            ? candidateRepository.findByDisposition(disposition)
            : candidateRepository.findAll();
    return candidates.stream().map(ReopenCandidateView::of).toList();
  }

  @PostMapping("/api/v1/gates/{gateId}/impact-assessment")
  public ResponseEntity<ImpactAssessmentView> createImpactAssessment(
      @PathVariable UUID gateId,
      @RequestHeader(value = "X-Actor", defaultValue = "reviewer") String actor,
      @RequestBody ImpactAssessmentRequest request) {
    ImpactAssessment assessment =
        reopenService.recordImpactAssessment(
            gateId,
            request.upstreamArtifactRevisionId(),
            request.reason(),
            request.scope(),
            request.risk(),
            actor);
    return ResponseEntity.status(HttpStatus.CREATED).body(ImpactAssessmentView.of(assessment));
  }

  @PostMapping("/api/v1/gates/{gateId}/reopen")
  public ResponseEntity<GateController.GateSummary> reopen(
      @PathVariable UUID gateId,
      @RequestHeader(value = "X-Actor", defaultValue = "reviewer") String actor) {
    GateInstance reopened = reopenService.reopen(gateId, actor);
    return ResponseEntity.ok(GateController.GateSummary.of(reopened));
  }

  public record ImpactAssessmentRequest(
      UUID upstreamArtifactRevisionId, String reason, String scope, String risk) {}

  public record ImpactAssessmentView(
      UUID id,
      UUID gateInstanceId,
      UUID upstreamArtifactRevisionId,
      String reason,
      String scope,
      String risk,
      String author,
      OffsetDateTime createdAt) {
    static ImpactAssessmentView of(ImpactAssessment a) {
      return new ImpactAssessmentView(
          a.getId(),
          a.getGateInstanceId(),
          a.getUpstreamArtifactRevisionId(),
          a.getReason(),
          a.getScope(),
          a.getRisk(),
          a.getAuthor(),
          a.getCreatedAt());
    }
  }

  public record ReopenCandidateView(
      UUID id,
      UUID upstreamArtifactRevisionId,
      UUID dependentArtifactRevisionId,
      UUID gateInstanceId,
      int depth,
      String disposition) {
    static ReopenCandidateView of(GateReopenCandidate c) {
      return new ReopenCandidateView(
          c.getId(),
          c.getUpstreamArtifactRevisionId(),
          c.getDependentArtifactRevisionId(),
          c.getGateInstanceId(),
          c.getDepth(),
          c.getDisposition().name());
    }
  }
}
