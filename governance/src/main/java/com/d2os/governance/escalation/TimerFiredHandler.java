package com.d2os.governance.escalation;

import com.d2os.governance.GateEventPublisher;
import com.d2os.governance.GateInstance;
import com.d2os.governance.GateInstanceRepository;
import com.d2os.governance.GateStatus;
import com.d2os.governance.metrics.GovernanceMetrics;
import com.d2os.governance.notification.NotificationService;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records an advisory SLA escalation firing (Phase 6 US4, T033, research R4, FR-010/011/012,
 * Principle V). {@code cancelActivity="false"} on the BPMN boundary timer (T032) is the structural
 * guarantee this handler's own behavior must honor: it NEVER mutates {@link GateInstance#status} or
 * touches the parked engine task — firing is purely a visible, durable record plus a notification.
 * Recorded even when the resolved role has no real assignee (there is no user/role-assignment model
 * anywhere in this codebase yet — {@code GateController}'s own javadoc notes the same gap — so
 * {@code assigneeResolved} is honestly {@code false} today; the column exists so a future role
 * model can flip it without a schema change).
 */
@Service
public class TimerFiredHandler {

  private final GateInstanceRepository gateInstanceRepository;
  private final EscalationPolicyResolver escalationPolicyResolver;
  private final EscalationActivationRepository activationRepository;
  private final NotificationService notificationService;
  private final GateEventPublisher gateEventPublisher;
  private final GovernanceMetrics governanceMetrics;

  public TimerFiredHandler(
      GateInstanceRepository gateInstanceRepository,
      EscalationPolicyResolver escalationPolicyResolver,
      EscalationActivationRepository activationRepository,
      NotificationService notificationService,
      GateEventPublisher gateEventPublisher,
      GovernanceMetrics governanceMetrics) {
    this.gateInstanceRepository = gateInstanceRepository;
    this.escalationPolicyResolver = escalationPolicyResolver;
    this.activationRepository = activationRepository;
    this.notificationService = notificationService;
    this.gateEventPublisher = gateEventPublisher;
    this.governanceMetrics = governanceMetrics;
  }

  /**
   * Fired by {@code TimerEscalationDelegate} (orchestration) when the gate userTask's
   * non-interrupting boundary timer elapses. {@code stepIndex} identifies which role-chain step
   * this timer represents (currently always {@code 0} — the BPMN wires exactly one boundary timer
   * per gate userTask; a multi-step escalation chain would need one boundary timer per step, a
   * later hardening pass). A no-op (returns {@code null}) if the case has no currently-OPEN gate —
   * the decision may have already been made in the window between the timer firing and this handler
   * running.
   */
  @Transactional
  public UUID onTimerFired(UUID workspaceId, UUID caseInstanceId, int stepIndex) {
    Optional<GateInstance> openGate =
        gateInstanceRepository.findByCaseInstanceId(caseInstanceId).stream()
            .filter(g -> g.status() == GateStatus.OPEN)
            .findFirst();
    if (openGate.isEmpty()) {
      return null; // decided (or otherwise moved on) before the timer handler ran — nothing to
      // escalate
    }
    GateInstance gate = openGate.get();

    EscalationPolicyResolver.Policy policy = escalationPolicyResolver.resolveFor(gate).orElse(null);
    String role =
        policy == null
            ? null
            : policy.step(stepIndex).map(EscalationPolicyResolver.Step::role).orElse(null);
    boolean assigneeResolved =
        false; // no role/user-assignment model exists yet — always honestly false

    String resolvedRole = role == null ? "reviewer" : role;
    String policyKey = policy == null ? "escalation-policy.default" : policy.key();
    int policyVersion = policy == null ? 0 : parseVersion(policy.version());

    EscalationActivation activation =
        new EscalationActivation(
            UUID.randomUUID(),
            workspaceId,
            gate.getId(),
            policyKey,
            policyVersion,
            stepIndex,
            resolvedRole,
            assigneeResolved);
    activationRepository.save(activation);

    notificationService.notifyRole(
        workspaceId,
        resolvedRole,
        "governance",
        "SLA_ESCALATION",
        java.util.Map.of("gateInstanceId", gate.getId().toString(), "stepIndex", stepIndex),
        "Gate "
            + gate.getId()
            + " has been open past its advisory SLA (step "
            + stepIndex
            + ", role "
            + resolvedRole
            + ")");

    gateEventPublisher.publishEscalationFired(gate, policyKey, policyVersion, stepIndex);

    // T020: an escalation firing on an OPEN gate is exactly an advisory-SLA breach — count it.
    governanceMetrics.recordSlaBreach();

    return activation.getId();
  }

  /**
   * {@code definition_asset.version} is a semver-ish string (e.g. "5.0.0"); the major segment
   * stands in for an int policy version.
   */
  private int parseVersion(String version) {
    try {
      return Integer.parseInt(version.split("\\.")[0]);
    } catch (Exception e) {
      return 0;
    }
  }
}
