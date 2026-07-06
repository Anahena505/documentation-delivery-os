package com.d2os.orchestration;

import com.d2os.artifacts.ArtifactRevision;
import com.d2os.artifacts.ArtifactService;
import com.d2os.artifacts.ExecutionPackage;
import com.d2os.artifacts.HandoverRecordService;
import com.d2os.artifacts.PackageAssemblyService;
import com.d2os.casecore.CaseDefinitionSnapshot;
import com.d2os.casecore.CaseDefinitionSnapshotRepository;
import com.d2os.casecore.CaseInstance;
import com.d2os.casecore.CaseInstanceRepository;
import com.d2os.casecore.CaseService;
import com.d2os.observability.KpiEmitter;
import com.d2os.tenancy.WorkspaceContext;
import com.d2os.tenancy.security.WorkspaceRlsBinder;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Final BPMN step of the Initiation pipeline (T036/T037/T038 wiring): materializes artifacts from
 * every validated persona output, assembles the hash-stamped package, builds the Handover Record,
 * and transitions the Case to Delivered — all after the sequential persona-1→3 steps complete.
 *
 * <p>Binds {@link WorkspaceContext} from the process's {@code workspaceId} variable <em>before</em>
 * touching the database — this delegate runs on the Flowable async job-executor thread pool (no
 * HTTP request, no {@code WorkspaceContextFilter}), and even the first {@code CaseInstance} lookup
 * below requires RLS context to already be bound.
 */
@Component("assemblePackageDelegate")
public class AssemblePackageDelegate implements JavaDelegate {

    private final ArtifactService artifactService;
    private final PackageAssemblyService packageAssemblyService;
    private final HandoverRecordService handoverRecordService;
    private final CaseInstanceRepository caseInstanceRepository;
    private final CaseDefinitionSnapshotRepository snapshotRepository;
    private final CaseService caseService;
    private final WorkspaceRlsBinder workspaceRlsBinder;
    private final KpiEmitter kpiEmitter;

    public AssemblePackageDelegate(ArtifactService artifactService,
                                   PackageAssemblyService packageAssemblyService,
                                   HandoverRecordService handoverRecordService,
                                   CaseInstanceRepository caseInstanceRepository,
                                   CaseDefinitionSnapshotRepository snapshotRepository,
                                   CaseService caseService,
                                   WorkspaceRlsBinder workspaceRlsBinder,
                                   KpiEmitter kpiEmitter) {
        this.artifactService = artifactService;
        this.packageAssemblyService = packageAssemblyService;
        this.handoverRecordService = handoverRecordService;
        this.caseInstanceRepository = caseInstanceRepository;
        this.snapshotRepository = snapshotRepository;
        this.caseService = caseService;
        this.workspaceRlsBinder = workspaceRlsBinder;
        this.kpiEmitter = kpiEmitter;
    }

    @Override
    @Transactional
    public void execute(DelegateExecution execution) {
        UUID caseId = UUID.fromString(execution.getProcessInstanceBusinessKey());
        UUID workspaceId = UUID.fromString((String) execution.getVariable("workspaceId"));

        WorkspaceContext.set(workspaceId);
        try {
            workspaceRlsBinder.bindCurrentTransaction(workspaceId);
            CaseInstance kase = caseInstanceRepository.findById(caseId)
                    .orElseThrow(() -> new NoSuchElementException("case " + caseId));

            List<ArtifactRevision> revisions = artifactService.materializeForCase(kase.getWorkspaceId(), caseId);
            if (revisions.isEmpty()) {
                // No persona validated (all escalated) — nothing to deliver; the Case is already
                // Escalated by PersonaExecutionService, so this step simply becomes a no-op.
                return;
            }

            ExecutionPackage pkg = packageAssemblyService.assemble(kase.getWorkspaceId(), caseId);

            CaseDefinitionSnapshot snapshot = snapshotRepository.findByCaseInstanceId(caseId)
                    .orElseThrow(() -> new IllegalStateException("case " + caseId + " has no pinned snapshot"));
            handoverRecordService.create(kase.getWorkspaceId(), pkg, kase.getSubmissionId(), snapshot.getId());

            // KPIs (FR-015): completeness = artifacts delivered; cost = tokens spent this Case.
            kpiEmitter.emit(kase.getWorkspaceId(), KpiEmitter.PACKAGE_COMPLETENESS, caseId, revisions.size());
            kpiEmitter.emit(kase.getWorkspaceId(), KpiEmitter.CASE_COST_TOKENS, caseId, kase.getTokensSpent());

            caseService.deliver(caseId);
        } finally {
            WorkspaceContext.clear();
        }
    }
}
