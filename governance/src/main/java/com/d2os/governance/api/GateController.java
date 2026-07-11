package com.d2os.governance.api;

import com.d2os.governance.DeltaReport;
import com.d2os.governance.DeltaReportRepository;
import com.d2os.governance.GateInstance;
import com.d2os.governance.GateInstanceRepository;
import com.d2os.governance.GateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The gate worklist + decision surface (T017, contracts/api.yaml, FR-002). {@code X-Actor}
 * identifies the deciding user as a pragmatic input pending a full auth-principal wiring — same
 * pattern as {@code CandidateController}'s D4 endpoint (there is no role model in the codebase
 * yet). Service exceptions map to the contract status codes via {@link GateExceptionHandler}:
 * {@code IllegalGateTransitionException} → 409, {@code SelfReviewNotAllowedException} → 403, {@code
 * NoSuchElementException} → 404.
 */
@RestController
@RequestMapping("/api/v1/gates")
public class GateController {

  private final GateInstanceRepository gateInstanceRepository;
  private final GateService gateService;
  private final DeltaReportRepository deltaReportRepository;
  private final ObjectMapper objectMapper;

  public GateController(
      GateInstanceRepository gateInstanceRepository,
      GateService gateService,
      DeltaReportRepository deltaReportRepository,
      ObjectMapper objectMapper) {
    this.gateInstanceRepository = gateInstanceRepository;
    this.gateService = gateService;
    this.deltaReportRepository = deltaReportRepository;
    this.objectMapper = objectMapper;
  }

