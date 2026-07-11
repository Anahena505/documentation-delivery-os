package com.d2os.governance;

import com.d2os.casecore.AuditWriter;
import com.d2os.casecore.CaseInstanceRepository;
import com.d2os.casecore.DecisionRecord;
import com.d2os.casecore.DecisionRepository;
import com.d2os.governance.GateInstance.GateType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Opens and decides Review/Approval gates (Phase 5, T014, research R1, FR-002/003/004).
 *
 * <p>{@code open} is called by {@code GateTaskBridge} (orchestration) on the engine gate userTask's
 * {@code create} event — it persists the {@link GateInstance} row and emits {@code GATE_OPENED}, both
 * in the transaction {@code GateTaskBridge}'s {@code TaskListener} already opened. Centralizing the
 * row-creation + event-emission here (rather than in {@code GateTaskBridge} directly, as the Phase 2
 * placeholder did) is what lets {@code GateEventPublisher} (T016) wire cleanly off both {@code open}
 * and {@code decide}.
 *
 * <p>{@code decide} accepts EXACTLY the three verbs APPROVE / REJECT / REQUEST_CHANGES (Q4) — REOPEN
 * is a distinct, later-phase operation ({@code ReopenService}, US3) never routed through here. Writes
 * the {@link DecisionRecord} + AuditEntry + outbox event in the SAME transaction as the {@link
 * GateInstance} state transition (Principle V), then best-effort releases the engine's parked userTask
 * via the {@link EngineGateReleasePort} SPI — keeping {@code governance} itself Flowable-free (plan.md
 * Structure Decision): the port interface lives here, {@code orchestration} supplies the
 * implementation wrapping {@code TaskService.complete(...)}, mirroring the existing {@code
 * CaptureWaitReleaser}/{@code CaptureWaitReleaserImpl} SPI pattern (Phase 3, T019/T026).
 */
@Service
public class GateService {

    /** The three decision verbs {@code decide} accepts (Q4) — REOPEN is out of scope (US3). */
    public enum Verb { APPROVE, REJECT, REQUEST_CHANGES }

    private final GateInstanceRepository gateInstanceRepository;
    private final DecisionRepository decisionRepository;
    private final CaseInstanceRepository caseInstanceRepository;
    private final AuditWriter auditWriter;
    private final GateEventPublisher gateEventPublisher;
    private final ObjectProvider<EngineGateReleasePort> engineGateReleasePort;

    public GateService(GateInstanceRepository gateInstanceRepository,
                       DecisionRepository decisionRepository,
                       CaseInstanceRepository caseInstanceRepository,
                       AuditWriter auditWriter,
                       GateEventPublisher gateEventPublisher,
                       ObjectProvider<EngineGateReleasePort> engineGateReleasePort) {
        this.gateInstanceRepository = gateInstanceRepository;
        this.decisionRepository = decisionRepository;
        this.caseInstanceRepository = caseInstanceRepository;
        this.auditWriter = auditWriter;
        this.gateEventPublisher = gateEventPublisher;
        this.engineGateReleasePort = engineGateReleasePort;
    }

    /**
     * Open a new gate occurrence. Called by {@code GateTaskBridge} when the engine's gate userTask is
     * created — {@code inputsRef} is the exact information the eventual decision will be based on
     * (artifact revisions, rubric scores, delta report id), captured now so it never has to be
     * re-derived later (data-model.md).
     */
    @Transactional
    public GateInstance open(UUID workspaceId, UUID caseInstanceId, GateType gateType,
                             String gateDefinitionKey, int gateDefinitionVersion,
                             UUID subjectArtifactRevisionId, String inputsRef,
                             String escalationPolicyKey, Integer escalationPolicyVersion,
                             String engineTaskId) {
        GateInstance gate = new GateInstance(UUID.randomUUID(), workspaceId, caseInstanceId, gateType,
                gateDefinitionKey, gateDefinitionVersion, subjectArtifactRevisionId, inputsRef,
                escalationPolicyKey, escalationPolicyVersion, engineTaskId);
        gateInstanceRepository.save(gate);
        gateEventPublisher.publishOpened(gate);
        return gate;
    }

