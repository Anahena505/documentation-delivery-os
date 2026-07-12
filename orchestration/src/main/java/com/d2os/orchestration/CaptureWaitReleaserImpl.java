package com.d2os.orchestration;

import com.d2os.knowledge.capture.CaptureWaitReleaser;
import java.util.List;
import java.util.UUID;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.springframework.stereotype.Component;

/**
 * Releases the {@code knowledge-capture} process's {@code d4-review} receiveTask once the D4
 * endpoint has committed the decision (T019/T026). Sets the {@code d4Approved} process variable and
 * triggers the parked execution, so the exclusive gateway routes to the published/rejected end —
 * mirroring the Phase 2 {@code EscalationBridge} receiveTask + {@code runtimeService.trigger}
 * pattern (per-execution trigger, not a broadcast signal, so only this case's capture instance
 * resumes).
 *
 * <p>Best-effort/idempotent: if no capture instance is parked at the wait (a services-only flow
 * with no running process, or an already-completed instance), it does nothing — the authoritative
 * publish/reject has already been committed by {@code PromotionGateService.decideD4}, so the
 * process is the audit spine.
 *
 * <p>Duplicate-tolerant: business-key uniqueness for {@code knowledge-capture} is enforced only by
 * the single-node check-then-start in {@code CaseDeliveredKnowledgeTrigger}; across nodes two
 * instances could momentarily share a case business key. This releaser therefore queries with
 * {@code list()} and releases every parked D4 wait rather than {@code singleResult()}, which throws
 * on more than one match. Releasing all matches is safe — the publish/reject is already committed,
 * so a redundant trigger is a no-op end.
 */
@Component
public class CaptureWaitReleaserImpl implements CaptureWaitReleaser {

  private static final String CAPTURE_PROCESS_KEY = "knowledge-capture";
  private static final String D4_WAIT_ACTIVITY = "d4-review";

  private final RuntimeService runtimeService;

  public CaptureWaitReleaserImpl(RuntimeService runtimeService) {
    this.runtimeService = runtimeService;
  }

  @Override
  public void releaseD4Wait(UUID caseInstanceId, boolean approved) {
    // list() not singleResult(): a cross-node double-start could leave two capture instances
    // sharing
    // this case business key, and singleResult() would throw. Release the D4 wait on every match.
    List<ProcessInstance> instances =
        runtimeService
            .createProcessInstanceQuery()
            .processInstanceBusinessKey(caseInstanceId.toString())
            .processDefinitionKey(CAPTURE_PROCESS_KEY)
            .list();
    for (ProcessInstance pi : instances) {
      Execution waiting =
          runtimeService
              .createExecutionQuery()
              .processInstanceId(pi.getId())
              .activityId(D4_WAIT_ACTIVITY)
              .list()
              .stream()
              .findFirst()
              .orElse(null);
      if (waiting == null) {
        continue; // this instance is not parked at the D4 wait
      }
      runtimeService.setVariable(pi.getId(), "d4Approved", approved);
      runtimeService.trigger(waiting.getId());
    }
  }
}
