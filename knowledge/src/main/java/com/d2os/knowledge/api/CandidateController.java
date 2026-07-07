package com.d2os.knowledge.api;

import com.d2os.knowledge.KnowledgeScope;
import com.d2os.knowledge.capture.CaptureCandidate;
import com.d2os.knowledge.capture.CaptureCandidateRepository;
import com.d2os.knowledge.capture.CaptureWaitReleaser;
import com.d2os.knowledge.capture.D4AuthorizationException;
import com.d2os.knowledge.capture.GateOrderViolationException;
import com.d2os.knowledge.capture.IllegalCandidateTransitionException;
import com.d2os.knowledge.capture.PrefilterFinding;
import com.d2os.knowledge.capture.PrefilterFindingRepository;
import com.d2os.knowledge.capture.PromotionGateRecord;
import com.d2os.knowledge.capture.PromotionGateRecordRepository;
import com.d2os.knowledge.capture.PromotionGateService;
import com.d2os.knowledge.capture.RedactionService;
import org.springframework.beans.factory.ObjectProvider;
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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The candidate/redaction/D4 governance surface for the capture→promotion pipeline (T026, US2,
 * contracts/api.yaml). Confidential until PUBLISHED. Service exceptions map to the contract status codes:
 * wrong-state / gate-order → 409, self-approval / non-owner → 403.
 *
 * <p>Approver identity + workspace-owner role are read from {@code X-Actor} / {@code X-Workspace-Owner}
 * headers as a pragmatic input pending a full auth-principal wiring (there is no role model in the
 * codebase yet). The D4 decision is authoritative (publishes/rejects synchronously, atomic Decision +
 * AuditEntry via {@link PromotionGateService}); it then best-effort releases the parked
 * {@code knowledge-capture} process wait via the optional {@link CaptureWaitReleaser} SPI.
 */
@RestController
@RequestMapping("/api/v1/knowledge/candidates")
public class CandidateController {

    private final CaptureCandidateRepository candidateRepository;
    private final PrefilterFindingRepository findingRepository;
    private final PromotionGateRecordRepository gateRepository;
    private final RedactionService redactionService;
    private final PromotionGateService promotionGateService;
    private final ObjectProvider<CaptureWaitReleaser> waitReleaser;

    public CandidateController(CaptureCandidateRepository candidateRepository,
                              PrefilterFindingRepository findingRepository,
                              PromotionGateRecordRepository gateRepository,
                              RedactionService redactionService,
                              PromotionGateService promotionGateService,
                              ObjectProvider<CaptureWaitReleaser> waitReleaser) {
        this.candidateRepository = candidateRepository;
        this.findingRepository = findingRepository;
        this.gateRepository = gateRepository;
        this.redactionService = redactionService;
        this.promotionGateService = promotionGateService;
        this.waitReleaser = waitReleaser;
    }

    @GetMapping
    public List<CandidateSummary> list(@RequestParam(required = false) String status,
                                       @RequestParam(required = false) UUID caseId) {
        List<CaptureCandidate> candidates;
        if (status != null) {
            candidates = candidateRepository.findByStatusOrderByCreatedAtDesc(status);
        } else if (caseId != null) {
            candidates = candidateRepository.findByCaseInstanceIdOrderByCreatedAtDesc(caseId);
        } else {
            candidates = candidateRepository.findAll();
        }
        return candidates.stream()
                .filter(c -> caseId == null || c.getCaseInstanceId().equals(caseId))
                .map(CandidateSummary::of)
                .toList();
    }