  /**
   * Reviewer worklist. {@code assignedRole} filters by a role derived from {@code gateType} — this
   * schema carries no explicit per-gate role-assignment column, so REVIEW gates are addressed to
   * {@code reviewer} and APPROVAL gates to {@code approver} (the same pragmatic-input posture as
   * the {@code X-Actor} header above; a real role model is a later hardening pass).
   */
  @GetMapping
  public List<GateSummary> list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID caseId,
      @RequestParam(required = false) String assignedRole) {
    List<GateInstance> gates;
    if (status != null) {
      gates = gateInstanceRepository.findByStatus(status);
    } else if (caseId != null) {
      gates = gateInstanceRepository.findByCaseInstanceId(caseId);
    } else {
      gates = gateInstanceRepository.findAll();
    }
    return gates.stream()
        // caseId.equals(...), not the reverse: a DEFINITION_VERSION-subject gate (studio
        // publish review, V27) can have a null caseInstanceId, so g.getCaseInstanceId()
        // must never be the receiver of .equals() here.
        .filter(g -> caseId == null || caseId.equals(g.getCaseInstanceId()))
        .filter(g -> assignedRole == null || assignedRole.equals(defaultRoleFor(g)))
        .map(GateSummary::of)
        .toList();
  }

  @GetMapping("/{gateId}")
  public ResponseEntity<GateDetail> get(@PathVariable UUID gateId) {
    return gateInstanceRepository
        .findById(gateId)
        .map(g -> ResponseEntity.ok(toDetail(g)))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  /**
   * APPROVE / REJECT / REQUEST_CHANGES only (Q4) — REQUEST_CHANGES requires non-blank comments.
   *
   * <p>008 US5 (T052, contracts/auth-and-rbac.yaml): role-restricted to a governance-approver. The
   * {@code @PreAuthorize} is INERT in the default posture — method security is enabled only by
   * {@link com.d2os.tenancy.security.OidcSecurityConfig}'s {@code @EnableMethodSecurity}, which is
   * active only when {@code d2os.security.oidc.enabled=true} — so existing (default-mode) tests and
   * deployments are unaffected; in OIDC mode an authenticated caller lacking {@code ROLE_approver}
   * gets 403.
   */
  @PreAuthorize("hasRole('approver')")
  @PostMapping("/{gateId}/decision")
  public ResponseEntity<GateSummary> decide(
      @PathVariable UUID gateId,
      @RequestHeader(value = "X-Actor", defaultValue = "reviewer") String actor,
      @RequestBody DecisionRequest request) {
    GateService.Verb verb;
    try {
      verb = GateService.Verb.valueOf(request.verb() == null ? "" : request.verb());
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().build();
    }
    if (verb == GateService.Verb.REQUEST_CHANGES
        && (request.comments() == null || request.comments().isBlank())) {
      return ResponseEntity.badRequest().build();
    }
    GateInstance decided = gateService.decide(gateId, verb, actor, request.comments());
    return ResponseEntity.ok(GateSummary.of(decided));
  }

  /**
   * {@code GET /gates/{gateId}/delta-report} (T021, contracts/api.yaml, FR-005): the deterministic
   * diff a comment-and-regenerate cycle (or a reopen, US3) attached to this gate. 404 both when the
   * gate itself doesn't exist and when it exists but no regeneration/reopen has produced a delta
   * yet ({@code deltaReportId} is null) — the same "nothing to show" outcome either way.
   *
   * <p><b>Shape-agnostic (tasks.md T014 verification)</b>: this endpoint just resolves whatever
   * {@link DeltaReport} row {@code gate_instance.delta_report_id} points at and returns it — it
   * never branches on which of the two V26 shapes (artifact-triple vs. definition-pair) the row is,
   * so a studio publish-review gate's definition-pair delta report round-trips through the exact
   * same route an artifact regeneration's does. {@link DeltaReportView} was extended with {@code
   * fromDefinitionId}/{@code toDefinitionId} (previously only the artifact-triple fields were
   * exposed) so the definition-pair shape's identifying ids are visible in the response too, not
   * just {@code diffContent}/{@code diffHash}.
   */
  @GetMapping("/{gateId}/delta-report")
  public ResponseEntity<DeltaReportView> deltaReport(@PathVariable UUID gateId) {
    return gateInstanceRepository
        .findById(gateId)
        .map(GateInstance::getDeltaReportId)
        .flatMap(
            deltaReportId ->
                deltaReportId == null
                    ? java.util.Optional.<DeltaReport>empty()
                    : deltaReportRepository.findById(deltaReportId))
        .map(dr -> ResponseEntity.ok(DeltaReportView.of(dr)))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  private String defaultRoleFor(GateInstance g) {
    return g.getGateType() == GateInstance.GateType.APPROVAL ? "approver" : "reviewer";
  }

  private GateDetail toDetail(GateInstance g) {
    Object inputsRef;
    try {
      inputsRef = objectMapper.readValue(g.getInputsRef(), Object.class);
    } catch (Exception e) {
      inputsRef = g.getInputsRef(); // fall back to the raw string if it's ever not valid JSON
    }
    return new GateDetail(
        g.getId(),
        g.getCaseInstanceId(),
        g.getGateType().name(),
        g.getGateDefinitionKey(),
        g.getGateDefinitionVersion(),
        g.getStatus(),
        g.getSubjectArtifactRevisionId(),
        g.getSubjectType().name(),
        g.getSubjectId(),
        g.getEscalationPolicyKey(),
        g.getEscalationPolicyVersion(),
        g.getDecisionId(),
        g.getOpenedAt(),
        g.getDecidedAt(),
        inputsRef,
        g.getReviewerComments(),
        g.getDeltaReportId());
  }

  // ---- DTOs ------------------------------------------------------------------------------------

  public record DecisionRequest(String verb, String rationale, String comments) {}

  public record GateSummary(
      UUID id,
      UUID caseInstanceId,
      String gateType,
      String gateDefinitionKey,
      int gateDefinitionVersion,
      String status,
      UUID subjectArtifactRevisionId,
      String escalationPolicyKey,
      Integer escalationPolicyVersion,
      UUID decisionId,
      OffsetDateTime openedAt,
      OffsetDateTime decidedAt) {
    static GateSummary of(GateInstance g) {
      return new GateSummary(
          g.getId(),
          g.getCaseInstanceId(),
          g.getGateType().name(),
          g.getGateDefinitionKey(),
          g.getGateDefinitionVersion(),
          g.getStatus(),
          g.getSubjectArtifactRevisionId(),
          g.getEscalationPolicyKey(),
          g.getEscalationPolicyVersion(),
          g.getDecisionId(),
          g.getOpenedAt(),
          g.getDecidedAt());
    }
  }

  /**
   * {@code GET /gates/{id}} — GateSummary plus the resolved {@code inputsRef} (FR-002/003), plus
   * the polymorphic {@code subjectType}/{@code subjectId} (V26, tasks.md T013/T014) — the studio
   * review page ({@code studio.ReviewPageController}, T015) reads these to resolve which {@code
   * DefinitionAsset} a {@code DEFINITION_VERSION} gate concerns.
   */
  public record GateDetail(
      UUID id,
      UUID caseInstanceId,
      String gateType,
      String gateDefinitionKey,
      int gateDefinitionVersion,
      String status,
      UUID subjectArtifactRevisionId,
      String subjectType,
      UUID subjectId,
      String escalationPolicyKey,
      Integer escalationPolicyVersion,
      UUID decisionId,
      OffsetDateTime openedAt,
      OffsetDateTime decidedAt,
      Object inputsRef,
      String reviewerComments,
      UUID deltaReportId) {}

  /**
   * {@code GET /gates/{id}/delta-report} response body (T021, extended by T014 with the V26
   * definition-pair shape's {@code fromDefinitionId}/{@code toDefinitionId} — both null for an
   * artifact-triple-shaped report, and {@code artifactId}/{@code fromRevisionId}/{@code
   * toRevisionId} null for a definition-pair-shaped one; exactly one pair is populated per row).
   */
  public record DeltaReportView(
      UUID id,
      UUID artifactId,
      UUID fromRevisionId,
      UUID toRevisionId,
      UUID fromDefinitionId,
      UUID toDefinitionId,
      String diffContent,
      String diffHash,
      OffsetDateTime createdAt) {
    static DeltaReportView of(DeltaReport d) {
      return new DeltaReportView(
          d.getId(),
          d.getArtifactId(),
          d.getFromRevisionId(),
          d.getToRevisionId(),
          d.getFromDefinitionId(),
          d.getToDefinitionId(),
          d.getDiffContent(),
          d.getDiffHash(),
          d.getCreatedAt());
    }
  }
}
