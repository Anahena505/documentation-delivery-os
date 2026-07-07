package com.d2os.casecore;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Reads/writes {@link DecisionRecord} rows (RLS-scoped to the caller's workspace). */
public interface DecisionRepository extends JpaRepository<DecisionRecord, UUID> {

    List<DecisionRecord> findByCaseInstanceId(UUID caseInstanceId);
}
