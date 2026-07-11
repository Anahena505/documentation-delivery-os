package com.d2os.casecore.audit;

import com.d2os.casecore.AuditEntryRecord;

import java.util.List;

/**
 * The canonical serialization {@link AuditChainSealer} hashes to seal a segment and {@link
 * AuditChainVerifier} recomputes to check it — kept as ONE shared function so sealing and verifying
 * can never silently drift out of sync with each other (Phase 7, T038/T039, research R5).
 */
final class AuditChainCanonicalizer {

    private AuditChainCanonicalizer() {}

    /** Ordered concatenation of every immutable field on each entry, one line per entry. */
    static String canonicalize(List<AuditEntryRecord> entries) {
        StringBuilder sb = new StringBuilder();
        for (AuditEntryRecord e : entries) {
            sb.append(e.getId()).append('|')
              .append(e.getWorkspaceId()).append('|')
              .append(e.getSubjectType()).append('|')
              .append(e.getSubjectId()).append('|')
              .append(e.getAction()).append('|')
              .append(e.getActor()).append('|')
              .append(e.getTxTime()).append('|')
              .append(e.getDetails()).append('\n');
        }
        return sb.toString();
    }
}
