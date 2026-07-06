package com.d2os.replay;

import com.d2os.artifacts.storage.ObjectStoreClient;
import com.d2os.persona.OperationExecution;
import com.d2os.persona.OperationExecutionRepository;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Replay-audits a completed Case (T041, R5, FR-009, SC-002).
 *
 * <p><b>Recorded-output replay</b>: for each OperationExecution, the harness re-reads the stored
 * output bytes from object storage and recomputes their SHA-256, checking it byte-for-byte against
 * the {@code output_hash} recorded at generation time. Verification is against what was <em>stored</em>,
 * never a fresh model call — so a match is exact and immune to provider non-determinism (per the
 * clarified replay semantics). Also confirms each snapshot is complete enough to reproduce (T043).
 */
@Component
public class ReplayHarness {

    private final OperationExecutionRepository operationExecutionRepository;
    private final ObjectStoreClient objectStoreClient;
    private final SnapshotCompletenessCheck completenessCheck;

    public ReplayHarness(OperationExecutionRepository operationExecutionRepository,
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
            if (byteIdentical) matched++;
            results.add(new ReplayReport.OperationResult(op.getId(), byteIdentical, complete));
        }

        return new ReplayReport(caseId, operations.size(), matched, operations.size() - matched, results);
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
