package com.d2os.casecore;

import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Escalation review + resolution (T059, FR-005, contracts/api.yaml). Resolution is
 * gate-comment-and-regenerate or cancel — never in-place editing of AI content (Principle II).
 *
 * <p>v1 scope: a Case escalates as a whole when a persona exhausts its revise loop, so escalations
 * are reported at Case granularity. "regenerate_with_comment" transitions the Case back to Running
 * and records the gate comment as a Decision; fully re-driving the Flowable job from the failed step
 * is future work (the state transition + decision record are real now).
 */
@RestController
@RequestMapping("/api/v1/cases/{caseId}/escalations")
public class EscalationController {

    private final CaseInstanceRepository caseRepository;
    private final CaseService caseService;

    public EscalationController(CaseInstanceRepository caseRepository, CaseService caseService) {
        this.caseRepository = caseRepository;
        this.caseService = caseService;
    }

    @GetMapping
    public ResponseEntity<List<EscalationView>> list(@PathVariable UUID caseId) {
        CaseInstance kase = caseRepository.findById(caseId).orElse(null);
        if (kase == null || kase.status() != CaseStatus.Escalated) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(List.of(new EscalationView(caseId, "case-level escalation: revise loop exhausted")));
    }

    @PostMapping("/{escalationId}/resolve")
    public ResponseEntity<Void> resolve(@PathVariable UUID caseId,
                                        @PathVariable UUID escalationId,
                                        @RequestBody ResolveRequest request) {
        switch (request.resolution()) {
            case "regenerate_with_comment" -> caseService.resume(caseId, "human:" + safe(request.gateComment()));
            case "cancel_case" -> caseService.cancel(caseId, "human:" + safe(request.gateComment()));
            default -> { return ResponseEntity.badRequest().build(); }
        }
        return ResponseEntity.ok().build();
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }

    public record EscalationView(UUID escalationId, String reason) {}

    public record ResolveRequest(@NotNull String resolution, String gateComment) {}
}
