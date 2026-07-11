package com.d2os.casecore.audit;

import com.d2os.casecore.AuditEntryRecord;
import com.d2os.casecore.AuditEntryRepository;
import com.d2os.tenancy.WorkspaceContext;
import com.d2os.tenancy.security.WorkspaceRlsBinder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Periodic tamper-evidence seal over the append-only {@code audit_entry} stream (Phase 7 US5, T038,
 * research R5, T6-b, FR-013). Fixed hourly schedule (no cadence config key, per T003's own note) —
 * seals every workspace's unsealed tail into one new {@link AuditChainSegment}: {@code segmentHash} =
 * SHA-256 over the ordered canonical serialization of the range, chained to the prior segment's own
 * hash ({@code genesis = 64 * '0'}). A workspace with zero new entries since its last seal is
 * skipped — sealing an empty range would just re-hash nothing and burn a segment_seq for no reason.
 */
@Component
public class AuditChainSealer {

    private final JdbcTemplate jdbcTemplate;
    private final AuditEntryRepository auditEntryRepository;
    private final AuditChainSegmentRepository segmentRepository;
    private final WorkspaceRlsBinder workspaceRlsBinder;

    public AuditChainSealer(JdbcTemplate jdbcTemplate, AuditEntryRepository auditEntryRepository,
                            AuditChainSegmentRepository segmentRepository, WorkspaceRlsBinder workspaceRlsBinder) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditEntryRepository = auditEntryRepository;
        this.segmentRepository = segmentRepository;
        this.workspaceRlsBinder = workspaceRlsBinder;
    }

    @Scheduled(cron = "${d2os.governance.audit.seal-cron:0 0 * * * *}")
    public void sealAllWorkspaces() {
        // Reuses the SECURITY DEFINER enumeration function the projector's cross-tenant sweep already
        // established (V29) — RLS gives no ordinary-SELECT tenant-enumeration escape hatch, and this
        // job, like the projector, must visit every workspace before any single one is bound.
        for (UUID workspaceId : jdbcTemplate.queryForList("SELECT id FROM list_active_workspace_ids()", UUID.class)) {
            sealWorkspace(workspaceId);
        }
    }

    /** Seal one workspace's unsealed tail. Returns the new segment, or {@code null} if nothing to seal. */
    @Transactional
    public AuditChainSegment sealWorkspace(UUID workspaceId) {
        WorkspaceContext.set(workspaceId);
        try {
            workspaceRlsBinder.bindCurrentTransaction(workspaceId);
            AuditChainSegment prior = segmentRepository.findFirstByWorkspaceIdOrderBySegmentSeqDesc(workspaceId).orElse(null);
            List<AuditEntryRecord> unsealed = prior == null
                    ? auditEntryRepository.findByWorkspaceIdOrderByTxTimeAscIdAsc(workspaceId)
                    : auditEntryRepository.findByWorkspaceIdAndTxTimeAfterOrderByTxTimeAscIdAsc(
                            workspaceId, entryTxTime(prior.getToEntryId()));
            if (unsealed.isEmpty()) {
                return null;
            }

            String segmentHash = HashUtil.sha256Hex(AuditChainCanonicalizer.canonicalize(unsealed));
            String prevHash = prior == null ? AuditChainSegment.GENESIS_HASH : prior.getSegmentHash();
            long nextSeq = prior == null ? 1 : prior.getSegmentSeq() + 1;

            AuditChainSegment segment = new AuditChainSegment(UUID.randomUUID(), workspaceId, nextSeq,
                    unsealed.get(0).getId(), unsealed.get(unsealed.size() - 1).getId(), unsealed.size(),
                    segmentHash, prevHash);
            return segmentRepository.save(segment);
        } finally {
            WorkspaceContext.clear();
        }
    }

    private java.time.OffsetDateTime entryTxTime(UUID entryId) {
        return auditEntryRepository.findById(entryId)
                .map(AuditEntryRecord::getTxTime)
                .orElseThrow(() -> new IllegalStateException("sealed audit_entry " + entryId + " no longer exists"));
    }
}
