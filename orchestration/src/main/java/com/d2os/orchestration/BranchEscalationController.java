package com.d2os.orchestration;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Resolves a branch-level persona escalation from the parallel block (T023, FR-005, contracts/api.yaml).
 *
 * <p>Distinct from the Phase 1 {@code EscalationController} (case-level, {@code /escalations/...}):
 * this operates on a specific parallel-branch {@code invocationId} and releases that branch's parked
 * receiveTask so the join can proceed, retaining every sibling's output. Path uses {@code /branches}
 * to avoid ambiguous mapping with the case-level resolver.
 */
@RestController
public class BranchEscalationController {

    private final EscalationBridge escalationBridge;

    public BranchEscalationController(EscalationBridge escalationBridge) {
        this.escalationBridge = escalationBridge;
    }

    @PostMapping("/api/v1/cases/{caseId}/branches/{invocationId}/resolve")
    public ResponseEntity<Void> resolve(@PathVariable UUID caseId,
                                        @PathVariable UUID invocationId,
                                        @RequestBody ResolveBranchRequest request) {
        EscalationBridge.Action action;
        try {
            action = EscalationBridge.Action.valueOf(request.action());
        } catch (IllegalArgumentException | NullPointerException e) {
            return ResponseEntity.badRequest().build();
        }
        boolean released = escalationBridge.resolveBranch(caseId, invocationId, action, request.rationale());
        // 409 if the invocation is not an escalated, parked branch (nothing to release).
        return released ? ResponseEntity.ok().build() : ResponseEntity.status(409).build();
    }

    public record ResolveBranchRequest(String action, String rationale) {}
}
