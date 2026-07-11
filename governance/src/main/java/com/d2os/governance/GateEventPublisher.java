package com.d2os.governance;

import com.d2os.casecore.AuditWriter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Emits the gate lifecycle outbox events (T016, research R8, FR-019) via the existing {@link
 * AuditWriter} mechanism — there is no separate "EventPublisher" abstraction elsewhere in this repo;
 * {@code AuditWriter.record} IS the same-transaction audit-row + outbox-row writer. Every payload
 * matches contracts/api.yaml's {@code GateEventPayload} schema (the projection-sufficient tuple Phase
 * 7's projector depends on) — {@code MANDATORY} propagation means these can only be called from
 * within {@code GateService.open}/{@code decide}'s already-open transaction, so the event is
 * guaranteed to commit or roll back atomically with the gate state change it describes.
 *
 * <p>Only {@code GATE_OPENED} and {@code GATE_DECIDED} are wired in this phase (US1); the other five
 * {@code GateEventPayload} event types ({@code GATE_REOPEN_CANDIDATE}, {@code GATE_IMPACT_ASSESSED},
 * {@code GATE_REOPENED}, {@code GATE_ESCALATION_FIRED}, {@code GATE_REGENERATION_TRIGGERED}) are later
 * phases' (US2-US4) responsibility, added to this same class as those phases land.
 */
@Component
public class GateEventPublisher {

    private final AuditWriter auditWriter;

    public GateEventPublisher(AuditWriter auditWriter) {
        this.auditWriter = auditWriter;
    }

    /** {@code GATE_OPENED} — no human decider yet, so the audit actor is the system engine bridge. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishOpened(GateInstance gate) {
        Map<String, Object> payload = basePayload("GATE_OPENED", gate);
        auditWriter.record(gate.getWorkspaceId(), "gate_instance", gate.getId(), "GATE_OPENED",
                "system:engine", payload);
    }

    /** {@code GATE_DECIDED} — carries the verb, decider, and Decision id (FR-002/019). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishDecided(GateInstance gate, GateService.Verb verb, String deciderId, UUID decisionId) {
        Map<String, Object> payload = basePayload("GATE_DECIDED", gate);
        payload.put("decisionVerb", verb.name());
        payload.put("deciderId", deciderId);
        payload.put("decisionId", decisionId == null ? null : decisionId.toString());
        auditWriter.record(gate.getWorkspaceId(), "gate_instance", gate.getId(), "GATE_DECIDED",
                deciderId, payload);
    }

    /**
     * {@code GATE_REGENERATION_TRIGGERED} (T022, research R8, FR-019) — emitted by {@code
     * GateTaskBridge} (orchestration) at the moment it opens the NEW gate cycle a comment-and-regenerate
     * re-entry produced (the {@code regenerationDeltaReportId} process variable {@code
     * RegenerationDelegate} set is non-null), carrying the produced revision id per contracts/api.yaml's
     * {@code GateEventPayload.producedArtifactRevisionId}. Uses plain (non-MANDATORY) {@code
     * @Transactional} — unlike {@link #publishOpened}/{@link #publishDecided}, this is not always called
     * from inside {@code GateService}'s own transaction, so it opens/joins one itself; {@link
     * AuditWriter#record}'s MANDATORY requirement is still satisfied because a transaction is active by
     * the time that call runs.
     */
    @Transactional
    public void publishRegenerationTriggered(GateInstance newGate, UUID producedArtifactRevisionId) {
        Map<String, Object> payload = basePayload("GATE_REGENERATION_TRIGGERED", newGate);
        payload.put("producedArtifactRevisionId",
                producedArtifactRevisionId == null ? null : producedArtifactRevisionId.toString());
        auditWriter.record(newGate.getWorkspaceId(), "gate_instance", newGate.getId(), "GATE_REGENERATION_TRIGGERED",
                "system:engine", payload);
    }

    /** The projection-sufficient tuple every {@code GateEventPayload} shares (contracts/api.yaml). */
    private Map<String, Object> basePayload(String eventType, GateInstance gate) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", eventType);
        payload.put("gateId", gate.getId().toString());
        payload.put("gateType", gate.getGateType().name());
        payload.put("gateDefinitionKey", gate.getGateDefinitionKey());
        payload.put("gateDefinitionVersion", gate.getGateDefinitionVersion());
        payload.put("caseInstanceId", gate.getCaseInstanceId().toString());
        payload.put("workspaceId", gate.getWorkspaceId().toString());
        payload.put("subjectArtifactRevisionId",
                gate.getSubjectArtifactRevisionId() == null ? null : gate.getSubjectArtifactRevisionId().toString());
        payload.put("escalationPolicyKey", gate.getEscalationPolicyKey());
        payload.put("escalationPolicyVersion", gate.getEscalationPolicyVersion());
        payload.put("occurredAt", OffsetDateTime.now().toString());
        return payload;
    }
}
