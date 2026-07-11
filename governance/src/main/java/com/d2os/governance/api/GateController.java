package com.d2os.governance.api;

import com.d2os.governance.GateInstance;
import com.d2os.governance.GateInstanceRepository;
import com.d2os.governance.GateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The gate worklist + decision surface (T017, contracts/api.yaml, FR-002). {@code X-Actor} identifies
 * the deciding user as a pragmatic input pending a full auth-principal wiring — same pattern as {@code
 * CandidateController}'s D4 endpoint (there is no role model in the codebase yet). Service exceptions
 * map to the contract status codes via {@link GateExceptionHandler}: {@code IllegalGateTransitionException}
 * → 409, {@code SelfReviewNotAllowedException} → 403, {@code NoSuchElementException} → 404.
 */
@RestController
@RequestMapping("/api/v1/gates")
public class GateController {

    private final GateInstanceRepository gateInstanceRepository;
    private final GateService gateService;
    private final ObjectMapper objectMapper;

    public GateController(GateInstanceRepository gateInstanceRepository, GateService gateService,
                          ObjectMapper objectMapper) {
        this.gateInstanceRepository = gateInstanceRepository;
        this.gateService = gateService;
        this.objectMapper = objectMapper;
    }

    /**
     * Reviewer worklist. {@code assignedRole} filters by a role derived from {@code gateType} — this
     * schema carries no explicit per-gate role-assignment column, so REVIEW gates are addressed to
     * {@code reviewer} and APPROVAL gates to {@code approver} (the same pragmatic-input posture as the
     * {@code X-Actor} header above; a real role model is a later hardening pass).
     */
    @GetMapping
    public List<GateSummary> list(@RequestParam(required = false) String status,
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
                .filter(g -> caseId == null || g.getCaseInstanceId().equals(caseId))
                .filter(g -> assignedRole == null || assignedRole.equals(defaultRoleFor(g)))
                .map(GateSummary::of)
                .toList();
    }

    @GetMapping("/{gateId}")
    public ResponseEntity<GateDetail> get(@PathVariable UUID gateId) {
        return gateInstanceRepository.findById(gateId)
                .map(g -> ResponseEntity.ok(toDetail(g)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** APPROVE / REJECT / REQUEST_CHANGES only (Q4) — REQUEST_CHANGES requires non-blank comments. */
    @PostMapping("/{gateId}/decision")
    public ResponseEntity<GateSummary> decide(@PathVariable UUID gateId,
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

    private String defaultRoleFor(GateInstance g) {
        return g.getGateType() == GateInstance.GateType.APPROVAL ? "approver" : "reviewer";
    }

    private GateDetail toDetail(GateInstance g) {
        Object inputsRef;
        try {
            inputsRef = objectMapper.readValue(g.getInputsRef(), Object.class);
        } catch (Exception e) {
            inputsRef = g.getInputsRef();   // fall back to the raw string if it's ever not valid JSON
        }
        return new GateDetail(g.getId(), g.getCaseInstanceId(), g.getGateType().name(), g.getGateDefinitionKey(),
                g.getGateDefinitionVersion(), g.getStatus(), g.getSubjectArtifactRevisionId(),
                g.getEscalationPolicyKey(), g.getEscalationPolicyVersion(), g.getDecisionId(),
                g.getOpenedAt(), g.getDecidedAt(), inputsRef, g.getReviewerComments(), g.getDeltaReportId());
    }

    // ---- DTOs ------------------------------------------------------------------------------------

    public record DecisionRequest(String verb, String rationale, String comments) {}

    public record GateSummary(UUID id, UUID caseInstanceId, String gateType, String gateDefinitionKey,
                              int gateDefinitionVersion, String status, UUID subjectArtifactRevisionId,
                              String escalationPolicyKey, Integer escalationPolicyVersion,
                              UUID decisionId, OffsetDateTime openedAt, OffsetDateTime decidedAt) {
        static GateSummary of(GateInstance g) {
            return new GateSummary(g.getId(), g.getCaseInstanceId(), g.getGateType().name(), g.getGateDefinitionKey(),
                    g.getGateDefinitionVersion(), g.getStatus(), g.getSubjectArtifactRevisionId(),
                    g.getEscalationPolicyKey(), g.getEscalationPolicyVersion(), g.getDecisionId(),
                    g.getOpenedAt(), g.getDecidedAt());
        }
    }

    /** {@code GET /gates/{id}} — GateSummary plus the resolved {@code inputsRef} (FR-002/003). */
    public record GateDetail(UUID id, UUID caseInstanceId, String gateType, String gateDefinitionKey,
                             int gateDefinitionVersion, String status, UUID subjectArtifactRevisionId,
                             String escalationPolicyKey, Integer escalationPolicyVersion,
                             UUID decisionId, OffsetDateTime openedAt, OffsetDateTime decidedAt,
                             Object inputsRef, String reviewerComments, UUID deltaReportId) {}
}
