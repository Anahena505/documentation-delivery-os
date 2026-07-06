package com.d2os.replay;

import java.util.List;
import java.util.UUID;

/** Result of replay-auditing a completed Case (T042, FR-009, SC-002). */
public record ReplayReport(
        UUID caseId,
        int totalOperations,
        int matched,
        int mismatched,
        List<OperationResult> results
) {
    public record OperationResult(UUID operationExecutionId, boolean byteIdentical, boolean snapshotComplete) {}
}
