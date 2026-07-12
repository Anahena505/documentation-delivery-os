package com.d2os.orchestration;

import com.d2os.persona.PersonaExecutionService;
import com.d2os.tenancy.WorkspaceContext;
import com.d2os.tenancy.security.WorkspaceRlsBinder;
import java.util.UUID;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

/**
 * BPMN service-task delegate for one persona step of the Initiation pipeline (T027/T029-034).
 * Derives which persona to run from the activity id (thread-safe against the singleton Spring
 * delegate — no field injection into shared state) and delegates the real work to {@link
 * PersonaExecutionService}.
 *
 * <p>Binds {@link WorkspaceContext} from the process's {@code workspaceId} variable before running
 * — this delegate executes on the Flowable async job-executor thread pool, which has no HTTP
 * request (and so no {@code WorkspaceContextFilter}) to have set it otherwise.
 */
@Component("personaStepDelegate")
public class PersonaStepDelegate implements JavaDelegate {

  private final PersonaExecutionService personaExecutionService;
  private final WorkspaceRlsBinder workspaceRlsBinder;

  public PersonaStepDelegate(
      PersonaExecutionService personaExecutionService, WorkspaceRlsBinder workspaceRlsBinder) {
    this.personaExecutionService = personaExecutionService;
    this.workspaceRlsBinder = workspaceRlsBinder;
  }

  @Override
  public void execute(DelegateExecution execution) {
    UUID caseId = UUID.fromString(execution.getProcessInstanceBusinessKey());
    UUID workspaceId = UUID.fromString((String) execution.getVariable("workspaceId"));
    String personaKey = execution.getCurrentActivityId();

    // A concurrent (parallel-branch) execution has a parent scope; the top-level sequential
    // execution does not. Tag parallel steps with their branch execution id (US2, T024); leave
    // sequential steps null so replay/reconciliation can tell them apart.
    String branchId = execution.getParentId() != null ? execution.getId() : null;

    WorkspaceContext.set(workspaceId);
    try {
      // Re-bind RLS on this job's transaction connection: Flowable checked it out before we
      // could set WorkspaceContext, so it currently carries the nil system workspace.
      workspaceRlsBinder.bindCurrentTransaction(workspaceId);
      boolean validated = personaExecutionService.executePersona(caseId, personaKey, branchId);
      // Branch-local so the exclusive gateway right after a parallel specialist routes a failed
      // branch to its escalation wait state (FR-005) without affecting sibling branches.
      execution.setVariableLocal("validated", validated);
    } finally {
      WorkspaceContext.clear();
    }
  }
}
