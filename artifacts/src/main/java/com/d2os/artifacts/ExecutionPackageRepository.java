package com.d2os.artifacts;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ExecutionPackageRepository extends JpaRepository<ExecutionPackage, UUID> {
    Optional<ExecutionPackage> findByCaseInstanceId(UUID caseInstanceId);
}
