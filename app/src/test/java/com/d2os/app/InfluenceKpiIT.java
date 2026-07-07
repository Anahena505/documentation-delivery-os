package com.d2os.app;

import com.d2os.app.support.StubAiGatewayClient;
import com.d2os.knowledge.EmbeddingIndexer;
import com.d2os.knowledge.KnowledgeScope;
import com.d2os.testsupport.ContainerFixtures;
import com.d2os.tenancy.WorkspaceContext;
import com.d2os.tenancy.WorkspaceProvisioningService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import javax.sql.DataSource;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Knowledge-influence KPI IT (T035, US4, SC-008). Opens a real case (Planned, with a pinned definition
 * snapshot) so the influence evaluation can resolve {@code solution-architect}'s pinned prompt+rubric,
 * publishes two governed items, then asserts:
 * <ul>
 *   <li>a paired influence evaluation records exactly TWO {@code evaluation=true} operation_executions
 *       (with/without) under one rubric version, so neither feeds delivery (FR-017);</li>
 *   <li>a {@code kpi_sample(metric='knowledge_influence')} is emitted for the evaluated item, and
 *       {@code GET /metrics/knowledge-influence} returns {@code MEASURED} with the same delta (FR-018);</li>
 *   <li>an item that was never evaluated returns {@code NOT_YET_MEASURABLE} with NO sample and no
 *       fabricated value (SC-008).</li>
 * </ul>
 *
 * <p>The stub gateway returns a fixed output regardless of the injected knowledge block, so the measured
 * delta here is genuinely {@code 0.0} — which is the point: the KPI reports the MEASURED value (0.0),
 * never a fabricated one. A nonzero influence would require a prompt-sensitive model; the semantics under
 * test are the paired-run mechanics and the MEASURED / NOT_YET_MEASURABLE distinction.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StubAiGatewayClient.class)
class InfluenceKpiIT {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired WorkspaceProvisioningService workspaceProvisioningService;
    @Autowired EmbeddingIndexer embeddingIndexer;

