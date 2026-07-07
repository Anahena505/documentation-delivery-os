package com.d2os.orchestration;

import com.d2os.casecore.CaseInstance;
import com.d2os.casecore.CaseService;
import com.d2os.casecore.WorkflowInstance;
import com.d2os.casecore.WorkflowInstanceRepository;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.ProcessInstance;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Starts the Initiation pipeline for a planned Case (T028): transitions Planned → Running, launches
 * the embedded Flowable process (async → queue-and-resume, NFR-4), and records the
 * engine↔Case correlation in {@code workflow_instance}. All in one transaction, so a failed engine
 * start rolls back the state change.
 */
@Service
public class CaseStartService {

    // Phase 2: new Cases run the full-suite canonical workflow (initiation-v2). The Phase 1
    // "initiation" process stays deployed only so already-pinned v1 Cases remain replayable (Principle I).
    // Single case type in v1/v2, so the key is a constant; snapshot-driven resolution is a later refinement.
    private static final String PROCESS_KEY = "initiation-v2";

    private final CaseService caseService;
    private final RuntimeService runtimeService;
    private final WorkflowInstanceRepository workflowInstanceRepository;

    public CaseStartService(CaseService caseService,
                            RuntimeService runtimeService,
                            WorkflowInstanceRepository workflowInstanceRepository) {
        this.caseService = caseService;
        this.runtimeService = runtimeService;
        this.workflowInstanceRepository = workflowInstanceRepository;
    }

    @Transactional
    public void start(UUID caseId) {
        CaseInstance kase = caseService.startRunning(caseId);   // Planned -> Running (guarded)

        // workspaceId rides along as a process variable: async job-executor threads run outside
        // any HTTP request (no WorkspaceContextFilter), so they have no other way to learn which
        // workspace's RLS context to bind before touching the database (T045 hardening).
        ProcessInstance pi = runtimeService.startProcessInstanceByKey(
                PROCESS_KEY,
                caseId.toString(),                              // businessKey = case id (correlation)
                Map.of("caseId", caseId.toString(), "workspaceId", kase.getWorkspaceId().toString()));

        workflowInstanceRepository.save(new WorkflowInstance(
                UUID.randomUUID(), kase.getWorkspaceId(), caseId, pi.getId()));
    }
}
