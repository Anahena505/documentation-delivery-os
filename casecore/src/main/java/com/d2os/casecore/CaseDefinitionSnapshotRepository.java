package com.d2os.casecore;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaseDefinitionSnapshotRepository
    extends JpaRepository<CaseDefinitionSnapshot, UUID> {
  Optional<CaseDefinitionSnapshot> findByCaseInstanceId(UUID caseInstanceId);
}
