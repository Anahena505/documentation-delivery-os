package com.d2os.casecore;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Reads/writes {@link DecisionRecord} rows (RLS-scoped to the caller's workspace). */
public interface DecisionRepository extends JpaRepository<DecisionRecord, UUID> {

  List<DecisionRecord> findByCaseInstanceId(UUID caseInstanceId);

  /**
   * Phase 4 (T011, US1): case-type confirm/override decisions are recorded before a Case exists
   * (see {@link DecisionRecord}'s nullable {@code caseInstanceId} note), so they are looked up by
   * {@code inputsRef} (the submission id) instead.
   */
  Optional<DecisionRecord> findFirstByInputsRefAndDecisionTypeOrderByCreatedAtDesc(
      String inputsRef, String decisionType);
}
