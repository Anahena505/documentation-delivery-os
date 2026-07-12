package com.d2os.orchestration;

import com.d2os.knowledge.capture.CaptureService;
import com.d2os.tenancy.WorkspaceContext;
import com.d2os.tenancy.security.WorkspaceRlsBinder;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

/**
 * BPMN service-task delegate for the capture step of the {@code knowledge-capture} process
 * (T019/T021, FR-008). Harvests the delivered case's lessons-learned candidate(s) via {@link
 * CaptureService} and pins the (first) candidate id onto the process as the {@code candidateId}
 * variable so the downstream pre-filter / curator steps operate on it.
 *
 * <p>Binds {@link WorkspaceContext} + RLS from the process {@code workspaceId} variable (async
 * job-executor thread, no HTTP request), as the other capture/initiation delegates do.
 */
@Component("captureStepDelegate")
public class CaptureStepDelegate implements JavaDelegate {

  private final CaptureService captureService;
  private final WorkspaceRlsBinder workspaceRlsBinder;

  public CaptureStepDelegate(CaptureService captureService, WorkspaceRlsBinder workspaceRlsBinder) {
    this.captureService = captureService;
    this.workspaceRlsBinder = workspaceRlsBinder;
  }

  @Override
  public void execute(DelegateExecution execution) {
    UUID caseInstanceId = UUID.fromString((String) execution.getVariable("caseInstanceId"));
    UUID workspaceId = UUID.fromString((String) execution.getVariable("workspaceId"));

    WorkspaceContext.set(workspaceId);
    try {
      workspaceRlsBinder.bindCurrentTransaction(workspaceId);
      List<UUID> candidateIds = captureService.captureFrom(caseInstanceId);
      if (candidateIds.isEmpty()) {
        throw new NoSuchElementException(
            "no capture candidate produced for case " + caseInstanceId);
      }
      // v1 harvest produces a single candidate per case; pin it for the downstream gates.
      execution.setVariable("candidateId", candidateIds.get(0).toString());
    } finally {
      WorkspaceContext.clear();
    }
  }
}
