package com.d2os.casecore.audit;

import com.d2os.casecore.AuditEntryRecord;
import com.d2os.casecore.AuditEntryRepository;
import com.d2os.tenancy.WorkspaceContext;
import com.d2os.tenancy.security.WorkspaceRlsBinder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Recomputes and checks the audit hash chain, on demand and on schedule (Phase 7 US5, T039, research
 * R5, FR-013). A mismatch — an altered or deleted entry inside an already-sealed range recomputing to
 * a different hash than what was sealed, or a broken {@code prevSegmentHash} link — is a tamper alert:
 * a {@code TAMPER_ALERT} in-app notification plus an outbox event, both via the SAME {@code
 * audit_entry} + {@code event_outbox} mechanism every other event in this codebase uses (raw JDBC
 * write to {@code in_app_notification}/{@code event_outbox} — {@code casecore} cannot depend on {@code
 * governance}, which owns those tables' JPA entities; same cross-module-raw-write convention {@code
 * ArtifactService}/{@code ConsistencyService} already establish for {@code trace_link}).
 */
@Component
public class AuditChainVerifier {

    /** One segment's verification outcome. */
    public record SegmentResult(long segmentSeq, boolean intact, String expectedHash, String recomputedHash) {}

    /** The whole-chain result {@code POST /audit/chain/verify} (T042) returns. */
    public record ChainResult(boolean intact, int segmentsVerified, Long firstBrokenSegment) {}

    private final AuditEntryRepository auditEntryRepository;
    private final AuditChainSegmentRepository segmentRepository;
    private final WorkspaceRlsBinder workspaceRlsBinder;
    private final JdbcTemplate jdbcTemplate;

    public AuditChainVerifier(AuditEntryRepository auditEntryRepository, AuditChainSegmentRepository segmentRepository,
                              WorkspaceRlsBinder workspaceRlsBinder, JdbcTemplate jdbcTemplate) {
        this.auditEntryRepository = auditEntryRepository;
        this.segmentRepository = segmentRepository;
        this.workspaceRlsBinder = workspaceRlsBinder;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Scheduled(cron = "${d2os.governance.audit.verify-cron:0 30 * * * *}")
    public void verifyAllWorkspaces() {
        for (UUID workspaceId : jdbcTemplate.queryForList("SELECT id FROM list_active_workspace_ids()", UUID.class)) {
            verifyWorkspace(workspaceId);
        }
    }

    /** Recompute every segment for {@code workspaceId} and check the chain end to end. */
    @Transactional
    public ChainResult verifyWorkspace(UUID workspaceId) {
        WorkspaceContext.set(workspaceId);
        try {
            workspaceRlsBinder.bindCurrentTransaction(workspaceId);
            List<AuditChainSegment> segments = segmentRepository.findByWorkspaceIdOrderBySegmentSeqAsc(workspaceId);
            List<SegmentResult> results = new ArrayList<>();
            String expectedPrevHash = AuditChainSegment.GENESIS_HASH;
            Long firstBroken = null;

            for (AuditChainSegment segment : segments) {
                List<AuditEntryRecord> range = entriesInRange(workspaceId, segment);
                String recomputed = HashUtil.sha256Hex(AuditChainCanonicalizer.canonicalize(range));
                boolean linkIntact = expectedPrevHash.equals(segment.getPrevSegmentHash());
                boolean hashIntact = recomputed.equals(segment.getSegmentHash());
                boolean intact = linkIntact && hashIntact;
                results.add(new SegmentResult(segment.getSegmentSeq(), intact, segment.getSegmentHash(), recomputed));

                segment.markVerified(OffsetDateTime.now());
                segmentRepository.save(segment);

                if (!intact && firstBroken == null) {
                    firstBroken = segment.getSegmentSeq();
                    raiseTamperAlert(workspaceId, segment);
                }
                expectedPrevHash = segment.getSegmentHash();   // chain continues on the RECORDED hash either way
            }

            boolean chainIntact = results.stream().allMatch(SegmentResult::intact);
            return new ChainResult(chainIntact, results.size(), firstBroken);
        } finally {
            WorkspaceContext.clear();
        }
    }

    private List<AuditEntryRecord> entriesInRange(UUID workspaceId, AuditChainSegment segment) {
        OffsetDateTime fromTime = auditEntryRepository.findById(segment.getFromEntryId())
                .map(AuditEntryRecord::getTxTime).orElse(null);
        OffsetDateTime toTime = auditEntryRepository.findById(segment.getToEntryId())
                .map(AuditEntryRecord::getTxTime).orElse(null);
        if (fromTime == null || toTime == null) {
            return List.of();   // a sealed entry no longer exists at all — recomputes empty, so hash mismatches
        }
        return auditEntryRepository.findByWorkspaceIdOrderByTxTimeAscIdAsc(workspaceId).stream()
                .filter(e -> !e.getTxTime().isBefore(fromTime) && !e.getTxTime().isAfter(toTime))
                .toList();
    }

    private void raiseTamperAlert(UUID workspaceId, AuditChainSegment segment) {
        UUID notificationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO in_app_notification (id, workspace_id, recipient_role, source_module, type, subject_ref, message) "
                        + "VALUES (?, ?, 'reviewer', 'governance', 'TAMPER_ALERT', ?::jsonb, ?)",
                notificationId, workspaceId, "{\"segmentId\":\"" + segment.getId() + "\"}",
                "Audit chain segment " + segment.getSegmentSeq() + " failed verification — possible tampering");
        jdbcTemplate.update(
                "INSERT INTO event_outbox (id, workspace_id, aggregate_type, aggregate_id, event_type, payload) "
                        + "VALUES (?, ?, 'audit_chain_segment', ?, 'TAMPER_ALERT', ?::jsonb)",
                UUID.randomUUID(), workspaceId, segment.getId(),
                "{\"segmentId\":\"" + segment.getId() + "\",\"segmentSeq\":" + segment.getSegmentSeq() + "}");
    }
}
