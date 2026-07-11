package com.d2os.replay;

import com.d2os.persona.HashUtil;
import com.d2os.persona.KnowledgeInjectionSnapshot;
import com.d2os.persona.KnowledgeInjectionSnapshotRepository;
import com.d2os.persona.OperationExecution;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Verifies an OperationExecution snapshot carries everything needed to reproduce its output (T043,
 * FR-006): prompt version, model id + version, inputs, and injected knowledge all present. A row
 * missing any of these is a reproducibility gap even if its output happens to match.
 *
 * <p>Phase 3 (T016, FR-007, research R5): additionally reconstructs the injected-knowledge slot
 * from the {@code knowledge_injection_snapshot} rows — the pinned {@code (key,version)} + content
 * hash — NOT the item's current state, so an item deprecated/superseded after the run cannot
 * perturb replay.
 */
@Component
public class SnapshotCompletenessCheck {

  /**
   * Loads the pinned item content (soft-referenced by key+version) to re-verify the snapshot hash.
   */
  private static final String PINNED_CONTENT_SQL =
      "SELECT content FROM knowledge_item WHERE workspace_id = ? AND key = ? AND version = ?";

  private final KnowledgeInjectionSnapshotRepository injectionSnapshotRepository;
  private final ObjectMapper objectMapper;
  private final JdbcTemplate jdbcTemplate;

  public SnapshotCompletenessCheck(
      KnowledgeInjectionSnapshotRepository injectionSnapshotRepository,
      ObjectMapper objectMapper,
      JdbcTemplate jdbcTemplate) {
    this.injectionSnapshotRepository = injectionSnapshotRepository;
    this.objectMapper = objectMapper;
    this.jdbcTemplate = jdbcTemplate;
  }

  public boolean isComplete(OperationExecution op) {
    return notBlank(op.getPromptDefinitionVersion())
        && notBlank(op.getModelId())
        && notBlank(op.getModelVersion())
        && notBlank(op.getInputs())
        && op.getInjectedKnowledge() != null;
  }

  /**
   * Reconstruct and verify the injected-knowledge slot from the snapshot (T016, FR-007): if the
   * op's {@code injected_knowledge} JSON is non-empty, snapshot rows must exist and their count
   * must match; positions must be contiguous from 0; and — the actual reproducibility guarantee —
   * for every snapshot the pinned {@code (key,version)} item must still be loadable and its content
   * must re-hash to the snapshot's {@code content_hash}. This catches the two ways byte-for-byte
   * replay can silently break: the pinned item version having been physically removed (nothing to
   * reconstruct from), or its {@code content} having drifted since the run (hash mismatch). A
   * blank/absent hash also fails. An operation that injected nothing trivially reproduces (empty
   * slot, no stray rows).
   */
  public boolean knowledgeContextReproduced(OperationExecution op) {
    int declaredCount = injectedItemCount(op.getInjectedKnowledge());
    List<KnowledgeInjectionSnapshot> snapshots =
        injectionSnapshotRepository.findByOperationExecutionIdOrderByPositionAsc(op.getId());

    if (declaredCount == 0) {
      return snapshots.isEmpty();
    }
    if (snapshots.size() != declaredCount) {
      return false;
    }
    for (int i = 0; i < snapshots.size(); i++) {
      KnowledgeInjectionSnapshot snap = snapshots.get(i);
      if (snap.getContentHash() == null || snap.getContentHash().isBlank()) {
        return false;
      }
      if (snap.getPosition() != i) { // ordered query → positions must be contiguous from 0
        return false;
      }
      // Reconstruct from the PINNED version and re-verify the hash against the item's stored
      // content.
      String content = loadPinnedContent(snap);
      if (content == null || !HashUtil.sha256Hex(content).equals(snap.getContentHash())) {
        return false;
      }
    }
    return true;
  }

  /**
   * The pinned item version's content, or null if that version no longer exists (workspace-scoped).
   */
  private String loadPinnedContent(KnowledgeInjectionSnapshot snap) {
    return jdbcTemplate.query(
        PINNED_CONTENT_SQL,
        rs -> rs.next() ? rs.getString(1) : null,
        snap.getWorkspaceId(),
        snap.getKnowledgeItemKey(),
        snap.getKnowledgeItemVersion());
  }

  /**
   * Attachment-summary reproducibility check (Phase 2 US5, T049, FR-016). A summary is produced at
   * upload time and carries its snapshot inline (no {@code operation_execution}): to reproduce it
   * byte-identically the harness needs the model identity plus the SHA-256 of both the
   * sandbox-extracted input and the summary output. Any blank field is a reproducibility gap. Kept
   * as a pure function so the replay module needs no dependency on intake — the replay IT reads the
   * persisted fields via JDBC and passes them here.
   */
  public boolean attachmentSummarySnapshotComplete(
      String modelId, String modelVersion, String extractedTextHash, String summaryHash) {
    return notBlank(modelId)
        && notBlank(modelVersion)
        && notBlank(extractedTextHash)
        && notBlank(summaryHash);
  }

  /**
   * Count entries in the {@code injected_knowledge} JSON array (0 for null/blank/"[]"/malformed).
   */
  private int injectedItemCount(String injectedKnowledgeJson) {
    if (injectedKnowledgeJson == null || injectedKnowledgeJson.isBlank()) {
      return 0;
    }
    try {
      JsonNode node = objectMapper.readTree(injectedKnowledgeJson);
      return node.isArray() ? node.size() : 0;
    } catch (Exception e) {
      return 0;
    }
  }

  private boolean notBlank(String s) {
    return s != null && !s.isBlank();
  }
}
