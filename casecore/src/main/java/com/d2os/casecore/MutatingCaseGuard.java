package com.d2os.casecore;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 4 Q2 optimistic single-active-mutating-case guard on the Feature aggregate (research R3,
 * FR-012/013, V17 {@code tenancy} columns). Each operation is a single guarded UPDATE — never a
 * retry loop, never a row lock held across the case lifecycle.
 *
 * <p><b>Not yet wired into {@link CaseService}</b> — this is the standalone guard service only.
 * Wiring {@link #acquire} into case creation (mutating case types) and {@link #release} into the
 * terminal-transition transaction is later work (T018/T027).
 */
@Service
public class MutatingCaseGuard {

    private final JdbcTemplate jdbcTemplate;

    public MutatingCaseGuard(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Acquire the Feature's single mutating-case slot for {@code caseId}. Guarded UPDATE:
     * {@code SET active_mutating_case_id=:caseId, aggregate_version=aggregate_version+1
     * WHERE id=:featureId AND aggregate_version=:expectedVersion AND active_mutating_case_id IS NULL}.
     * Zero rows updated means either the version was stale or the slot was already occupied —
     * either way this is a conflict (never a queue, never a retry): throws
     * {@link MutatingGuardConflictException} carrying the Feature id and (best-effort, informational
     * only) the case currently holding the slot.
     */
    @Transactional
    public void acquire(UUID featureId, long expectedVersion, UUID caseId) {
        int updated = jdbcTemplate.update(
                "UPDATE feature SET active_mutating_case_id = ?, aggregate_version = aggregate_version + 1 "
                        + "WHERE id = ? AND aggregate_version = ? AND active_mutating_case_id IS NULL",
                caseId, featureId, expectedVersion);
        if (updated == 0) {
            throw new MutatingGuardConflictException(featureId, currentActiveCaseId(featureId));
        }
    }

    /**
     * Release the slot held by {@code caseId} on its terminal transition (Delivered/Cancelled/Failed,
     * same transaction as the transition). Idempotent — a slot not currently held by {@code caseId}
     * (already released, or never acquired e.g. an Assessment case) is a harmless no-op.
     */
    @Transactional
    public void release(UUID caseId) {
        jdbcTemplate.update(
                "UPDATE feature SET active_mutating_case_id = NULL, aggregate_version = aggregate_version + 1 "
                        + "WHERE active_mutating_case_id = ?",
                caseId);
    }

    private UUID currentActiveCaseId(UUID featureId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT active_mutating_case_id FROM feature WHERE id = ?", featureId);
        if (rows.isEmpty()) {
            return null;
        }
        Object value = rows.get(0).get("active_mutating_case_id");
        return value == null ? null : UUID.fromString(value.toString());
    }
}
