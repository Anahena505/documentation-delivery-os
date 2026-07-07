package com.d2os.casecore;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-workspace AI-cost rollup and ceiling (T036/T037, FR-017, T5-b). Consumption is incremented in
 * the same transaction as each OperationExecution cost record, so concurrent Cases in a workspace
 * cannot collectively evade the ceiling. A {@code token_cap} of 0 means unlimited (the default), so a
 * workspace only enforces a ceiling once one is set.
 */
@Service
public class WorkspaceBudgetService {

    private final JdbcTemplate jdbcTemplate;

    public WorkspaceBudgetService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private void ensureRow(UUID workspaceId) {
        jdbcTemplate.update(
                "INSERT INTO workspace_budget (workspace_id) VALUES (?) ON CONFLICT (workspace_id) DO NOTHING",
                workspaceId);
    }

    /** True if the estimated call would push the workspace over a set (non-zero) cap. */
    public boolean wouldExceed(UUID workspaceId, long estimatedTokens) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT token_cap, tokens_consumed FROM workspace_budget WHERE workspace_id = ?", workspaceId);
        if (rows.isEmpty()) return false;
        long cap = ((Number) rows.get(0).get("token_cap")).longValue();
        long consumed = ((Number) rows.get(0).get("tokens_consumed")).longValue();
        return cap > 0 && consumed + estimatedTokens > cap;
    }

    @Transactional
    public void recordConsumption(UUID workspaceId, long tokens) {
        ensureRow(workspaceId);
        jdbcTemplate.update(
                "UPDATE workspace_budget SET tokens_consumed = tokens_consumed + ?, updated_at = now() "
                        + "WHERE workspace_id = ?", tokens, workspaceId);
    }

    /** Sets (or resets) a workspace cap — used by ops tooling and the workspace-cap regression test. */
    @Transactional
    public void setCap(UUID workspaceId, long tokenCap) {
        ensureRow(workspaceId);
        jdbcTemplate.update("UPDATE workspace_budget SET token_cap = ? WHERE workspace_id = ?",
                tokenCap, workspaceId);
    }

    public BudgetView get(UUID workspaceId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT period_start, token_cap, tokens_consumed, rate_limit_per_minute "
                        + "FROM workspace_budget WHERE workspace_id = ?", workspaceId);
        if (rows.isEmpty()) {
            return new BudgetView(null, 0, 0, 0);
        }
        Map<String, Object> r = rows.get(0);
        return new BudgetView(
                String.valueOf(r.get("period_start")),
                ((Number) r.get("token_cap")).longValue(),
                ((Number) r.get("tokens_consumed")).longValue(),
                ((Number) r.get("rate_limit_per_minute")).intValue());
    }

    public record BudgetView(String periodStart, long tokenCap, long tokensConsumed, int rateLimitPerMinute) {}
}
