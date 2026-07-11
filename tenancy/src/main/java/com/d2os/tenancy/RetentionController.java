package com.d2os.tenancy;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** {@code GET/PUT /workspace/retention} (T042, contracts/api.yaml, NFR-5, FR-014). */
@RestController
@RequestMapping("/api/v1/workspace/retention")
public class RetentionController {

    /** The regulatory floor (V23's own DEFAULT) — {@code PUT} below this is rejected 422. */
    private static final int MINIMUM_RETENTION_YEARS = 7;

    private final JdbcTemplate jdbcTemplate;

    public RetentionController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping
    public RetentionPolicy get() {
        UUID workspaceId = WorkspaceContext.require();
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT retention_years, retention_policy_notes FROM workspace WHERE id = ?", workspaceId);
        return new RetentionPolicy((Integer) row.get("retention_years"), (String) row.get("retention_policy_notes"));
    }

    @PutMapping
    public ResponseEntity<RetentionPolicy> set(@RequestBody RetentionPolicy request) {
        if (request.retentionYears() == null || request.retentionYears() < MINIMUM_RETENTION_YEARS) {
            return ResponseEntity.unprocessableEntity().build();
        }
        UUID workspaceId = WorkspaceContext.require();
        jdbcTemplate.update("UPDATE workspace SET retention_years = ?, retention_policy_notes = ? WHERE id = ?",
                request.retentionYears(), request.notes(), workspaceId);
        return ResponseEntity.ok(request);
    }

    public record RetentionPolicy(Integer retentionYears, String notes) {}
}
