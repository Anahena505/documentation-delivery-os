package com.d2os.artifacts;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ArtifactRepository extends JpaRepository<Artifact, UUID> {
    List<Artifact> findByCaseInstanceId(UUID caseInstanceId);
}
