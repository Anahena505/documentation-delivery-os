package com.d2os.artifacts;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ArtifactRevisionRepository extends JpaRepository<ArtifactRevision, UUID> {
    List<ArtifactRevision> findByArtifactId(UUID artifactId);

    List<ArtifactRevision> findByArtifactIdOrderByRevisionNoAsc(UUID artifactId);

    /**
     * Phase 5 (T019/T020, research R2): the latest revision of an Artifact — used by {@code
     * ArtifactService.createRevision} to decide whether a new persona output is genuinely a new
     * revision (append) or byte-identical to what's already there (idempotent no-op).
     */
    Optional<ArtifactRevision> findFirstByArtifactIdOrderByRevisionNoDesc(UUID artifactId);
}
