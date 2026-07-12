package com.d2os.governance.api;

import com.d2os.governance.escalation.EscalationActivation;
import com.d2os.governance.escalation.EscalationActivationRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /gates/{gateId}/escalations} (T035, contracts/api.yaml, FR-010). */
@RestController
@RequestMapping("/api/v1/gates/{gateId}/escalations")
public class EscalationController {

  private final EscalationActivationRepository activationRepository;

  public EscalationController(EscalationActivationRepository activationRepository) {
    this.activationRepository = activationRepository;
  }

  @GetMapping
  public List<EscalationActivationView> list(@PathVariable UUID gateId) {
    return activationRepository.findByGateInstanceId(gateId).stream()
        .map(EscalationActivationView::of)
        .toList();
  }

  public record EscalationActivationView(
      UUID id,
      UUID gateInstanceId,
      String policyKey,
      int policyVersion,
      int stepIndex,
      String role,
      boolean assigneeResolved,
      String status,
      OffsetDateTime firedAt) {
    static EscalationActivationView of(EscalationActivation a) {
      return new EscalationActivationView(
          a.getId(),
          a.getGateInstanceId(),
          a.getPolicyKey(),
          a.getPolicyVersion(),
          a.getStepIndex(),
          a.getRole(),
          a.isAssigneeResolved(),
          a.getStatus(),
          a.getFiredAt());
    }
  }
}
