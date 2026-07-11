package com.d2os.persona;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Reads/writes {@link KnowledgeInjectionSnapshot} rows. Replay (T016) loads the injected slot
 * ordered by {@code position} to reconstruct the exact injected context of an operation (FR-007).
 */
public interface KnowledgeInjectionSnapshotRepository
    extends JpaRepository<KnowledgeInjectionSnapshot, UUID> {

  List<KnowledgeInjectionSnapshot> findByOperationExecutionIdOrderByPositionAsc(
      UUID operationExecutionId);
}