    /**
     * Decide an OPEN gate. Order of checks matches the design (T014): gate must be OPEN (else {@link
     * IllegalGateTransitionException}, 409), actor must not be barred by non-self-review (else {@link
     * SelfReviewNotAllowedException}, 403), then the Decision + AuditEntry + outbox event are written
     * and the gate transitions, all in this one transaction, before best-effort releasing the engine's
     * parked userTask.
     */
    @Transactional
    public GateInstance decide(UUID gateId, Verb verb, String actorId, String comments) {
        GateInstance gate = gateInstanceRepository.findById(gateId)
                .orElseThrow(() -> new NoSuchElementException("gate " + gateId));

        GateStatus target = switch (verb) {
            case APPROVE -> GateStatus.APPROVED;
            case REJECT -> GateStatus.REJECTED;
            case REQUEST_CHANGES -> GateStatus.REGENERATING;
        };
        if (gate.status() != GateStatus.OPEN) {
            // Not decidable — 409. (transitionTo below would throw the same exception once the state
            // machine actually mutates; checking here up front matches the specified check order and
            // avoids constructing a Decision row for a gate that was never going to accept it.)
            throw new IllegalGateTransitionException(gate.status(), target);
        }

        requireNotSelfReview(gate, actorId);

        String decisionType = switch (verb) {
            case APPROVE -> "GATE_APPROVE";
            case REJECT -> "GATE_REJECT";
            case REQUEST_CHANGES -> "GATE_REQUEST_CHANGES";
        };
        UUID decisionId = UUID.randomUUID();
        OffsetDateTime decidedAt = OffsetDateTime.now();
        decisionRepository.save(new DecisionRecord(decisionId, gate.getWorkspaceId(), gate.getCaseInstanceId(),
                decisionType, actorId, comments, gate.getId().toString()));

        gate.transitionTo(target);
        gate.recordDecision(decisionId, decidedAt);
        if (verb == Verb.REQUEST_CHANGES) {
            gate.recordReviewerComments(comments);
        }
        gateInstanceRepository.save(gate);

        auditWriter.record(gate.getWorkspaceId(), "gate_instance", gate.getId(), decisionType, actorId,
                Map.of("verb", verb.name(), "comments", comments == null ? "" : comments));

        gateEventPublisher.publishDecided(gate, verb, actorId, decisionId);

        // Best-effort/idempotent, same reasoning as CaptureWaitReleaser: a services-only slice with no
        // orchestration bean on the classpath gets a no-op — the authoritative Decision above already
        // committed regardless of whether an engine task is actually parked.
        engineGateReleasePort.ifAvailable(port -> port.completeGateTask(gate.getEngineTaskId(), verb.name()));

        return gate;
    }

    /**
     * Non-self-review (FR-018, mirroring {@code PromotionGateService}'s D4 non-self-satisfiable check).
     * Gates review AI-drafted artifacts: persona execution has no human "author" field anywhere in the
     * schema ({@code operation_execution}/{@code persona_invocation} are system-authored rows), so
     * there is no direct producing-actor id to compare against. The closest honest analog to "the
     * artifact's own author" available here is the Case's own submitter ({@code
     * CaseInstance.createdBy}): the person who submitted/owns the case must not also be the one who
     * decides its own gate.
     */
    private void requireNotSelfReview(GateInstance gate, String actorId) {
        if (actorId == null) {
            return;
        }
        caseInstanceRepository.findById(gate.getCaseInstanceId()).ifPresent(kase -> {
            if (actorId.equals(kase.getCreatedBy())) {
                throw new SelfReviewNotAllowedException(
                        "actor " + actorId + " is the case's own submitter and may not decide its gate " + gate.getId());
            }
        });
    }
}
