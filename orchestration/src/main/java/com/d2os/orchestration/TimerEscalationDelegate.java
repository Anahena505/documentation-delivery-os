package com.d2os.orchestration;

import com.d2os.governance.escalation.TimerFiredHandler;
import com.d2os.tenancy.WorkspaceContext;
import com.d2os.tenancy.security.WorkspaceRlsBinder;
import java.util.UUID;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Engine-side bridge for a gate userTask's non-interrupting boundary SLA timer (Phase 6 US4,
 * T032/T033, research R4). Bound as a serviceTask right after the boundary timer in {@code
 * review-gate.bpmn20.xml}/{@code approval-gate.bpmn20.xml} — mirrors {@link GateTaskBridge}'s
 * engine-coupling posture (this is the only other class in {@code orchestration} that reaches into
 * {@code governance}'s escalation package). {@code cancelActivity="false"} on the boundary event
 * means this execution branch runs concurrently with the still-parked {@code gate-decision}
 * userTask — {@link TimerFiredHandler} never touches that task or the {@code GateInstance} status
 * (Principle V).
 */
@Component("timerEscalationDelegate")
public class TimerEscalationDelegate implements JavaDelegate {

  private final TimerFiredHandler timerFiredHandler;
  private final WorkspaceRlsBinder workspaceRlsBinder;

  public TimerEscalationDelegate(
      TimerFiredHandler timerFiredHandler, WorkspaceRlsBinder workspaceRlsBinder) {
    this.timerFiredHandler = timerFiredHandler;
    this.workspaceRlsBinder = workspaceRlsBinder;
  }

  @Override
  @Transactional
  public void execute(DelegateExecution execution) {
    UUID workspaceId = UUID.fromString((String) execution.getVariable("workspaceId"));
    UUID caseInstanceId = UUID.fromString((String) execution.getVariable("caseInstanceId"));

    WorkspaceContext.set(workspaceId);
    try {
      workspaceRlsBinder.bindCurrentTransaction(workspaceId);
      // Exactly one boundary timer is wired per gate userTask today (step 0 of the role chain) —
      // see TimerFiredHandler's javadoc for the multi-step follow-up this leaves open.
      timerFiredHandler.onTimerFired(workspaceId, caseInstanceId, 0);
    } finally {
      WorkspaceContext.clear();
    }
  }
}
