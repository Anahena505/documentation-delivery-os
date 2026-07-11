package com.d2os.orchestration;

import com.d2os.governance.GateInstance.GateType;
import com.d2os.governance.GateService;
import com.d2os.tenancy.WorkspaceContext;
import com.d2os.tenancy.security.WorkspaceRlsBinder;
import org.flowable.engine.delegate.TaskListener;
import org.flowable.task.service.delegate.DelegateTask;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Engine userTask ↔ governance {@code GateInstance} sync point (T010 Phase 2, wired in Phase 3, research
 * R1) — same engine-coupling pattern as {@link PersonaStepDelegate}: this is the ONLY class in {@code
 * orchestration} allowed to reach into {@code governance} JPA/service state (plan.md Structure
 * Decision, enforced later by an ArchUnit rule, Phase 9 T048), keeping {@code governance} itself
 * engine-agnostic (it has no Flowable dependency at all).
 *
 * <p><b>T012/T013 wiring (Phase 3)</b>: bound via {@code <flowable:taskListener event="create"
 * delegateExpression="${gateTaskBridge}"/>} on the {@code review-gate.bpmn20.xml} /
 * {@code approval-gate.bpmn20.xml} gate userTask. Row creation + {@code GATE_OPENED} event emission are
 * delegated to {@link GateService#open}, not done directly here (the Phase 2 placeholder used the
 * repository directly; T014/T016 centralized that in governance so {@code GateEventPublisher} can wire
 * cleanly off one call site) — the complete-task-on-decide half lives in {@code GateService#decide}
 * via {@code EngineGateReleasePort}.
 *
 * <p><b>Expected variable contract</b> (satisfied by the gate callActivity's in-parameter mapping,
 * T012/T013/T015): the subprocess execution must carry {@code workspaceId} (same convention as {@link
 * PersonaStepDelegate}), {@code caseInstanceId}, {@code gateType} ({@code REVIEW}|{@code APPROVAL}),
 * {@code gateDefinitionKey}, {@code gateDefinitionVersion}, {@code inputsRef} (JSON string — the exact
 * information the decision is based on), and optionally {@code subjectArtifactRevisionId}, {@code
 * escalationPolicyKey}, {@code escalationPolicyVersion}.
 */
@Component("gateTaskBridge")
public class GateTaskBridge implements TaskListener {

    private final GateService gateService;
    private final WorkspaceRlsBinder workspaceRlsBinder;

    public GateTaskBridge(GateService gateService, WorkspaceRlsBinder workspaceRlsBinder) {
        this.gateService = gateService;
        this.workspaceRlsBinder = workspaceRlsBinder;
    }

    @Override
    @Transactional
    public void notify(DelegateTask delegateTask) {
        UUID workspaceId = UUID.fromString((String) delegateTask.getVariable("workspaceId"));
        UUID caseInstanceId = UUID.fromString((String) delegateTask.getVariable("caseInstanceId"));

        WorkspaceContext.set(workspaceId);
        try {
            // Re-bind RLS on this job's transaction connection — same reasoning as PersonaStepDelegate:
            // Flowable checked the connection out before WorkspaceContext could be set.
            workspaceRlsBinder.bindCurrentTransaction(workspaceId);

            GateType gateType = GateType.valueOf((String) delegateTask.getVariable("gateType"));
            String gateDefinitionKey = (String) delegateTask.getVariable("gateDefinitionKey");
            int gateDefinitionVersion = ((Number) delegateTask.getVariable("gateDefinitionVersion")).intValue();
            String inputsRef = (String) delegateTask.getVariable("inputsRef");

            Object subjectRevisionVar = delegateTask.getVariable("subjectArtifactRevisionId");
            UUID subjectArtifactRevisionId = subjectRevisionVar == null
                    ? null : UUID.fromString((String) subjectRevisionVar);

            String escalationPolicyKey = (String) delegateTask.getVariable("escalationPolicyKey");
            Object escalationPolicyVersionVar = delegateTask.getVariable("escalationPolicyVersion");
            Integer escalationPolicyVersion = escalationPolicyVersionVar == null
                    ? null : ((Number) escalationPolicyVersionVar).intValue();

            gateService.open(workspaceId, caseInstanceId, gateType, gateDefinitionKey, gateDefinitionVersion,
                    subjectArtifactRevisionId, inputsRef, escalationPolicyKey, escalationPolicyVersion,
                    delegateTask.getId());
        } finally {
            WorkspaceContext.clear();
        }
    }
}
