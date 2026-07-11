package com.d2os.projection;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * READ-side repository (T007) — app datasource. Callers (T016+, TraceabilityQueryService et al.)
 * read {@code liveGeneration} from here to scope every graph query to the current live generation.
 */
public interface ProjectionStateRepository extends JpaRepository<ProjectionState, UUID> {
}