    private static final UUID WORKSPACE_ID = UUID.randomUUID();
    private UUID featureId;

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
        registry.add("d2os.storage.endpoint", () -> ContainerFixtures.MINIO.getS3URL());
        registry.add("d2os.storage.access-key", ContainerFixtures.MINIO::getUserName);
        registry.add("d2os.storage.secret-key", ContainerFixtures.MINIO::getPassword);
        registry.add("d2os.storage.bucket", () -> "d2os-artifacts");
    }

    @BeforeAll
    static void bucket() {
        ContainerFixtures.startAll();
        S3Client s3 = S3Client.builder()
                .endpointOverride(URI.create(ContainerFixtures.MINIO.getS3URL()))
                .region(Region.US_EAST_1)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        ContainerFixtures.MINIO.getUserName(), ContainerFixtures.MINIO.getPassword())))
                .build();
        try {
            s3.createBucket(CreateBucketRequest.builder().bucket("d2os-artifacts").build());
        } catch (Exception ignored) { /* bucket may already exist across suites */ }
    }

    @BeforeEach
    void seedTenancy() throws Exception {
        featureId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();

        // provisionWorkspace creates the workspace row AND its knowledge_item partition (so publish works).
        WorkspaceContext.set(WORKSPACE_ID);
        workspaceProvisioningService.provisionWorkspace(WORKSPACE_ID, "influence-it-ws", "test");

        try (Connection c = dataSource.getConnection()) {
            exec(c, "SET app.workspace_id = '" + WORKSPACE_ID + "'");
            ins(c, "INSERT INTO project (id,workspace_id,name,created_by) VALUES (?,?,?,?)",
                    projectId, WORKSPACE_ID, "p", "t");
            ins(c, "INSERT INTO project_version (id,workspace_id,project_id,label,created_by) VALUES (?,?,?,?,?)",
                    versionId, WORKSPACE_ID, projectId, "v1", "t");
            ins(c, "INSERT INTO feature (id,workspace_id,project_version_id,name,created_by) VALUES (?,?,?,?,?)",
                    featureId, WORKSPACE_ID, versionId, "f", "t");
        }
    }

    @AfterEach
    void clearContext() {
        WorkspaceContext.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void pairedEvaluationEmitsMeasuredDeltaAndNeverInjectedIsNotYetMeasurable() {
        // Two governed items: one to evaluate, one that is never evaluated.
        WorkspaceContext.set(WORKSPACE_ID);
        UUID evaluatedId = embeddingIndexer.publish(
                WORKSPACE_ID, UUID.randomUUID(), "influence-item", 1, KnowledgeScope.WORKSPACE, WORKSPACE_ID,
                List.of("architecture"), "en", "Influence item", "Guidance to measure the influence of.",
                null, null);
        embeddingIndexer.publish(
                WORKSPACE_ID, UUID.randomUUID(), "never-used-item", 1, KnowledgeScope.WORKSPACE, WORKSPACE_ID,
                List.of("architecture"), "en", "Never used", "Never evaluated, so not yet measurable.",
                null, null);

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Workspace-Id", WORKSPACE_ID.toString());
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Open a case (Planned) so it carries a pinned definition snapshot to resolve the persona/rubric.
        String caseId = openCase(headers);

        // Trigger the paired influence evaluation against solution-architect's pinned rubric.
        ResponseEntity<Map> evalResp = rest.exchange(
                url("/api/v1/knowledge/items/" + evaluatedId + "/influence-evaluations"), HttpMethod.POST,
                new HttpEntity<>(Map.of("caseId", caseId, "personaKey", "solution-architect"), headers), Map.class);
        assertEquals(202, evalResp.getStatusCode().value(), () -> "eval body: " + evalResp.getBody());
        assertNotNull(evalResp.getBody().get("delta"), "the evaluation must return a measured delta");
        double delta = ((Number) evalResp.getBody().get("delta")).doubleValue();

        // Exactly two evaluation-flagged operation_executions were recorded (with + without).
        WorkspaceContext.set(WORKSPACE_ID);
        Long evalRuns = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM operation_execution oe JOIN persona_invocation pi "
                        + "ON pi.id = oe.persona_invocation_id WHERE pi.case_instance_id = ? AND oe.evaluation = true",
                Long.class, UUID.fromString(caseId));
        assertEquals(2L, evalRuns, "paired evaluation records exactly two evaluation=true runs");

        // The influence KPI is MEASURED for the evaluated item, with the same delta the POST returned.
        ResponseEntity<Map> measured = rest.exchange(
                url("/api/v1/metrics/knowledge-influence?itemId=" + evaluatedId), HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);
        assertEquals(200, measured.getStatusCode().value());
        assertEquals("MEASURED", measured.getBody().get("status"));
        assertNotNull(measured.getBody().get("latestDelta"));
        assertEquals(delta, ((Number) measured.getBody().get("latestDelta")).doubleValue(), 1e-9,
                "the reported KPI equals the measured with-minus-without delta");
        assertFalse(((List<?>) measured.getBody().get("samples")).isEmpty(), "MEASURED carries the sample series");

        // Exactly one knowledge_influence sample exists for the evaluated (key,version).
        Long evaluatedSamples = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM kpi_sample WHERE metric = 'knowledge_influence' "
                        + "AND dimensions->>'key' = 'influence-item' AND dimensions->>'version' = '1'", Long.class);
        assertEquals(1L, evaluatedSamples, "one influence sample was emitted for the evaluated item");

        // The never-evaluated item is NOT_YET_MEASURABLE — no value fabricated, no sample.
        ResponseEntity<Map> notMeasured = rest.exchange(
                url("/api/v1/metrics/knowledge-influence?key=never-used-item&version=1"), HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);
        assertEquals(200, notMeasured.getStatusCode().value());
        assertEquals("NOT_YET_MEASURABLE", notMeasured.getBody().get("status"));
        assertNull(notMeasured.getBody().get("latestDelta"), "a never-injected item has no fabricated value");

        Long neverSamples = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM kpi_sample WHERE metric = 'knowledge_influence' "
                        + "AND dimensions->>'key' = 'never-used-item'", Long.class);
        assertEquals(0L, neverSamples, "no sample exists for an item that was never evaluated");
    }

    /** Submit → confirm classification → open case; returns the Planned case id (snapshot pinned). */
    @SuppressWarnings("unchecked")
    private String openCase(HttpHeaders headers) {
        Map<String, Object> submissionBody = Map.of(
                "formData", Map.of("category", "initiation", "description", "Influence KPI IT submission"));
        ResponseEntity<Map> submissionResp = rest.exchange(
                url("/api/v1/submissions"), HttpMethod.POST, new HttpEntity<>(submissionBody, headers), Map.class);
        assertEquals(201, submissionResp.getStatusCode().value());
        String submissionId = (String) submissionResp.getBody().get("id");

        ResponseEntity<Map> confirmResp = rest.exchange(
                url("/api/v1/submissions/" + submissionId + "/confirm-classification"), HttpMethod.POST,
                new HttpEntity<>(Map.of("confirmedCaseType", "initiation"), headers), Map.class);
        assertEquals(200, confirmResp.getStatusCode().value());

        ResponseEntity<Map> caseResp = rest.exchange(
                url("/api/v1/cases"), HttpMethod.POST,
                new HttpEntity<>(Map.of("submissionId", submissionId, "featureId", featureId.toString()), headers),
                Map.class);
        assertEquals(201, caseResp.getStatusCode().value(), () -> "case body: " + caseResp.getBody());
        assertNotNull(caseResp.getBody().get("definitionSnapshot"), "the case must pin a definition snapshot");
        return (String) caseResp.getBody().get("id");
    }

    private static void assertFalse(boolean condition, String message) {
        assertTrue(!condition, message);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private void exec(Connection c, String sql) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) { ps.execute(); }
    }

    private void ins(Connection c, String sql, Object... params) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                Object p = params[i];
                if (p instanceof UUID u) ps.setObject(i + 1, u);
                else ps.setString(i + 1, p.toString());
            }
            ps.executeUpdate();
        }
    }
}
