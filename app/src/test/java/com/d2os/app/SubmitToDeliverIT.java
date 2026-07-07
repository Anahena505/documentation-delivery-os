package com.d2os.app;

import com.d2os.app.support.StubAiGatewayClient;
import com.d2os.testsupport.ContainerFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Full submit → classify → confirm → open case → start → Delivered flow (T040, quickstart.md
 * Scenario 1). Uses {@link StubAiGatewayClient} in place of a live provider (none is configured in
 * this environment) and real Postgres 16 + MinIO via Testcontainers.
 *
 * <p>Phase 2 (T019): the pipeline now runs the full 13-persona canonical suite via {@code
 * initiation-v2}, so this test additionally asserts the delivered package covers every persona
 * (SC-002) — not just that the Case reached Delivered (SC-001).
 */
// StubAiGatewayClient MUST be explicitly @Import-ed: it's a @TestConfiguration, which Spring
// deliberately excludes from component scanning (via TypeExcludeFilter), so @Import is the only
// thing that registers it — and registers it exactly once (the earlier double-registration was a
// symptom of the now-fixed duplicate @ComponentScan, not of @Import). Without this, the real
// HttpAiGatewayClient is injected and every persona step 401s against the live Anthropic API.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.context.annotation.Import(StubAiGatewayClient.class)
class SubmitToDeliverIT {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    DataSource dataSource;

    @Autowired
    ObjectMapper objectMapper;

    private static final UUID WORKSPACE_ID = UUID.randomUUID();
    private static UUID featureId;

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        ContainerFixtures.startAll();

        String jdbcUrl = ContainerFixtures.POSTGRES.getJdbcUrl();
        registry.add("spring.flyway.url", () -> jdbcUrl);
        registry.add("spring.flyway.user", ContainerFixtures.POSTGRES::getUsername);
        registry.add("spring.flyway.password", ContainerFixtures.POSTGRES::getPassword);
        // JPA connects as d2os_app (created by V8__app_role.sql during the Flyway run above);
        // Testcontainers' POSTGRES_USER is superuser, so it can create that role.
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", () -> "d2os_app");
        registry.add("spring.datasource.password", () -> "d2os_app");

