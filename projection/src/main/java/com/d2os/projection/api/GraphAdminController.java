package com.d2os.projection.api;

import com.d2os.projection.ProjectionGap;
import com.d2os.projection.ProjectionGapRepository;
import com.d2os.projection.RebuildJob;
import com.d2os.tenancy.WorkspaceContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * T012 — {@code contracts/api.yaml}'s graph-admin surface (research R4/R5/R6, FR-002/011/015).
 * Read endpoints run on the current request's normal RLS-bound datasource connection (the same
 * transparent-RLS-via-request-scoped-DataSource convention every other controller in this repo
 * relies on — see {@code WorkspaceBudgetController} for the identical {@code
 * WorkspaceContext.require()} pattern); the rebuild trigger delegates entirely to {@link
 * RebuildJob}, which manages its own datasource/RLS binding for the background work it kicks off.
 */
@RestController
@RequestMapping("/api/v1/graph/admin")
public class GraphAdminController {

    private final RebuildJob rebuildJob;
    private final JdbcTemplate jdbcTemplate;
    private final ProjectionGapRepository projectionGapRepository;

    private static final String CONSUMER = "graph-projector";

    public GraphAdminController(RebuildJob rebuildJob, JdbcTemplate jdbcTemplate,
                                ProjectionGapRepository projectionGapRepository) {
        this.rebuildJob = rebuildJob;
        this.jdbcTemplate = jdbcTemplate;
        this.projectionGapRepository = projectionGapRepository;
    }

    /** {@code POST /graph/admin/rebuild} — 202 accepted (async, result via {@code /status}); 409 if already running. */
    @PostMapping("/rebuild")
    public ResponseEntity<Void> rebuild() {
        UUID workspaceId = WorkspaceContext.require();
        boolean started = rebuildJob.triggerAsync(workspaceId);
        return started ? ResponseEntity.status(HttpStatus.ACCEPTED).build()
                       : ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    /** {@code GET /graph/admin/status} — live generation, watermark lag, last equivalence result, open-gap count. */
    @GetMapping("/status")
    public ProjectionStatusView status() {
        UUID workspaceId = WorkspaceContext.require();

        List<java.util.Map<String, Object>> stateRows = jdbcTemplate.queryForList(
                "SELECT live_generation, last_equivalence_check, last_equivalence_result "
                        + "FROM projection_state WHERE workspace_id = ?",
                workspaceId);
        int liveGeneration = 0;
        OffsetDateTime lastEquivalenceCheck = null;
        String lastEquivalenceResult = null;
        if (!stateRows.isEmpty()) {
            java.util.Map<String, Object> row = stateRows.get(0);
            liveGeneration = (Integer) row.get("live_generation");
            Object checkRaw = row.get("last_equivalence_check");
            lastEquivalenceCheck = checkRaw instanceof java.sql.Timestamp ts
                    ? ts.toInstant().atOffset(java.time.ZoneOffset.UTC) : (OffsetDateTime) checkRaw;
            lastEquivalenceResult = (String) row.get("last_equivalence_result");
        }

        List<Long> watermarkRows = jdbcTemplate.queryForList(
                "SELECT outbox_watermark FROM projection_checkpoint WHERE consumer = ? AND workspace_id = ?",
                Long.class, CONSUMER, workspaceId);
        long watermark = watermarkRows.isEmpty() ? 0L : watermarkRows.get(0);

        Double lagSeconds = jdbcTemplate.queryForObject(
                "SELECT EXTRACT(EPOCH FROM (now() - MIN(created_at))) FROM event_outbox "
                        + "WHERE workspace_id = ? AND seq > ?",
                Double.class, workspaceId, watermark);

        long openGapCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM projection_gap WHERE workspace_id = ? AND status = 'OPEN'",
                Long.class, workspaceId);

        return new ProjectionStatusView(liveGeneration, rebuildJob.isInProgress(workspaceId),
                lagSeconds == null ? 0.0 : lagSeconds, lastEquivalenceCheck, lastEquivalenceResult, openGapCount);
    }

    /** {@code GET /graph/admin/gaps?status=OPEN|RESOLVED} — projection-sufficiency findings (FR-011). */
    @GetMapping("/gaps")
    public List<ProjectionGapView> gaps(@RequestParam(required = false) String status) {
        UUID workspaceId = WorkspaceContext.require();
        List<ProjectionGap> rows = status != null
                ? projectionGapRepository.findByWorkspaceIdAndStatus(workspaceId, status)
                : projectionGapRepository.findAll().stream().filter(g -> workspaceId.equals(g.getWorkspaceId())).toList();
        return rows.stream().map(ProjectionGapView::of).toList();
    }

    // ---- DTOs (contracts/api.yaml ProjectionStatus / ProjectionGap) --------------------------------

    public record ProjectionStatusView(int liveGeneration, boolean rebuildInProgress, double outboxLagSeconds,
                                       OffsetDateTime lastEquivalenceCheck, String lastEquivalenceResult,
                                       long openGapCount) {}

    public record ProjectionGapView(UUID id, UUID eventId, String eventType, List<String> missingFields,
                                    OffsetDateTime detectedAt, String status) {
        static ProjectionGapView of(ProjectionGap g) {
            return new ProjectionGapView(g.getId(), g.getEventId(), g.getEventType(),
                    List.of(g.getMissingFields()), g.getDetectedAt(), g.getStatus());
        }
    }
}
