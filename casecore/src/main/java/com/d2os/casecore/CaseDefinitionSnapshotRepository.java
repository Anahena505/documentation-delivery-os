package com.d2os.casecore;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CaseDefinitionSnapshotRepository extends JpaRepository<CaseDefinitionSnapshot, UUID> {
    Optional<CaseDefinitionSnapshot> findByCaseInstanceId(UUID caseInstanceId);
}
