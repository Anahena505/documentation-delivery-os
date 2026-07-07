package com.d2os.casecore.progress;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProgressEventRepository extends JpaRepository<ProgressEvent, Long> {

    /** Long-poll / paging cursor: events for a case after a given id, oldest first. */
    List<ProgressEvent> findByCaseIdAndIdGreaterThanOrderByIdAsc(UUID caseId, Long afterId, Limit limit);

    /** Latest event for a case (used by cadence checks / reconciliation). */
    ProgressEvent findFirstByCaseIdOrderByIdDesc(UUID caseId);
}