    @GetMapping("/{candidateId}")
    public ResponseEntity<CandidateDetail> get(@PathVariable UUID candidateId) {
        Optional<CaptureCandidate> found = candidateRepository.findById(candidateId);
        if (found.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        CaptureCandidate c = found.get();
        UUID root = c.getRevisionOf() == null ? c.getId() : c.getRevisionOf();
        List<PrefilterFinding> findings = findingRepository.findByCandidateIdOrderBySpanStartAsc(root);
        List<PromotionGateRecord> gates = gateRepository.findByCandidateIdOrderByCreatedAtAsc(root);
        return ResponseEntity.ok(CandidateDetail.of(c, findings, gates));
    }

    @PostMapping("/{candidateId}/redaction")
    public ResponseEntity<RedactionResponse> redact(@PathVariable UUID candidateId,
                                                    @RequestHeader(value = "X-Actor", defaultValue = "curator") String actor,
                                                    @RequestBody RedactionRequest request) {
        try {
            UUID revisionId = redactionService.saveRedaction(
                    candidateId, actor, request.title(), request.content(), request.tags(), null);
            return ResponseEntity.status(HttpStatus.CREATED).body(new RedactionResponse(revisionId));
        } catch (IllegalCandidateTransitionException e) {
            // Candidate not in PREFILTERED state — wrong state for redaction.
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        } catch (GateOrderViolationException e) {
            // A CURATION PASS already exists for this candidate (e.g. the BPMN CuratorStepDelegate
            // already auto-redacted it) — redacting again violates the fixed gate order. 409, not 500.
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    @PostMapping("/{candidateId}/d4")
    public ResponseEntity<D4Response> d4(@PathVariable UUID candidateId,
                                         @RequestHeader(value = "X-Actor", defaultValue = "owner") String actor,
                                         @RequestHeader(value = "X-Workspace-Owner", defaultValue = "false")
                                         boolean isWorkspaceOwner,
                                         @RequestBody D4Request request) {
        // Validate the outcome explicitly: only APPROVE / REJECT are legal. Anything else (typo,
        // null, garbage) must be a 400 — never silently treated as REJECT, because REJECT is terminal
        // and would kill the candidate unrecoverably (contract: outcome enum [APPROVE, REJECT]).
        String outcome = request.outcome() == null ? "" : request.outcome().trim().toUpperCase();
        boolean approve;
        if ("APPROVE".equals(outcome)) {
            approve = true;
        } else if ("REJECT".equals(outcome)) {
            approve = false;
        } else {
            return ResponseEntity.badRequest().build();
        }
        KnowledgeScope scope;
        try {
            scope = request.scopeLevel() == null
                    ? KnowledgeScope.WORKSPACE : KnowledgeScope.valueOf(request.scopeLevel());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        UUID caseInstanceId = candidateRepository.findById(candidateId)
                .map(CaptureCandidate::getCaseInstanceId)
                .orElse(null);
        try {
            UUID publishedItemId = promotionGateService.decideD4(
                    candidateId, actor, isWorkspaceOwner, approve, scope, request.reason());
            // Release the parked capture process wait (best-effort; no-op in services-only flows).
            if (caseInstanceId != null) {
                waitReleaser.ifAvailable(r -> r.releaseD4Wait(caseInstanceId, approve));
            }
            return ResponseEntity.ok(new D4Response(approve ? "APPROVE" : "REJECT", publishedItemId));
        } catch (D4AuthorizationException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (GateOrderViolationException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    // ---- DTOs ------------------------------------------------------------------------------------

    public record RedactionRequest(String title, String content, List<String> tags) {}

    public record RedactionResponse(UUID revisionId) {}

    public record D4Request(String outcome, String scopeLevel, String reason) {}

    public record D4Response(String outcome, UUID publishedItemId) {}

    public record CandidateSummary(UUID id, UUID caseInstanceId, UUID projectId, int revision,
                                   UUID revisionOf, String title, String status,
                                   String rejectionStage, String rejectionReason) {
        static CandidateSummary of(CaptureCandidate c) {
            return new CandidateSummary(c.getId(), c.getCaseInstanceId(), c.getProjectId(), c.getRevision(),
                    c.getRevisionOf(), c.getTitle(), c.getStatus(), c.getRejectionStage(), c.getRejectionReason());
        }
    }

    public record FindingView(String category, int spanStart, int spanEnd, String source) {}

    public record GateView(String gate, String outcome, String actor, UUID decisionId, String detail) {}

    public record CandidateDetail(UUID id, UUID caseInstanceId, UUID projectId, int revision,
                                  UUID revisionOf, String title, String content, List<String> tags,
                                  String status, String rejectionStage, String rejectionReason,
                                  List<FindingView> prefilterFindings, List<GateView> gateRecords) {
        static CandidateDetail of(CaptureCandidate c, List<PrefilterFinding> findings,
                                  List<PromotionGateRecord> gates) {
            List<FindingView> f = findings.stream()
                    .map(x -> new FindingView(x.getCategory(), x.getSpanStart(), x.getSpanEnd(), x.getSource()))
                    .toList();
            List<GateView> g = gates.stream()
                    .map(x -> new GateView(x.getGate(), x.getOutcome(), x.getActor(), x.getDecisionId(), x.getDetail()))
                    .toList();
            return new CandidateDetail(c.getId(), c.getCaseInstanceId(), c.getProjectId(), c.getRevision(),
                    c.getRevisionOf(), c.getTitle(), c.getContent(), List.of(c.getTags()), c.getStatus(),
                    c.getRejectionStage(), c.getRejectionReason(), f, g);
        }
    }
}
