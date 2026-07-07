package com.d2os.knowledge.capture;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Append-only reads over {@link PrefilterFinding} (RLS-scoped to the caller's workspace). */
public interface PrefilterFindingRepository extends JpaRepository<PrefilterFinding, UUID> {

    List<PrefilterFinding> findByCandidateIdOrderBySpanStartAsc(UUID candidateId);
}
