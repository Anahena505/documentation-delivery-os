package com.d2os.knowledge;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Read/flip finders over {@link KnowledgeAffectedExecution}. The flag rows are <em>inserted</em> by
 * {@link DeprecationService} through a JdbcTemplate insert-select (it joins the persona module's
 * {@code knowledge_injection_snapshot}, which JPA cannot express cleanly); this repository serves
 * the review-queue listing (US3 endpoints) and the acknowledgement flip.
 */
public interface KnowledgeAffectedExecutionRepository
    extends JpaRepository<KnowledgeAffectedExecution, UUID> {

  List<KnowledgeAffectedExecution> findByReviewStatusOrderByFlaggedAtDesc(String reviewStatus);

  List<KnowledgeAffectedExecution> findAllByOrderByFlaggedAtDesc();

  long countByKnowledgeItemKeyAndKnowledgeItemVersion(
      String knowledgeItemKey, int knowledgeItemVersion);
}
