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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * T013 — US1 acceptance IT (SC-001/002, FR-002/015): seed case/artifact/gate data directly (no
 * engine/AI involved — this exercises the {@code projection} module in isolation, which is
 * appropriate since {@link Projector}/{@link RebuildJob} only ever read already-committed source
 * rows and the outbox, never anything engine-specific), run {@link Projector#sweep()} to build
 * generation 0 from the outbox + table scans, then trigger {@link RebuildJob} on demand and assert
 * the verify-then-flip contract: a matching rebuild flips {@code live_generation} atomically and
 * purges the old generation; a seeded divergence FAILS loudly, the prior generation stays live, and
 * a divergence alert is recorded.
 *
 * <p><b>Cannot actually run in this environment</b> — Testcontainers/Docker confirmed non-functional
 * in this sandbox since Phase 1+2's own report (a pre-existing environment/tooling mismatch, not
 * something this phase's changes caused). Written to be logically sound against the real {@link
 * Projector}/{@link RebuildJob}/{@code EquivalenceVerifier}/{@code GraphWriteRepository} code, and
 * against the actual {@code case_instance}/{@code artifact_revision}/{@code gate_instance}/{@code
 * event_outbox} schemas (V4/V6/V20/V28), traced by hand rather than asserted to pass — same posture
 * {@code GateFlowIT}/{@code PublishGovernanceIT} document for the same reason.
 *
 * <p>Follows the {@code AuditGrantSuiteIT}/{@code GateFlowIT} IT skeleton: Testcontainers via {@link
 * ContainerFixtures#startAll()}, {@code @DynamicPropertySource}, direct-JDBC tenancy/domain seeding
 * with an explicit {@code SET app.workspace_id} on the seeding connection (RLS). This class ALSO
 * sets {@code spring.datasource.projector.url} — the one property {@code application.properties}'s
 * test defaults do not already cover (username/password are pre-wired there; the URL still needs to
 * point at the same Testcontainers Postgres instance every other datasource in the test uses).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RebuildEquivalenceIT {

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
        // application.properties already pins spring.datasource.projector.username/password to
        // d2os_projector/d2os_projector; only the URL needs pointing at the Testcontainers instance.
        registry.add("spring.datasource.projector.url", () -> jdbcUrl);
        registry.add("d2os.storage.endpoint", () -> ContainerFixtures.MINIO.getS3URL());
        registry.add("d2os.storage.access-key", ContainerFixtures.MINIO::getUserName);
        registry.add("d2os.storage.secret-key", ContainerFixtures.MINIO::getPassword);
    }

    @Test
    void rebuildProducesAnEquivalentGenerationAndFlipsAtomically() throws Exception {
        UUID workspaceId = UUID.randomUUID();
        UUID caseId = seedCaseArtifactAndGate(workspaceId);

        // 1. Incremental projection builds generation 0 from the outbox + table scans.
        projector.sweep();

        assertEquals(0, currentLiveGeneration(workspaceId), "Projector bootstraps generation 0");
        assertEquals(1L, countGraphNodes(workspaceId, 0, "CASE", caseId.toString()),
                "CASE node projected into generation 0");
        assertEquals(1L, countAllGraphNodes(workspaceId, 0), "generation 0 has SOME projected content");

        // 2. Trigger a rebuild -> generation 1, equivalence PASS, atomic flip, generation 0 purged.
        assertTrue(rebuildJob.triggerAsync(workspaceId), "no rebuild already in progress");
        waitUntilNotInProgress(workspaceId);

        assertEquals(1, currentLiveGeneration(workspaceId), "a PASSing rebuild flips live_generation to N+1");
        assertEquals("PASS", jdbcTemplate.queryForObject(
                "SELECT last_equivalence_result FROM projection_state WHERE workspace_id = ?", String.class, workspaceId));
        assertEquals(0L, countAllGraphNodes(workspaceId, 0), "the old generation is purged after a PASSing flip");
        assertEquals(1L, countGraphNodes(workspaceId, 1, "CASE", caseId.toString()),
                "generation 1 contains the same CASE node under its natural key");

        // Every node in the flipped generation still carries real provenance (FR-003).
        Long unsourced = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM graph_node WHERE workspace_id = ? AND generation = 1 "
                        + "AND (source_ref IS NULL OR source_kind IS NULL)",
                Long.class, workspaceId);
        assertEquals(0L, unsourced, "no unsourced element survives into the flipped generation");
    }

    @Test
    void seededDivergenceFailsTheRebuildAndKeepsThePriorGenerationLive() throws Exception {
        UUID workspaceId = UUID.randomUUID();
        seedCaseArtifactAndGate(workspaceId);
        projector.sweep(); // generation 0 lives

        // Seed a divergence the projector/rebuild pair would never itself produce: a phantom
        // graph_node planted directly into what generation 1 (current live 0 + 1) will become,
        // bypassing NodeEdgeMapper/GraphWriteRepository entirely — a row RebuildJob's own build step
        // will neither write nor (since it only upserts, never deletes pre-existing rows in the
        // generation it is about to check) remove. Truth has no such row, so EquivalenceVerifier's
        // CASE-type digest mismatches and the rebuild must FAIL.
        try (Connection conn = projectorDataSource.getConnection()) {
            setWorkspace(conn, workspaceId);
            insert(conn,
                    "INSERT INTO graph_node (id, workspace_id, generation, node_type, natural_key, label, "
                            + "attributes, source_kind, source_ref, projected_at) "
                            + "VALUES (gen_random_uuid(), ?, 1, 'CASE', ?, 'phantom-unsourced-in-truth', "
                            + "'{}'::jsonb, 'TRACE_LINK', ?, now())",
                    workspaceId, UUID.randomUUID().toString(), UUID.randomUUID().toString());
        }

        assertTrue(rebuildJob.triggerAsync(workspaceId));
        waitUntilNotInProgress(workspaceId);

        assertEquals(0, currentLiveGeneration(workspaceId), "a FAILing rebuild must not flip — generation 0 stays live");
        assertEquals("FAIL", jdbcTemplate.queryForObject(
                "SELECT last_equivalence_result FROM projection_state WHERE workspace_id = ?", String.class, workspaceId));
        assertEquals(0L, countAllGraphNodes(workspaceId, 1), "the divergent N+1 generation is dropped on FAIL");
        assertTrue(countAllGraphNodes(workspaceId, 0) > 0, "generation 0 is never dropped on a FAILing rebuild");

        Long alertRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM projection_gap WHERE workspace_id = ? AND event_type LIKE 'REBUILD_DIVERGENCE%'",
                Long.class, workspaceId);
        assertTrue(alertRows >= 1, "a divergence alert (log + projection_gap-style record) is recorded — never silently absorbed");
    }

    // ---- seeding -------------------------------------------------------------------------------

    private UUID seedCaseArtifactAndGate(UUID workspaceId) throws Exception {
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
                    workspaceId, "rebuild-eq-ws", "test");
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

            // Case lifecycle event — CaseLifecycleFact is assembled by re-reading case_instance
            // directly (see Projector's javadoc), so the payload content itself is irrelevant here.
            insert(conn, "INSERT INTO event_outbox (id, workspace_id, aggregate_type, aggregate_id, event_type, "
                            + "payload) VALUES (?, ?, 'case_instance', ?, 'Delivered', '{}'::jsonb)",
                    UUID.randomUUID(), workspaceId, caseId);

            // Gate event — the full GateEventPayload shape (matches GateEventPublisher#basePayload).
            String gatePayload = "{\"eventType\":\"GATE_OPENED\",\"gateId\":\"" + gateId + "\","
                    + "\"gateType\":\"REVIEW\",\"gateDefinitionKey\":\"review-gate\",\"gateDefinitionVersion\":1,"
                    + "\"caseInstanceId\":\"" + caseId + "\",\"workspaceId\":\"" + workspaceId + "\","
                    + "\"subjectArtifactRevisionId\":\"" + revisionId + "\",\"escalationPolicyKey\":null,"
                    + "\"escalationPolicyVersion\":null,\"occurredAt\":\"" + Instant.now() + "\"}";
            insert(conn, "INSERT INTO event_outbox (id, workspace_id, aggregate_type, aggregate_id, event_type, "
                            + "payload) VALUES (?, ?, 'gate_instance', ?, 'GATE_OPENED', ?::jsonb)",
                    UUID.randomUUID(), workspaceId, gateId, gatePayload);
        }
        return caseId;
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

    private Integer currentLiveGeneration(UUID workspaceId) {
        return jdbcTemplate.queryForObject(
                "SELECT live_generation FROM projection_state WHERE workspace_id = ?", Integer.class, workspaceId);
    }

    private long countAllGraphNodes(UUID workspaceId, int generation) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM graph_node WHERE workspace_id = ? AND generation = ?",
                Long.class, workspaceId, generation);
    }

    private long countGraphNodes(UUID workspaceId, int generation, String nodeType, String naturalKey) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM graph_node WHERE workspace_id = ? AND generation = ? "
                        + "AND node_type = ? AND natural_key = ?",
                Long.class, workspaceId, generation, nodeType, naturalKey);
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
