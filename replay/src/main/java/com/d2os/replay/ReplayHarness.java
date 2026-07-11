package com.d2os.replay;

import com.d2os.artifacts.storage.ObjectStoreClient;
import com.d2os.persona.OperationExecution;
import com.d2os.persona.OperationExecutionRepository;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Replay-audits a completed Case (T041, R5, FR-009, SC-002).
 *
 * <p><b>Recorded-output replay</b>: for each OperationExecution, the harness re-reads the stored
 * output bytes from object storage and recomputes their SHA-256, checking it byte-for-byte against
 * the {@code output_hash} recorded at generation time. Verification is against what was
 * <em>stored</em>, never a fresh model call — so a match is exact and immune to provider
 * non-determinism (per the clarified replay semantics). Also confirms each snapshot is complete
 * enough to reproduce (T043).
 *
 * <p><b>Injected-knowledge reproduction</b> (T016, FR-007, research R5): for each operation the
 * harness loads its {@code knowledge_injection_snapshot} rows ordered by {@code position} and
 * verifies the injected context reconstructs from the SNAPSHOT (the pinned {@code (key,version)} +
 * content hash), never the item's current state — so an item deprecated or superseded after the run
 * does not perturb replay. A per-operation {@code knowledgeContextReproduced} flag surfaces this
 * and feeds the report's matched/complete accounting.
 *
 * <p><b>Parallel-case + consistency coverage</b> (Phase 2 US5, T049, SC-008, FR-016): the semantic
 * Consistency-Check reviewer runs as an ordinary persona step, so its OperationExecution is
 * replayed by exactly this path — a parallel case (four specialists + consistency reviewer) replays
 * byte-identically with no special handling. Attachment summaries live outside {@code
 * operation_execution} (they are produced pre-Case at upload time); their inline reproducibility
 * snapshot is checked separately via {@link
 * SnapshotCompletenessCheck#attachmentSummarySnapshotComplete}.
 */
@Component
public class ReplayHarness {

  private final OperationExecutionRepository operationExecutionRepository;
  private final ObjectStoreClient objectStoreClient;
  private final SnapshotCompletenessCheck completenessCheck;

  public ReplayHarness(
      OperationExecutionRepository operationExecutionRepository,
      ObjectStoreClient objectStoreClient,
      SnapshotCompletenessCheck completenessCheck) {
    this.operationExecutionRepository = operationExecutionRepository;
    this.objectStoreClient = objectStoreClient;
    this.completenessCheck = completenessCheck;
  }

  public ReplayReport replay(UUID caseId) {
    List<OperationExecution> operations = operationExecutionRepository.findByCaseInstanceId(caseId);
    List<ReplayReport.OperationResult> results = new ArrayList<>();
    int matched = 0;

    for (OperationExecution op : operations) {
      boolean byteIdentical = verifyOutput(op);
      boolean complete = completenessCheck.isComplete(op);
      boolean knowledgeReproduced = completenessCheck.knowledgeContextReproduced(op);
      // An operation counts as matched only if its output is byte-identical AND its injected
      // knowledge context reconstructs from the snapshot (T016).
      if (byteIdentical && knowledgeReproduced) matched++;
      results.add(
          new ReplayReport.OperationResult(
              op.getId(), byteIdentical, complete, knowledgeReproduced));
    }

    return new ReplayReport(
        caseId, operations.size(), matched, operations.size() - matched, results);
  }

  private boolean verifyOutput(OperationExecution op) {
    if (op.getOutputRef() == null || op.getOutputHash() == null) {
      return false;
    }
    byte[] stored = objectStoreClient.get(op.getOutputRef());
    return sha256Hex(stored).equals(op.getOutputHash());
  }

  private String sha256Hex(byte[] content) {
    try {
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(content);
      StringBuilder sb = new StringBuilder(hash.length * 2);
      for (byte b : hash) sb.append(String.format("%02x", b));
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
