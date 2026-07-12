package com.d2os.casecore;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowInstanceRepository extends JpaRepository<WorkflowInstance, UUID> {
  Optional<WorkflowInstance> findByCaseInstanceId(UUID caseInstanceId);
}
