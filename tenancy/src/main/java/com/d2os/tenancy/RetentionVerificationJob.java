package com.d2os.tenancy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Report-only retention-horizon check (Phase 7 US5, T041, research R6, NFR-5, FR-014, Principle V).
 * Reads each workspace's {@code retention_years} (V23) and flags delivered packages approaching (within
 * 90 days) or past their policy horizon (measured from {@code execution_package.created_at}, the
 * closest thing to a "delivered at" timestamp this schema carries). NEVER deletes anything — disposal
 * stays an explicit, separately-governed action; this job's entire output is a set of {@code
 * in_app_notification} rows (raw JDBC — {@code tenancy} is the foundational module with no dependency
 * on {@code artifacts}/{@code governance}, which own the tables this reads/writes; same
 * cross-module-raw-write convention {@code ArtifactService}/{@code AuditChainVerifier} already use).
 */
@Component
public class RetentionVerificationJob {

    private static final int APPROACHING_WINDOW_DAYS = 90;

    private final JdbcTemplate jdbcTemplate;

    public RetentionVerificationJob(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Scheduled(cron = "${d2os.tenancy.retention.check-cron:0 0 3 * * *}")
    public void checkAllWorkspaces() {
        for (UUID workspaceId : jdbcTemplate.queryForList("SELECT id FROM list_active_workspace_ids()", UUID.class)) {
            checkWorkspace(workspaceId);
        }
    }

    /** Returns the number of packages flagged (approaching or over) for {@code workspaceId}. */
    @Transactional
    public int checkWorkspace(UUID workspaceId) {
        WorkspaceContext.set(workspaceId);
        try {
            Integer retentionYears = jdbcTemplate.queryForObject(
                    "SELECT retention_years FROM workspace WHERE id = ?", Integer.class, workspaceId);
            if (retentionYears == null) {
                return 0;
            }
            OffsetDateTime horizon = OffsetDateTime.now().minusYears(retentionYears);
            OffsetDateTime approachingFrom = horizon.plusDays(APPROACHING_WINDOW_DAYS);

            List<Map<String, Object>> packages = jdbcTemplate.queryForList(
                    "SELECT id, created_at FROM execution_package WHERE workspace_id = ? AND created_at < ?",
                    workspaceId, approachingFrom);

            int flagged = 0;
            for (Map<String, Object> pkg : packages) {
                UUID packageId = (UUID) pkg.get("id");
                OffsetDateTime createdAt = ((java.sql.Timestamp) pkg.get("created_at")).toInstant().atOffset(java.time.ZoneOffset.UTC);
                boolean over = createdAt.isBefore(horizon);
                recordFinding(workspaceId, packageId, retentionYears, over);
                flagged++;
            }
            return flagged;
        } finally {
            WorkspaceContext.clear();
        }
    }

    private void recordFinding(UUID workspaceId, UUID packageId, int retentionYears, boolean over) {
        String type = over ? "RETENTION_OVER" : "RETENTION_APPROACHING";
        String message = over
                ? "Package " + packageId + " is past its " + retentionYears + "-year retention horizon (report-only, no auto-deletion)"
                : "Package " + packageId + " is approaching its " + retentionYears + "-year retention horizon";
        jdbcTemplate.update(
                "INSERT INTO in_app_notification (id, workspace_id, recipient_role, source_module, type, subject_ref, message) "
                        + "VALUES (?, ?, 'reviewer', 'tenancy', ?, ?::jsonb, ?)",
                UUID.randomUUID(), workspaceId, type, "{\"packageId\":\"" + packageId + "\"}", message);
    }
}
