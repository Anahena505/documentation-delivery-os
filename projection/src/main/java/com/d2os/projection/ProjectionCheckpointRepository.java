package com.d2os.projection;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** READ-side repository (T007) — app datasource. Writes go through {@link GraphWriteRepository}. */
public interface ProjectionCheckpointRepository
    extends JpaRepository<ProjectionCheckpoint, ProjectionCheckpointId> {

  List<ProjectionCheckpoint> findByWorkspaceId(UUID workspaceId);
}
