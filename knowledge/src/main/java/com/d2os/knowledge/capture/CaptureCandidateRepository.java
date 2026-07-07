package com.d2os.knowledge.capture;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read/write finders over {@link CaptureCandidate}. RLS confines every query to the caller's workspace,
 * so no method carries a workspace predicate. Revision chains are walked via {@code revisionOf}: the
 * "latest revision by root" finders resolve the row the D4 gate reviews.
 */
public interface CaptureCandidateRepository extends JpaRepository<CaptureCandidate, UUID> {

    List<CaptureCandidate> findByStatusOrderByCreatedAtDesc(String status);

    List<CaptureCandidate> findByCaseInstanceIdOrderByCreatedAtDesc(UUID caseInstanceId);

    List<CaptureCandidate> findByCaseInstanceId(UUID caseInstanceId);

    /**
     * All revisions in a chain rooted at (or being) {@code rootId}: the root row itself plus every row
     * whose {@code revisionOf} points back into the chain. v1 chains are linear (one redaction step), so
     * this direct-children + self query resolves the full chain; ordered by revision so the last element
     * is the latest revision the D4 gate reviews.
     */
    List<CaptureCandidate> findByIdOrRevisionOfOrderByRevisionAsc(UUID id, UUID revisionOf);

    Optional<CaptureCandidate> findFirstByRevisionOfOrderByRevisionDesc(UUID revisionOf);
}
