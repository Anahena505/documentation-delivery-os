package com.d2os.artifacts;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExecutionPackageRepository extends JpaRepository<ExecutionPackage, UUID> {
  Optional<ExecutionPackage> findByCaseInstanceId(UUID caseInstanceId);
}
