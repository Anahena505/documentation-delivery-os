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

    /**
     * Ordered concatenation of every immutable field on each entry, one line per entry.
     *
     * <p>008 US5 (T050): the authenticated actor fields ({@code actor_user_id}, {@code actor_role})
     * are folded into the seal so a decision's "who + role" cannot be altered after the fact without
     * breaking the hash. They are appended <b>null-safely and only when at least one is present</b>:
     * for every pre-migration row and every default-mode (non-OIDC) decision — where both are null —
     * NOTHING is appended, so the canonical bytes are IDENTICAL to the pre-008 form and existing
     * seals/hashes remain valid. A non-null actor (OIDC mode) appends {@code |userId|role}, which both
     * changes the bytes (tamper-evidence for the actor) and keeps legacy rows unaffected.
     */
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
              .append(e.getDetails());
            String actorUserId = e.getActorUserId();
            String actorRole = e.getActorRole();
            if (actorUserId != null || actorRole != null) {
                sb.append('|').append(nullToEmpty(actorUserId))
                  .append('|').append(nullToEmpty(actorRole));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
