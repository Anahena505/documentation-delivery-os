package com.d2os.persona.consistency;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ConsistencyFindingRepository extends JpaRepository<ConsistencyFinding, UUID> {

    List<ConsistencyFinding> findByCaseId(UUID caseId);

    /** The blocking invariant (FR-007): any OPEN DETERMINISTIC finding stops the package advancing. */
    long countByCaseIdAndTierAndStatus(UUID caseId, String tier, String status);
}
