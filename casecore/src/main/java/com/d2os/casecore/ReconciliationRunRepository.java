package com.d2os.casecore;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRun, UUID> {
  List<ReconciliationRun> findByCaseId(UUID caseId);
}
