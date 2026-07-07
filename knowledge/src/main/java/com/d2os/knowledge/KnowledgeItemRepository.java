package com.d2os.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read finders over {@link KnowledgeItem}. The ANN retrieval path does NOT go through here — it uses
 * {@code JdbcTemplate} in {@link KnowledgeRetrievalService} because JPA cannot express the pgvector
 * {@code <=>} distance operator. This repository serves version/lookup reads (US1 tests, US2/US3).
 */
public interface KnowledgeItemRepository extends JpaRepository<KnowledgeItem, UUID> {

    List<KnowledgeItem> findByWorkspaceIdAndKeyOrderByVersionDesc(UUID workspaceId, String key);

    Optional<KnowledgeItem> findFirstByWorkspaceIdAndKeyOrderByVersionDesc(UUID workspaceId, String key);

    List<KnowledgeItem> findByWorkspaceIdAndStatus(UUID workspaceId, String status);
}
