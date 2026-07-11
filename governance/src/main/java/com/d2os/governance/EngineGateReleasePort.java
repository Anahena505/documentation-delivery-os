package com.d2os.governance;

/**
 * SPI {@code GateService.decide()} (T014) uses to release the engine's parked gate userTask once a
 * decision has committed — mirrors {@code com.d2os.knowledge.capture.CaptureWaitReleaser} /
 * {@code CaptureWaitReleaserImpl} exactly: the interface lives here in {@code governance} so this
 * module stays Flowable-free (plan.md Structure Decision — "governance stays engine-agnostic"; engine
 * coupling is confined to {@code orchestration}'s {@code GateTaskBridge}, later enforced by an
 * ArchUnit rule, Phase 9 T048). {@code orchestration} supplies the implementation
 * ({@code EngineGateReleasePortImpl}), wrapping Flowable's {@code TaskService.complete(...)}, wired
 * purely via Spring DI (governance has no compile-time dependency on orchestration or Flowable).
 */
public interface EngineGateReleasePort {

    /**
     * Complete the userTask {@code engineTaskId} with the process variable {@code gateDecision =
     * verbName}, so the gate callActivity subprocess (review-gate/approval-gate, T012/T013) routes on
     * the verb and control returns to the calling workflow. Best-effort/idempotent: a null task id
     * (services-only flow, no parked engine task) or an already-completed task is a no-op — the
     * authoritative Decision has already committed by the time this is called.
     */
    void completeGateTask(String engineTaskId, String verbName);
}
