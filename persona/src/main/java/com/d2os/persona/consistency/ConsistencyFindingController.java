package com.d2os.persona.consistency;

import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consistency findings API (US3, T031, contracts/api.yaml). Lists findings for a case and records
 * the human decision on one. A DETERMINISTIC finding cannot be WAIVED — that request returns 409 so
 * a real contradiction can never be waved through (Principle V).
 */
@RestController
@RequestMapping("/api/v1/cases/{caseId}/consistency-findings")
public class ConsistencyFindingController {

  private final ConsistencyService consistencyService;

  public ConsistencyFindingController(ConsistencyService consistencyService) {
    this.consistencyService = consistencyService;
  }

  @GetMapping
  public ResponseEntity<List<FindingView>> list(
      @PathVariable UUID caseId,
      @RequestParam(required = false) String tier,
      @RequestParam(required = false) String status) {
    List<FindingView> views =
        consistencyService.findings(caseId).stream()
            .filter(f -> tier == null || tier.equals(f.getTier()))
            .filter(f -> status == null || status.equals(f.getStatus()))
            .map(FindingView::of)
            .toList();
    return ResponseEntity.ok(views);
  }

  @PostMapping("/{findingId}/resolve")
  public ResponseEntity<Void> resolve(
      @PathVariable UUID caseId,
      @PathVariable UUID findingId,
      @RequestBody ResolveRequest request) {
    ConsistencyFinding.Status resolution;
    try {
      resolution = ConsistencyFinding.Status.valueOf(request.resolution());
    } catch (IllegalArgumentException | NullPointerException e) {
      return ResponseEntity.badRequest().build();
    }
    if (resolution != ConsistencyFinding.Status.RESOLVED
        && resolution != ConsistencyFinding.Status.WAIVED) {
      return ResponseEntity.badRequest().build();
    }
    boolean ok = consistencyService.resolveFinding(caseId, findingId, resolution, "human");
    // 409 when a DETERMINISTIC finding was asked to be WAIVED (non-waivable).
    return ok ? ResponseEntity.ok().build() : ResponseEntity.status(409).build();
  }

  public record FindingView(
      UUID id,
      String tier,
      String kind,
      String subjectRef,
      String status,
      UUID sourceOperationId,
      UUID targetOperationId) {
    static FindingView of(ConsistencyFinding f) {
      return new FindingView(
          f.getId(),
          f.getTier(),
          f.getKind(),
          f.getSubjectRef(),
          f.getStatus(),
          f.getSourceOperationId(),
          f.getTargetOperationId());
    }
  }

  public record ResolveRequest(String resolution, String rationale) {}
}
