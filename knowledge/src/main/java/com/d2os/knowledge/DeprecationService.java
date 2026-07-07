package com.d2os.knowledge;

import com.d2os.casecore.AuditWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Retires KnowledgeItem versions and flags — never rewrites — the history that referenced them (US3,
 * T028, FR-014/015/016, research R8). In ONE transaction {@link #deprecate} does exactly two things:
 *
 * <ol>
 *   <li><b>Retire</b> every currently-PUBLISHED item in the {@code [fromVersion, toVersion]} range for
 *       the key: flip {@code status → DEPRECATED} (append-only — content/hash untouched, so any snapshot
 *       that pinned the version still replays byte-identically, FR-016) and write one immutable
 *       {@code AuditEntry} per retired version.</li>
 *   <li><b>Flag</b> the affected history: an insert-select over {@code knowledge_injection_snapshot}
 *       inserts one {@code knowledge_affected_execution} row per distinct {@code operation_execution}
 *       whose snapshot referenced a retired version — reaching the owning case through
 *       {@code persona_invocation}. The snapshots and outputs are read-only here; they are NEVER
 *       modified (Principle III, FR-016).</li>
 * </ol>
 *
 * <p><b>Governance record = AuditEntry, not Decision.</b> The {@code decision} table (V4) is
 * NOT-NULL {@code case_instance_id} and CHECK-constrains {@code decision_type} to the case D-gates
 * (D1–D4); a knowledge-item deprecation is bound to no single case and is not a D-gate, so it is
 * recorded in the immutable {@code audit_entry} stream (the system-of-record vehicle for FR-016)
 * rather than forced into a shape the schema forbids.
 *
 * <p>New-operation retrieval already excludes DEPRECATED items — {@link KnowledgeRetrievalService}'s
 * query filters {@code status = 'PUBLISHED'} (T029) — while in-flight snapshotted envelopes, being
 * decoupled soft references, are unaffected.
 */
@Service
public class DeprecationService {

    // Flags every distinct execution whose injection snapshot referenced a retired (key, version) in
    // range, resolving the owning case via persona_invocation. NOT EXISTS keeps it idempotent so a
    // retry never double-flags. RLS confines both sides to the current workspace; the explicit
    // workspace_id predicate keeps the scan scoped even under an admin/system context.
    private static final String FLAG_AFFECTED_SQL = """
            INSERT INTO knowledge_affected_execution
                (workspace_id, knowledge_item_key, knowledge_item_version, operation_execution_id, case_instance_id)
            SELECT DISTINCT s.workspace_id, s.knowledge_item_key, s.knowledge_item_version,
                   s.operation_execution_id, pi.case_instance_id
              FROM knowledge_injection_snapshot s
              JOIN operation_execution oe ON oe.id = s.operation_execution_id
              JOIN persona_invocation   pi ON pi.id = oe.persona_invocation_id
             WHERE s.workspace_id = ?
               AND s.knowledge_item_key = ?
               AND s.knowledge_item_version BETWEEN ? AND ?
               AND NOT EXISTS (
                   SELECT 1 FROM knowledge_affected_execution a
                    WHERE a.operation_execution_id = s.operation_execution_id
                      AND a.knowledge_item_key = s.knowledge_item_key
                      AND a.knowledge_item_version = s.knowledge_item_version)
            """;

    private final KnowledgeItemRepository itemRepository;
    private final KnowledgeAffectedExecutionRepository affectedExecutionRepository;
    private final JdbcTemplate jdbcTemplate;
    private final AuditWriter auditWriter;

    public DeprecationService(KnowledgeItemRepository itemRepository,
                              KnowledgeAffectedExecutionRepository affectedExecutionRepository,
                              JdbcTemplate jdbcTemplate,
                              AuditWriter auditWriter) {
        this.itemRepository = itemRepository;
        this.affectedExecutionRepository = affectedExecutionRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.auditWriter = auditWriter;
    }

    /** The outcome of a deprecation: how many versions were retired and how many executions were flagged. */
    public record DeprecationResult(List<UUID> deprecatedItemIds, int flaggedExecutionCount) {}

    /**
     * Acknowledge a deprecation flag (US3, T043): flip the {@code knowledge_affected_execution}'s
     * {@code review_status} OPEN → REVIEWED and audit the acknowledgement, in ONE transaction. Only the
     * flag row is touched — the flagged {@code operation_execution}, its snapshot, and its output are
     * never modified (FR-016). Idempotent-safe: re-reviewing an already-REVIEWED flag simply re-audits.
     *
     * @throws NoSuchElementException the flag id does not exist (or is outside the caller's workspace)
     */
    @Transactional
    public KnowledgeAffectedExecution reviewAffectedExecution(UUID flagId, String actor) {
        KnowledgeAffectedExecution flag = affectedExecutionRepository.findById(flagId)
                .orElseThrow(() -> new NoSuchElementException("affected-execution flag " + flagId));
        flag.markReviewed();
        affectedExecutionRepository.save(flag);
        auditWriter.record(flag.getWorkspaceId(), "knowledge_affected_execution", flag.getId(),
                "AFFECTED_EXECUTION_REVIEWED", actor,
                Map.of("operationExecutionId", flag.getOperationExecutionId().toString(),
                        "knowledgeItemKey", flag.getKnowledgeItemKey(),
                        "knowledgeItemVersion", flag.getKnowledgeItemVersion()));
        return flag;
    }

    /**
     * Deprecate a single KnowledgeItem by id (the {@code POST /items/{id}/deprecate} entry point).
     *
     * @throws NoSuchElementException   the item id does not exist
     * @throws AlreadyDeprecatedException the item is already DEPRECATED (→ 409)
     */
    @Transactional
    public DeprecationResult deprecateItem(UUID itemId, String reason, String actor) {
        KnowledgeItem item = itemRepository.findById(itemId)
                .orElseThrow(() -> new NoSuchElementException("knowledge item " + itemId));
        if ("DEPRECATED".equals(item.getStatus())) {
            throw new AlreadyDeprecatedException("knowledge item " + itemId + " is already DEPRECATED");
        }
        return deprecate(item.getWorkspaceId(), item.getKey(), item.getVersion(), item.getVersion(), reason, actor);
    }

    /**
     * Deprecate every currently-PUBLISHED version of {@code key} in {@code [fromVersion, toVersion]} and
     * flag the executions that injected any retired version. Retiring an already-DEPRECATED-only range is
     * a no-op that flags nothing (versions are skipped, no rows inserted).
     *
     * @return the retired item ids and the count of newly-flagged executions
     */
    @Transactional
    public DeprecationResult deprecate(UUID workspaceId, String key, int fromVersion, int toVersion,
                                       String reason, String actor) {
        List<KnowledgeItem> versions = itemRepository.findByWorkspaceIdAndKeyOrderByVersionDesc(workspaceId, key);
        List<UUID> retired = versions.stream()
                .filter(i -> i.getVersion() >= fromVersion && i.getVersion() <= toVersion)
                .filter(i -> "PUBLISHED".equals(i.getStatus()))
                .map(item -> {
                    item.deprecate(reason);
                    itemRepository.save(item);
                    // Immutable audit of the governance action (FR-016) — the deprecation's system of record.
                    auditWriter.record(workspaceId, "knowledge_item", item.getId(), "DEPRECATED", actor,
                            Map.of("key", key, "version", item.getVersion(), "reason", safe(reason)));
                    return item.getId();
                })
                .toList();

        int flagged = 0;
        if (!retired.isEmpty()) {
            // Flag over the requested range so every retired version's referencing executions are caught
            // in one pass; NOT EXISTS makes re-flagging harmless.
            flagged = jdbcTemplate.update(FLAG_AFFECTED_SQL, workspaceId, key, fromVersion, toVersion);
        }
        return new DeprecationResult(retired, flagged);
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }
}
