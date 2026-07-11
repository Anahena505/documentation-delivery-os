package com.d2os.projection;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** READ-side repository (T007) — app datasource. Backs the admin API's `/graph/admin/gaps` (T012, later). */
public interface ProjectionGapRepository extends JpaRepository<ProjectionGap, UUID> {

    List<ProjectionGap> findByWorkspaceIdAndStatus(UUID workspaceId, String status);
}
