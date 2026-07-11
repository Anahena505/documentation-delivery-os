package com.d2os.orchestration;

import com.d2os.governance.EngineGateReleasePort;
import java.util.Map;
import org.flowable.engine.TaskService;
import org.springframework.stereotype.Component;

/**
 * Orchestration-side implementation of {@code EngineGateReleasePort} (T014): completes the gate
 * userTask {@code GateService.decide()} parked at, once the decision has committed — mirrors {@link
 * CaptureWaitReleaserImpl}'s best-effort/idempotent posture exactly.
 *
 * <p>Unlike {@code CaptureWaitReleaserImpl} (a receiveTask released via {@code
 * runtimeService.trigger}), the gate subprocess (T012/T013) uses a real Flowable userTask, so
 * release here means {@code TaskService.complete(...)} with the decided verb as the {@code
 * gateDecision} process variable — the callActivity subprocess's exclusive gateway (T015's
 * embedding workflows) reads that variable back via the callActivity's {@code flowable:out} mapping
 * to route on the verb.
 */
@Component
public class EngineGateReleasePortImpl implements EngineGateReleasePort {

  private final TaskService taskService;

  public EngineGateReleasePortImpl(TaskService taskService) {
    this.taskService = taskService;
  }

  @Override
  public void completeGateTask(String engineTaskId, String verbName) {
    if (engineTaskId == null) {
      return; // services-only flow: no parked engine task to release
    }
    if (taskService.createTaskQuery().taskId(engineTaskId).count() == 0) {
      return; // already completed — idempotent no-op, the Decision is already authoritative
    }
    taskService.complete(engineTaskId, Map.of("gateDecision", verbName));
  }
}
