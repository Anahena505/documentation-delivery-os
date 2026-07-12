package com.d2os.artifacts;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ArtifactRepository extends JpaRepository<Artifact, UUID> {
  List<Artifact> findByCaseInstanceId(UUID caseInstanceId);

  /**
   * Phase 5 (T019/T020, research R2): find the Artifact a persona key already materialized for this
   * case, if any — {@code ArtifactService.createRevision} uses this to append a new revision to the
   * SAME Artifact (comment-and-regenerate, gate re-materialization) instead of minting a fresh one.
   */
  Optional<Artifact> findFirstByCaseInstanceIdAndArtifactTypeOrderByCreatedAtDesc(
      UUID caseInstanceId, String artifactType);
}
