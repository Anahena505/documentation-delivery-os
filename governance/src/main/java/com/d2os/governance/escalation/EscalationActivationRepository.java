package com.d2os.governance.escalation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EscalationActivationRepository extends JpaRepository<EscalationActivation, UUID> {
    List<EscalationActivation> findByGateInstanceId(UUID gateInstanceId);
}
