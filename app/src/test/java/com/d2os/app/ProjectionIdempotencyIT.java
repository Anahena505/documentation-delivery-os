package com.d2os.app;

import com.d2os.projection.Projector;
import com.d2os.projection.RebuildJob;
import com.d2os.testsupport.ContainerFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * T015 — replay/idempotency acceptance IT (research R3, FR-004/012). Runs {@link
 * Projector#sweep()} twice over the SAME {@code event_outbox} range (the second pass driven by
 * resetting {@code projection_checkpoint.outbox_watermark} back to 0, simulating replay / at-least-
 * once duplicate delivery) plus the SAME table-scan sources ({@code artifact_revision}/{@code
 * trace_link}, which are re-read in full every sweep by design — see {@link Projector}'s "Full-
 * rescan tradeoff" javadoc) — and asserts the resulting {@code graph_node}/{@code graph_edge} row
 * SETS are byte-identical: same count, same content, no duplicates, no drift. This is the direct
 * consequence of every write being an {@code ON CONFLICT DO UPDATE} upsert keyed on the mapper's
 * deterministic natural keys (T006/T007) — replaying is a no-op by construction, not by luck.
 *
 * <p><b>Cannot actually run in this environment</b> (Testcontainers/Docker non-functional in this
 * sandbox, per Phase 1+2's report) — traced by hand against the real {@link Projector}/{@code
 * GraphWriteRepository} code, not asserted to pass.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ProjectionIdempotencyIT {

    @Autowired DataSource dataSource;
    @Autowired
    @Qualifier("projectorDataSource")
    DataSource projectorDataSource;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired Projector projector;
    @Autowired RebuildJob rebuildJob;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        ContainerFixtures.startAll();
        String jdbcUrl = ContainerFixtures.POSTGRES.getJdbcUrl();
        registry.add("spring.flyway.url", () -> jdbcUrl);
        registry.add("spring.flyway.user", ContainerFixtures.POSTGRES::getUsername);
        registry.add("spring.flyway.password", ContainerFixtures.POSTGRES::getPassword);
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", () -> "d2os_app");
        registry.add("spring.datasource.password", () -> "d2os_app");
        registry.add("spring.datasource.projector.url", () -> jdbcUrl);
        registry.add("d2os.storage.endpoint", () -> ContainerFixtures.MINIO.getS3URL());
        registry.add("d2os.storage.access-key", ContainerFixtures.MINIO::getUserName);
        registry.add("d2os.storage.secret-key", ContainerFixtures.MINIO::getPassword);
    }

    @Test
    void replayingTheSameOutboxRangeTwiceProducesAnIdenticalGraph() throws Exception {
        UUID workspaceId = UUID.randomUUID();
        seedCaseArtifactAndGate(workspaceId);

        projector.sweep(); // first pass: generation 0 built, watermark advanced past every event

        List<Map<String, Object>> nodesAfterFirst = graphNodeSnapshot(workspaceId);
        List<Map<String, Object>> edgesAfterFirst = graphEdgeSnapshot(workspaceId);
        assertFalse(nodesAfterFirst.isEmpty(), "the first sweep must have projected something");

        resetWatermarkToZero(workspaceId);

        projector.sweep(); // second pass: the SAME outbox rows (seq > 0) + the SAME table scans

        List<Map<String, Object>> nodesAfterSecond = graphNodeSnapshot(workspaceId);
        List<Map<String, Object>> edgesAfterSecond = graphEdgeSnapshot(workspaceId);

        assertEquals(nodesAfterFirst.size(), nodesAfterSecond.size(), "no duplicate nodes after replay");
        assertEquals(nodesAfterFirst, nodesAfterSecond,
                "identical node content after replay — true upsert idempotency (FR-012), not merely equal counts");
        assertEquals(edgesAfterFirst.size(), edgesAfterSecond.size(), "no duplicate edges after replay");
        assertEquals(edgesAfterFirst, edgesAfterSecond, "identical edge content after replay");
    }

    /**
     * FR-015's "reads during rebuild serve the last consistent generation, never a half-built one"
     * has two parts: (1) {@code /graph/admin/status} must report {@code rebuildInProgress=true}
     * while a rebuild runs — directly tested here via {@link RebuildJob#isInProgress}, the same flag
     * {@code GraphAdminController#status} exposes; (2) a query mid-rebuild must never observe a
     * half-built generation — that part is a STRUCTURAL guarantee of {@link RebuildJob}'s design
     * rather than something a timing-dependent test can force deterministically without an injected
     * delay hook this phase does not add: {@code live_generation} is flipped in ONE atomic
     * transaction only AFTER the full build+verify+PASS sequence completes, and every read filters
     * {@code generation = live_generation} — so a concurrent reader either sees the fully-committed
     * old generation (flip hasn't happened) or the fully-committed new one (flip has happened,
     * post-commit); there is no `generation` value a read could resolve to that was ever partially
     * written. Documented here rather than asserted by a race-prone test.
     */
    @Test
    void rebuildInProgressFlagIsVisibleWhileARebuildRuns() throws Exception {
        UUID workspaceId = UUID.randomUUID();
        seedCaseArtifactAndGate(workspaceId);
        projector.sweep(); // bootstrap generation 0 so RebuildJob has something to build N+1 from

        assertFalse(rebuildJob.isInProgress(workspaceId), "no rebuild running yet");
        assertTrue(rebuildJob.triggerAsync(workspaceId), "starts a rebuild");
        // The in-progress flag is set synchronously by triggerAsync itself (before the async task
        // even starts), so it is observable immediately — no race with the background thread needed.
        assertTrue(rebuildJob.isInProgress(workspaceId), "rebuildInProgress is true once triggered");
        assertFalse(rebuildJob.triggerAsync(workspaceId), "a second concurrent trigger is refused (409)");

        waitUntilNotInProgress(workspaceId);
        assertFalse(rebuildJob.isInProgress(workspaceId), "rebuildInProgress clears once the rebuild finishes");
    }

    // ---- seeding -------------------------------------------------------------------------------

    private void seedCaseArtifactAndGate(UUID workspaceId) throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID projectVersionId = UUID.randomUUID();
        UUID featureId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        UUID artifactId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID gateId = UUID.randomUUID();

        try (Connection conn = dataSource.getConnection()) {
            setWorkspace(conn, workspaceId);
            insert(conn, "INSERT INTO workspace (id, name, created_by) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                    workspaceId, "idempotency-ws", "test");
            insert(conn, "INSERT INTO project (id, workspace_id, name, created_by) VALUES (?, ?, ?, ?)",
                    projectId, workspaceId, "p", "test");
            insert(conn, "INSERT INTO project_version (id, workspace_id, project_id, label, created_by) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    projectVersionId, workspaceId, projectId, "v1", "test");
            insert(conn, "INSERT INTO feature (id, workspace_id, project_version_id, name, created_by) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    featureId, workspaceId, projectVersionId, "f", "test");
            insert(conn, "INSERT INTO case_instance (id, workspace_id, feature_id, submission_id, case_type_key, "
                            + "case_type_version, mode, status, token_budget, created_by) "
                            + "VALUES (?, ?, ?, ?, 'initiation', '1.0.0', 'mutating', 'Delivered', 1000, 'test')",
                    caseId, workspaceId, featureId, UUID.randomUUID());
            insert(conn, "INSERT INTO artifact (id, workspace_id, case_instance_id, template_definition_id, "
                            + "template_definition_version, artifact_type) VALUES (?, ?, ?, ?, '1.0.0', 'brd')",
                    artifactId, workspaceId, caseId, UUID.randomUUID());
            insert(conn, "INSERT INTO artifact_revision (id, workspace_id, artifact_id, revision_no, storage_ref, "
                            + "content_hash) VALUES (?, ?, ?, 1, 's3://x', 'deadbeef')",
                    revisionId, workspaceId, artifactId);
            insert(conn, "INSERT INTO gate_instance (id, workspace_id, case_instance_id, gate_type, "
                            + "gate_definition_key, gate_definition_version, subject_artifact_revision_id, "
                            + "inputs_ref, status) VALUES (?, ?, ?, 'REVIEW', 'review-gate', 1, ?, '{}'::jsonb, 'OPEN')",
                    gateId, workspaceId, caseId, revisionId);
            insert(conn, "INSERT INTO event_outbox (id, workspace_id, aggregate_type, aggregate_id, event_type, "
                            + "payload) VALUES (?, ?, 'case_instance', ?, 'Delivered', '{}'::jsonb)",
                    UUID.randomUUID(), workspaceId, caseId);

            String gatePayload = "{\"eventType\":\"GATE_OPENED\",\"gateId\":\"" + gateId + "\","
                    + "\"gateType\":\"REVIEW\",\"gateDefinitionKey\":\"review-gate\",\"gateDefinitionVersion\":1,"
                    + "\"caseInstanceId\":\"" + caseId + "\",\"workspaceId\":\"" + workspaceId + "\","
                    + "\"subjectArtifactRevisionId\":\"" + revisionId + "\",\"escalationPolicyKey\":null,"
                    + "\"escalationPolicyVersion\":null,\"occurredAt\":\"" + Instant.now() + "\"}";
            insert(conn, "INSERT INTO event_outbox (id, workspace_id, aggregate_type, aggregate_id, event_type, "
                            + "payload) VALUES (?, ?, 'gate_instance', ?, 'GATE_OPENED', ?::jsonb)",
                    UUID.randomUUID(), workspaceId, gateId, gatePayload);
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private void waitUntilNotInProgress(UUID workspaceId) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            if (!rebuildJob.isInProgress(workspaceId)) return;
            Thread.sleep(100);
        }
        fail("rebuild did not finish for workspace " + workspaceId + " within 30s");
    }

    private List<Map<String, Object>> graphNodeSnapshot(UUID workspaceId) {
        return jdbcTemplate.queryForList(
                "SELECT node_type, natural_key, label, attributes, source_kind, source_ref FROM graph_node "
                        + "WHERE workspace_id = ? ORDER BY node_type, natural_key",
                workspaceId);
    }

    private List<Map<String, Object>> graphEdgeSnapshot(UUID workspaceId) {
        return jdbcTemplate.queryForList(
                "SELECT ge.edge_type, gn1.node_type AS from_type, gn1.natural_key AS from_key, "
                        + "       gn2.node_type AS to_type, gn2.natural_key AS to_key, ge.source_ref "
                        + "FROM graph_edge ge "
                        + "JOIN graph_node gn1 ON gn1.id = ge.from_node "
                        + "JOIN graph_node gn2 ON gn2.id = ge.to_node "
                        + "WHERE ge.workspace_id = ? "
                        + "ORDER BY ge.edge_type, from_type, from_key, to_type, to_key, ge.source_ref",
                workspaceId);
    }

    /** Direct write to {@code projection_checkpoint} via the {@code d2os_projector} role (d2os_app has no write grant on it, V28). */
    private void resetWatermarkToZero(UUID workspaceId) throws Exception {
        try (Connection conn = projectorDataSource.getConnection()) {
            setWorkspace(conn, workspaceId);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE projection_checkpoint SET outbox_watermark = 0 WHERE consumer = 'graph-projector' "
                            + "AND workspace_id = ?")) {
                ps.setObject(1, workspaceId);
                ps.executeUpdate();
            }
        }
    }

    private void setWorkspace(Connection conn, UUID workspaceId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("SET app.workspace_id = '" + workspaceId + "'")) {
            ps.execute();
        }
    }

    private void insert(Connection conn, String sql, Object... params) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                Object p = params[i];
                if (p instanceof UUID uuid) ps.setObject(i + 1, uuid);
                else ps.setString(i + 1, p.toString());
            }
            ps.executeUpdate();
        }
    }
}
