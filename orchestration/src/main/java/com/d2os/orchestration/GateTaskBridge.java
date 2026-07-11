package com.d2os.orchestration;

import com.d2os.governance.GateInstance;
import com.d2os.governance.GateInstance.GateType;
import com.d2os.governance.GateInstanceRepository;
import com.d2os.tenancy.WorkspaceContext;
import com.d2os.tenancy.security.WorkspaceRlsBinder;
import org.flowable.engine.delegate.TaskListener;
import org.flowable.task.service.delegate.DelegateTask;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Engine userTask ↔ governance {@link GateInstance} sync point (Phase 5, T010, research R1) — same
 * engine-coupling pattern as {@link PersonaStepDelegate}: this is the ONLY class in {@code
 * orchestration} allowed to reach into {@code governance} JPA state (plan.md Structure Decision,
 * enforced later by an ArchUnit rule, Phase 9 T048), keeping {@code governance} itself engine-agnostic
 * (it has no Flowable dependency at all).
 *
 * <p><b>Scope of this task (T010, Phase 2 Foundational)</b>: only the create-gate-row-on-subprocess-
 * entry half is implemented here — a Flowable {@link TaskListener} bound to the {@code create} event
 * of a gate userTask, which opens the {@link GateInstance} row and correlates {@code engine_task_id}.
 * It is a real, working class, but it is <b>not yet wired into any BPMN</b>: the gate subprocess
 * definitions ({@code review-gate.bpmn20.xml} / {@code approval-gate.bpmn20.xml}) that declare a
 * {@code <taskListener event="create" delegateExpression="${gateTaskBridge}"/>} on their userTask
 * land in Phase 3 (T012/T013); the complete-task-on-decide half is {@code GateService.decide()} in
 * that same phase (T014). Until then this bean is dead code from the engine's point of view.
 *
 * <p><b>Expected variable contract</b> (to be satisfied by the gate callActivity's in-parameter
 * mapping when T012/T013 wire it up): the subprocess execution must carry {@code workspaceId} (same
 * convention as {@link PersonaStepDelegate}), {@code caseInstanceId}, {@code gateType}
 * ({@code REVIEW}|{@code APPROVAL}), {@code gateDefinitionKey}, {@code gateDefinitionVersion},
 * {@code inputsRef} (JSON string — the exact information the decision is based on), and optionally
 * {@code subjectArtifactRevisionId}, {@code escalationPolicyKey}, {@code escalationPolicyVersion}.
 */
@Component("gateTaskBridge")
public class GateTaskBridge implements TaskListener {

    private final GateInstanceRepository gateInstanceRepository;
    private final WorkspaceRlsBinder workspaceRlsBinder;

    public GateTaskBridge(GateInstanceRepository gateInstanceRepository,
                          WorkspaceRlsBinder workspaceRlsBinder) {
        this.gateInstanceRepository = gateInstanceRepository;
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

            GateInstance gateInstance = new GateInstance(
                    UUID.randomUUID(), workspaceId, caseInstanceId, gateType,
                    gateDefinitionKey, gateDefinitionVersion, subjectArtifactRevisionId, inputsRef,
                    escalationPolicyKey, escalationPolicyVersion, delegateTask.getId());
            gateInstanceRepository.save(gateInstance);
        } finally {
            WorkspaceContext.clear();
        }
    }
}