        registry.add("d2os.storage.endpoint", () -> ContainerFixtures.MINIO.getS3URL());
        registry.add("d2os.storage.access-key", ContainerFixtures.MINIO::getUserName);
        registry.add("d2os.storage.secret-key", ContainerFixtures.MINIO::getPassword);
        registry.add("d2os.storage.bucket", () -> "d2os-artifacts");
    }

    @BeforeAll
    static void createBucket() {
        ContainerFixtures.startAll();
        S3Client s3 = S3Client.builder()
                .endpointOverride(URI.create(ContainerFixtures.MINIO.getS3URL()))
                .region(Region.US_EAST_1)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(ContainerFixtures.MINIO.getUserName(), ContainerFixtures.MINIO.getPassword())))
                .build();
        try {
            s3.createBucket(CreateBucketRequest.builder().bucket("d2os-artifacts").build());
        } catch (Exception ignored) {
            // MinIO container is shared/reused across test classes — the bucket may already exist.
        }
    }

    @BeforeEach
    void seedTenancy() throws Exception {
        // No REST endpoint exists to create Workspace/Project/Feature (out of Phase 1 scope) —
        // seeded directly here. RLS is genuinely enforced (V8 fix) even for this seed, so
        // app.workspace_id must be SET before the inserts — and since HikariCP may hand
        // JdbcTemplate a different pooled connection per call, everything runs on one explicitly
        // held Connection so the session-scoped SET actually applies to every statement below.
        UUID projectId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        featureId = UUID.randomUUID();

        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement setCtx = conn.prepareStatement("SET app.workspace_id = '" + WORKSPACE_ID + "'")) {
                setCtx.execute();
            }
            insert(conn, "INSERT INTO workspace (id, name, created_by) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                    WORKSPACE_ID, "it-workspace", "test");
            insert(conn, "INSERT INTO project (id, workspace_id, name, created_by) VALUES (?, ?, ?, ?)",
                    projectId, WORKSPACE_ID, "it-project", "test");
            insert(conn, "INSERT INTO project_version (id, workspace_id, project_id, label, created_by) VALUES (?, ?, ?, ?, ?)",
                    versionId, WORKSPACE_ID, projectId, "v1", "test");
            insert(conn, "INSERT INTO feature (id, workspace_id, project_version_id, name, created_by) VALUES (?, ?, ?, ?, ?)",
                    featureId, WORKSPACE_ID, versionId, "it-feature", "test");
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

    @Test
    void submittedProblemIsClassifiedPlannedRunAndDelivered() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Workspace-Id", WORKSPACE_ID.toString());
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

        // 1. Submit
        Map<String, Object> submissionBody = Map.of(
                "formData", Map.of("category", "initiation", "description", "Integration test submission"));
        ResponseEntity<Map> submissionResp = rest.exchange(
                url("/api/v1/submissions"), HttpMethod.POST, new HttpEntity<>(submissionBody, headers), Map.class);
        assertEquals(201, submissionResp.getStatusCode().value());
        String submissionId = (String) submissionResp.getBody().get("id");
        assertNotNull(submissionId);

        // 2. Confirm classification
        ResponseEntity<Map> confirmResp = rest.exchange(
                url("/api/v1/submissions/" + submissionId + "/confirm-classification"), HttpMethod.POST,
                new HttpEntity<>(Map.of("confirmedCaseType", "initiation"), headers), Map.class);
        assertEquals(200, confirmResp.getStatusCode().value());

        // 3. Open case (zero manual DB edits from here — SC-001)
        ResponseEntity<Map> caseResp = rest.exchange(
                url("/api/v1/cases"), HttpMethod.POST,
                new HttpEntity<>(Map.of("submissionId", submissionId, "featureId", featureId.toString()), headers),
                Map.class);
        assertEquals(201, caseResp.getStatusCode().value(), () -> "case create body: " + caseResp.getBody());
        String caseId = (String) caseResp.getBody().get("id");
        assertEquals("Planned", caseResp.getBody().get("status"));
        assertNotNull(caseResp.getBody().get("definitionSnapshot"));

        // 4. Start the pipeline (async — Flowable job executor drives it to completion)
        ResponseEntity<Void> startResp = rest.exchange(
                url("/api/v1/cases/" + caseId + "/start"), HttpMethod.POST, new HttpEntity<>(null, headers), Void.class);
        assertEquals(202, startResp.getStatusCode().value());

        // 5. Poll until Delivered (or Escalated, which would be a test failure). 13 async persona
        //    steps now run (full suite), so allow a wider window than the Phase 1 three-step pipeline.
        String finalStatus = pollUntilTerminal(caseId, headers, Duration.ofSeconds(120));
        assertEquals("Delivered", finalStatus, "case should reach Delivered, not escalate, with the stub gateway");

        // 6. Package + verify
        ResponseEntity<Map> pkgResp = rest.exchange(
                url("/api/v1/cases/" + caseId + "/package"), HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        assertEquals(200, pkgResp.getStatusCode().value());
        assertNotNull(pkgResp.getBody().get("manifestHash"));
        assertNotNull(pkgResp.getBody().get("handoverRecord"));

        ResponseEntity<Map> verifyResp = rest.exchange(
                url("/api/v1/cases/" + caseId + "/package/verify"), HttpMethod.POST, new HttpEntity<>(headers), Map.class);
        assertEquals(200, verifyResp.getStatusCode().value());
        assertTrue((Boolean) verifyResp.getBody().get("manifestHashValid"));

        // 6b. SC-002: the delivered package covers EVERY persona in the canonical Initiation suite —
        //     each contributes a validated artifact, none silently skipped.
        java.util.Set<String> expectedPersonas = java.util.Set.of(
                "intake-analyst", "business-analyst", "product-functional-analyst", "solution-architect",
                "api-designer", "security-architect", "ux-architect", "data-architect",
                "infrastructure-engineer", "qa-test-strategist", "risk-governance-officer",
                "delivery-planner", "technical-writer");
        java.util.Set<String> actualPersonas = artifactTypesFor(caseId);
        java.util.Set<String> missing = new java.util.TreeSet<>(expectedPersonas);
        missing.removeAll(actualPersonas);
        assertTrue(missing.isEmpty(),
                () -> "package must cover all 13 personas (SC-002); missing=" + missing
                        + " actual=" + new java.util.TreeSet<>(actualPersonas));

        // 7. Replay-audit (SC-008): every AI output reconstructs byte-identically from snapshots.
        ResponseEntity<Map> replayResp = rest.exchange(
                url("/api/v1/cases/" + caseId + "/replay"), HttpMethod.POST, new HttpEntity<>(headers), Map.class);
        assertEquals(200, replayResp.getStatusCode().value());
        int total = (Integer) replayResp.getBody().get("totalOperations");
        assertTrue(total >= 13, "expected >=13 operation executions (full suite), got " + total);
        assertEquals(total, replayResp.getBody().get("matched"), "all operations must reconstruct byte-identically");
        assertEquals(0, replayResp.getBody().get("mismatched"));

        // 8. KPIs (US5, SC-006): all three metrics emitted for this case.
        for (String metric : new String[]{"rubric_first_pass_rate", "package_completeness", "case_cost_tokens"}) {
            ResponseEntity<java.util.List> kpiResp = rest.exchange(
                    url("/api/v1/metrics/kpis?metric=" + metric), HttpMethod.GET, new HttpEntity<>(headers), java.util.List.class);
            assertEquals(200, kpiResp.getStatusCode().value());
            assertTrue(!kpiResp.getBody().isEmpty(), "expected >=1 sample for " + metric);
        }
    }

    private String pollUntilTerminal(String caseId, HttpHeaders headers, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            ResponseEntity<Map> resp = rest.exchange(
                    url("/api/v1/cases/" + caseId), HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            String status = (String) resp.getBody().get("status");
            if ("Delivered".equals(status) || "Escalated".equals(status)) {
                return status;
            }
            Thread.sleep(500);
        }
        fail("case did not reach a terminal state within " + timeout + "\n" + dumpDiagnostics(caseId));
        return null;
    }

    /** On timeout, report exactly how far the async pipeline got, incl. any silent Flowable job failures. */
    private String dumpDiagnostics(String caseId) {
        StringBuilder sb = new StringBuilder("=== PIPELINE DIAGNOSTICS ===\n");
        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute("SET app.workspace_id = '" + WORKSPACE_ID + "'");
            sb.append("case status: ").append(scalar(conn,
                    "SELECT status FROM case_instance WHERE id = '" + caseId + "'")).append('\n');
            sb.append("persona_invocation rows: ").append(scalar(conn,
                    "SELECT string_agg(sequence_no || ':' || status, ', ') FROM persona_invocation WHERE case_instance_id = '" + caseId + "'")).append('\n');
            sb.append("operation_execution count: ").append(scalar(conn,
                    "SELECT count(*) FROM operation_execution oe JOIN persona_invocation pi ON pi.id = oe.persona_invocation_id WHERE pi.case_instance_id = '" + caseId + "'")).append('\n');
            sb.append("ACT_RU_JOB exceptions: ").append(scalar(conn,
                    "SELECT string_agg(EXCEPTION_MSG_, ' | ') FROM ACT_RU_JOB")).append('\n');
            sb.append("ACT_RU_DEADLETTER_JOB exceptions: ").append(scalar(conn,
                    "SELECT string_agg(EXCEPTION_MSG_, ' | ') FROM ACT_RU_DEADLETTER_JOB")).append('\n');
            sb.append("ACT_RU_JOB count: ").append(scalar(conn, "SELECT count(*) FROM ACT_RU_JOB")).append('\n');
        } catch (Exception e) {
            sb.append("diagnostics query failed: ").append(e.getMessage());
        }
        return sb.toString();
    }

    /** Distinct artifact types (= persona keys, post-T018) in the delivered package for a case. */
    private java.util.Set<String> artifactTypesFor(String caseId) throws Exception {
        java.util.Set<String> types = new java.util.HashSet<>();
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement setCtx = conn.prepareStatement("SET app.workspace_id = '" + WORKSPACE_ID + "'")) {
                setCtx.execute();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT DISTINCT artifact_type FROM artifact WHERE case_instance_id = ?")) {
                ps.setObject(1, UUID.fromString(caseId));
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) types.add(rs.getString(1));
                }
            }
        }
        return types;
    }

    private String scalar(Connection conn, String sql) {
        try (PreparedStatement ps = conn.prepareStatement(sql); var rs = ps.executeQuery()) {
            return rs.next() ? String.valueOf(rs.getObject(1)) : "(no rows)";
        } catch (Exception e) {
            return "(query error: " + e.getMessage() + ")";
        }
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
