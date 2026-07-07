package com.d2os.orchestration;

import com.d2os.casecore.CaseInstance;
import com.d2os.casecore.CaseInstanceRepository;
import com.d2os.casecore.CaseService;
import com.d2os.casecore.CaseStatus;
import com.d2os.casecore.ReconciliationRun;
import com.d2os.casecore.ReconciliationRunRepository;
import com.d2os.casecore.progress.ProgressEmitter;
import com.d2os.casecore.progress.ProgressEvent;
import com.d2os.tenancy.WorkspaceContext;
import com.d2os.tenancy.security.WorkspaceRlsBinder;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.job.api.Job;
import org.flowable.engine.ManagementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Dual-state reconciliation sweep (T025, FR-010, research R8): brings the workflow engine and the
 * domain Case state back into agreement, keyed by Case id, so concurrent execution never leaves the
 * audit trail inconsistent.
 *
 * <p>v1 detects the {@code DEAD_LETTER_JOB} divergence — an async branch job that exhausted its
 * retries (e.g. a persistent failure at the parallel join) leaves the engine stuck while the domain
 * Case still reads Running. That case would otherwise hang forever; the sweep marks it Escalated so a
 * human is pulled in, and records an auditable {@link ReconciliationRun}. It reads Flowable's engine
 * tables (which carry no RLS) to discover the stuck job, then binds the case's own workspace before
 * touching any RLS-scoped domain table. Healthy cases (including branches legitimately parked at an
 * escalation {@code receiveTask}) have no dead-letter jobs, so a clean sweep writes nothing.
 */
@Component
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    private final ManagementService managementService;
    private final RuntimeService runtimeService;
    private final CaseInstanceRepository caseRepository;
    private final CaseService caseService;
    private final ReconciliationRunRepository reconciliationRepository;
    private final ProgressEmitter progressEmitter;
    private final WorkspaceRlsBinder workspaceRlsBinder;

    public ReconciliationJob(ManagementService managementService,
                             RuntimeService runtimeService,
                             CaseInstanceRepository caseRepository,
                             CaseService caseService,
                             ReconciliationRunRepository reconciliationRepository,
                             ProgressEmitter progressEmitter,
                             WorkspaceRlsBinder workspaceRlsBinder) {
        this.managementService = managementService;
        this.runtimeService = runtimeService;
        this.caseRepository = caseRepository;
        this.caseService = caseService;
        this.reconciliationRepository = reconciliationRepository;
        this.progressEmitter = progressEmitter;
        this.workspaceRlsBinder = workspaceRlsBinder;
    }

    private static final int REPAIR_RETRIES = 5;

    @Scheduled(fixedDelayString = "${d2os.orchestration.reconciliation-interval-ms:60000}",
               initialDelayString = "${d2os.orchestration.reconciliation-interval-ms:60000}")
    public void sweep() {
        List<Job> deadLetterJobs = managementService.createDeadLetterJobQuery().list();
        for (Job job : deadLetterJobs) {
            try {
                reconcileDeadLetter(job.getId(), job.getProcessInstanceId(), job.getExceptionMessage());
            } catch (Exception e) {
                log.warn("reconciliation failed for process {}: {}", job.getProcessInstanceId(), e.toString());
            }
        }
    }

    /**
     * A dead-letter job caused by a transient concurrency conflict at the parallel join (Flowable
     * optimistic locking under {@code exclusive=false}) is REPAIRED — moved back to the executable
     * queue to re-run once the contending sibling has committed. Only a genuinely failing job (a real
     * delegate error) ESCALATES the Case. This is the repair-vs-escalate distinction of research R8.
     * Own transaction per job so one bad case never rolls back the whole sweep.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reconcileDeadLetter(String jobId, String processInstanceId, String exceptionMessage) {
        if (processInstanceId == null) return;
        ProcessInstance pi = runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        if (pi == null || pi.getBusinessKey() == null) return;

        UUID caseId = UUID.fromString(pi.getBusinessKey());
        Object wsVar = runtimeService.getVariable(processInstanceId, "workspaceId");
        if (wsVar == null) return;
        UUID workspaceId = UUID.fromString(wsVar.toString());

        boolean transientConflict = isTransientConcurrencyConflict(exceptionMessage);

        WorkspaceContext.set(workspaceId);
        try {
            workspaceRlsBinder.bindCurrentTransaction(workspaceId);
            Optional<CaseInstance> found = caseRepository.findById(caseId);
            if (found.isEmpty()) return;
            CaseStatus status = found.get().status();
            if (status == CaseStatus.Delivered || status == CaseStatus.Cancelled) {
                return;   // already terminal — nothing to reconcile
            }

            String engineState = "{\"deadLetterJob\":true,\"transient\":" + transientConflict
                    + ",\"exception\":" + jsonString(exceptionMessage) + "}";
            String domainState = "{\"caseStatus\":\"" + status.name() + "\"}";

            if (transientConflict) {
                // Self-heal: re-queue the branch job; the contention is gone once its sibling committed.
                managementService.moveDeadLetterJobToExecutableJob(jobId, REPAIR_RETRIES);
                reconciliationRepository.save(new ReconciliationRun(
                        UUID.randomUUID(), workspaceId, caseId, ReconciliationRun.Divergence.DEAD_LETTER_JOB,
                        engineState, domainState, ReconciliationRun.Action.REPAIRED));
                progressEmitter.emit(workspaceId, caseId, ProgressEvent.Kind.RECONCILED, null,
                        "{\"divergence\":\"DEAD_LETTER_JOB\",\"action\":\"REPAIRED\"}");
                log.info("reconciled case {} (transient join conflict) -> re-queued branch job", caseId);
            } else if (status != CaseStatus.Escalated) {
                reconciliationRepository.save(new ReconciliationRun(
                        UUID.randomUUID(), workspaceId, caseId, ReconciliationRun.Divergence.DEAD_LETTER_JOB,
                        engineState, domainState, ReconciliationRun.Action.ESCALATED));
                caseService.escalate(caseId, "reconciliation: dead-letter job — " + exceptionMessage);
                progressEmitter.emit(workspaceId, caseId, ProgressEvent.Kind.RECONCILED, null,
                        "{\"divergence\":\"DEAD_LETTER_JOB\",\"action\":\"ESCALATED\"}");
                log.info("reconciled stuck case {} (unrecoverable dead-letter job) -> Escalated", caseId);
            }
        } finally {
            WorkspaceContext.clear();
        }
    }

    private boolean isTransientConcurrencyConflict(String exceptionMessage) {
        if (exceptionMessage == null) return false;
        String m = exceptionMessage.toLowerCase();
        return m.contains("updated by another transaction")
                || m.contains("optimisticlock")
                || m.contains("concurrently");
    }

    private String jsonString(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
