package com.d2os.orchestration;

import com.d2os.knowledge.capture.SensitivityPreFilter;
import com.d2os.tenancy.WorkspaceContext;
import com.d2os.tenancy.security.WorkspaceRlsBinder;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * BPMN service-task delegate for the deterministic pre-filter gate of the {@code knowledge-capture}
 * process (T022, FR-010). Runs {@link SensitivityPreFilter} over the candidate captured by the preceding
 * capture step (its id rides the process as the {@code candidateId} variable), which records the PII/
 * sensitivity findings, redacts detected spans out of the content by default (T3-c), and transitions the
 * candidate CAPTURED → PREFILTERED.
 *
 * <p>Binds {@link WorkspaceContext} + RLS from the process {@code workspaceId} variable before running —
 * capture runs on the Flowable async job-executor thread pool with no HTTP request to have set it,
 * exactly as {@link PersonaStepDelegate} does for the initiation pipeline.
 */
@Component("preFilterDelegate")
public class PreFilterDelegate implements JavaDelegate {

    private final SensitivityPreFilter preFilter;
    private final WorkspaceRlsBinder workspaceRlsBinder;

    public PreFilterDelegate(SensitivityPreFilter preFilter, WorkspaceRlsBinder workspaceRlsBinder) {
        this.preFilter = preFilter;
        this.workspaceRlsBinder = workspaceRlsBinder;
    }

    @Override
    public void execute(DelegateExecution execution) {
        UUID workspaceId = UUID.fromString((String) execution.getVariable("workspaceId"));
        UUID candidateId = UUID.fromString((String) execution.getVariable("candidateId"));

        WorkspaceContext.set(workspaceId);
        try {
            workspaceRlsBinder.bindCurrentTransaction(workspaceId);
            // v1 harvest carries no propagated intake sensitivity terms into the process; the pattern
            // detectors run unconditionally and the tag-propagation channel stays open for a future
            // harvest that threads the source submission's sensitive field values through here.
            preFilter.prefilter(candidateId, List.of());
        } finally {
            WorkspaceContext.clear();
        }
    }
}
