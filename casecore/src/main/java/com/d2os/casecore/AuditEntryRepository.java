package com.d2os.casecore;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuditEntryRepository extends JpaRepository<AuditEntryRecord, UUID> {
    List<AuditEntryRecord> findBySubjectTypeAndSubjectIdOrderByTxTimeAsc(String subjectType, UUID subjectId);

    /**
     * Phase 5 (T023/T025, US3, research R4): the most recent audit entry of a given action for a
     * subject — used to read back {@code BASELINE_RESOLVED} (written once by {@code
     * BaselineResolutionDelegate}) as the durable source of truth for both {@code
     * ArtifactService#materializeForCase} (same-tx DERIVES_FROM trace-link writing) and {@code
     * CaseController}'s {@code GET /cases/{id}/baseline} (T025) — the established "audit entry as
     * durable record" convention in this codebase, rather than new schema.
     */
    Optional<AuditEntryRecord> findFirstBySubjectTypeAndSubjectIdAndActionOrderByTxTimeDesc(
            String subjectType, UUID subjectId, String action);
}
