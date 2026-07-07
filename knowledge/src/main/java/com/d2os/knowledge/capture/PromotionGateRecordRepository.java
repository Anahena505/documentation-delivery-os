package com.d2os.knowledge.capture;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Append-only reads over {@link PromotionGateRecord}. Gate-order and at-most-one-PASS invariants are
 * checked in {@link PromotionGateService} against these reads (belt) and enforced by the DB partial
 * unique index (braces).
 */
public interface PromotionGateRecordRepository extends JpaRepository<PromotionGateRecord, UUID> {

    List<PromotionGateRecord> findByCandidateIdOrderByCreatedAtAsc(UUID candidateId);

    boolean existsByCandidateIdAndGateAndOutcome(UUID candidateId, String gate, String outcome);
}
