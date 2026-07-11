package com.d2os.orchestration;

import com.d2os.casecore.CaseService;
import com.d2os.persona.consistency.ConsistencyService;
import com.d2os.tenancy.WorkspaceContext;
import com.d2os.tenancy.security.WorkspaceRlsBinder;
import java.util.UUID;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

/**
 * Runs the Consistency-Check after the parallel join (US3, T030). Delegates to {@link
 * ConsistencyService} for the two tiers, then routes:
 *
 * <ul>
 *   <li>a DETERMINISTIC contradiction hard-blocks — the Case is escalated for a human and the
 *       branch routes to the consistency wait state (FR-007);
 *   <li>a failing SEMANTIC review has already escalated the Case (advisory, FR-008);
 *   <li>a clean check flows straight on to the remaining personas.
 * </ul>
 *
 * The branch-local {@code consistencyClean} flag drives the following gateway.
 */
@Component("consistencyCheckDelegate")
public class ConsistencyCheckDelegate implements JavaDelegate {

  private final ConsistencyService consistencyService;
  private final CaseService caseService;
  private final WorkspaceRlsBinder workspaceRlsBinder;

  public ConsistencyCheckDelegate(
      ConsistencyService consistencyService,
      CaseService caseService,
      WorkspaceRlsBinder workspaceRlsBinder) {
    this.consistencyService = consistencyService;
    this.caseService = caseService;
    this.workspaceRlsBinder = workspaceRlsBinder;
  }

  @Override
  public void execute(DelegateExecution execution) {
    UUID caseId = UUID.fromString(execution.getProcessInstanceBusinessKey());
    UUID workspaceId = UUID.fromString((String) execution.getVariable("workspaceId"));

    WorkspaceContext.set(workspaceId);
    try {
      workspaceRlsBinder.bindCurrentTransaction(workspaceId);
      boolean clean = consistencyService.runCheck(caseId, workspaceId);
      if (!clean && consistencyService.deterministicBlockingRemains(caseId)) {
        // Semantic-only failures are already escalated by the reviewer's own path; a
        // deterministic contradiction is escalated here so a human must fix it before delivery.
        caseService.escalate(
            caseId, "consistency: deterministic cross-artifact contradiction blocks delivery");
      }
      execution.setVariableLocal("consistencyClean", clean);
    } finally {
      WorkspaceContext.clear();
    }
  }
}
