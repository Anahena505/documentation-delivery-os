package com.d2os.governance.escalation;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EscalationActivationRepository extends JpaRepository<EscalationActivation, UUID> {
  List<EscalationActivation> findByGateInstanceId(UUID gateInstanceId);
}
