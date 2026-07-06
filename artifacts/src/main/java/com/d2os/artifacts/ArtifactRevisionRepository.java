package com.d2os.artifacts;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ArtifactRevisionRepository extends JpaRepository<ArtifactRevision, UUID> {
    List<ArtifactRevision> findByArtifactId(UUID artifactId);
}
