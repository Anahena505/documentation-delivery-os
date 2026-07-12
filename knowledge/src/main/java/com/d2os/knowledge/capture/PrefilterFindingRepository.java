package com.d2os.knowledge.capture;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Append-only reads over {@link PrefilterFinding} (RLS-scoped to the caller's workspace). */
public interface PrefilterFindingRepository extends JpaRepository<PrefilterFinding, UUID> {

  List<PrefilterFinding> findByCandidateIdOrderBySpanStartAsc(UUID candidateId);
}
