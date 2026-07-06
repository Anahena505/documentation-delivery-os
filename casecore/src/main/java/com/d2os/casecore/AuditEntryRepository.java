package com.d2os.casecore;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AuditEntryRepository extends JpaRepository<AuditEntryRecord, UUID> {
    List<AuditEntryRecord> findBySubjectTypeAndSubjectIdOrderByTxTimeAsc(String subjectType, UUID subjectId);
}
