package com.d2os.app;

import com.d2os.app.support.StubAiGatewayClient;
import com.d2os.artifacts.ArtifactRevision;
import com.d2os.artifacts.ArtifactService;
import com.d2os.artifacts.spi.PersonaOutputPort;
import com.d2os.testsupport.ContainerFixtures;
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
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * US2 acceptance IT (T019, SC-003): running an Assessment case end to end produces a delivered
 * package with only FINDINGS + RECOMMENDATION artifact kinds, a seeded mutating write attempt is
 * refused and audited without failing the case, and the case never touches the Q2 mutating-slot
 * columns on its Feature (research R2/R3, FR-005/006/007).
 *
 * <p>Follows the {@code DeprecationIT}/{@code CaseRoutingIT} IT skeleton: Testcontainers via {@link
 * ContainerFixtures#startAll()}, a {@link StubAiGatewayClient} import so persona calls are
 * deterministic and provider-free, and direct-JDBC tenancy seeding since there is no REST endpoint for
 * Workspace/Project/Feature provisioning.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.context.annotation.Import(StubAiGatewayClient.class)
class AssessmentReadOnlyIT {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    DataSource dataSource;

    @Autowired
    ArtifactService artifactService;

    private static final UUID WORKSPACE_ID = UUID.randomUUID();
    private static UUID projectVersionId;
    private static UUID featureId;

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
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
        UUID projectId = UUID.randomUUID();
        projectVersionId = UUID.randomUUID();
        featureId = UUID.randomUUID();

        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement setCtx = conn.prepareStatement("SET app.workspace_id = '" + WORKSPACE_ID + "'")) {
                setCtx.execute();
            }
            insert(conn, "INSERT INTO workspace (id, name, created_by) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                    WORKSPACE_ID, "assessment-readonly-ws", "test");
            insert(conn, "INSERT INTO project (id, workspace_id, name, created_by) VALUES (?, ?, ?, ?)",
                    projectId, WORKSPACE_ID, "assessment-readonly-project", "test");
            insert(conn, "INSERT INTO project_version (id, workspace_id, project_id, label, created_by) VALUES (?, ?, ?, ?, ?)",
                    projectVersionId, WORKSPACE_ID, projectId, "v1", "test");
            insert(conn, "INSERT INTO feature (id, workspace_id, project_version_id, name, created_by) VALUES (?, ?, ?, ?, ?)",
                    featureId, WORKSPACE_ID, projectVersionId, "assessment-readonly-feature", "test");
        }
    }

    @Test
    void assessmentCaseDeliversReadOnlyPackageAndRefusesASeededMutatingWrite() throws Exception {
        HttpHeaders headers = headers();

        // 1. Classify + confirm as ASSESSMENT (US1 routing, same DMN inputs as CaseRoutingIT).
        String submissionId = submit(headers, Map.of(
                "subjectExists", true, "hasDeliveredBaseline", false, "requestIntent", "evaluate"));
        Map<String, Object> confirmed = confirm(headers, submissionId, "ASSESSMENT");
        assertEquals("CONFIRMED", confirmed.get("classificationStatus"));
        assertEquals("ASSESSMENT", confirmed.get("confirmedCaseType"));

        // 2. Open the Case — Assessment's catalog content (T015/T016) now exists, so this succeeds.
        ResponseEntity<Map> caseResp = createCase(headers, submissionId, featureId);
        assertEquals(201, caseResp.getStatusCode().value(), () -> "case create body: " + caseResp.getBody());
        String caseId = (String) caseResp.getBody().get("id");
        assertEquals("Planned", caseResp.getBody().get("status"));

        // Baseline check (pre-run): the Q2 guard columns are untouched at Planned — Assessment never
        // acquires the slot (T018; the guard isn't even wired into create yet, T027).
        assertFeatureSlotFree();

        // 3. Run the pipeline to completion.
        ResponseEntity<Void> startResp = rest.exchange(
                url("/api/v1/cases/" + caseId + "/start"), HttpMethod.POST, new HttpEntity<>(null, headers), Void.class);
        assertEquals(202, startResp.getStatusCode().value());

        String finalStatus = pollUntilTerminal(caseId, headers, Duration.ofSeconds(120));
        assertEquals("Delivered", finalStatus, "assessment case should reach Delivered with the stub gateway");

        // 4. SC-003: the delivered package's artifact kinds are ONLY FINDINGS + RECOMMENDATION.
        //    "Kind" is derived the same way ArtifactService.deriveArtifactKind does (T017): the
        //    recommendation persona's output is RECOMMENDATION, everything else is FINDINGS.
        Set<String> artifactTypes = artifactTypesFor(caseId);
        assertFalse(artifactTypes.isEmpty(), "assessment case must produce at least one artifact");
        Set<String> expectedPersonas = Set.of("assessment-intake", "capability-analyst", "gap-analyst",
                "risk-analyst", "assessment-findings", "assessment-recommendation");
        assertEquals(expectedPersonas, artifactTypes, "package must cover exactly the assessment persona suite");

        Set<String> kinds = new HashSet<>();
        for (String type : artifactTypes) {
            kinds.add(type.contains("recommendation") ? "RECOMMENDATION" : "FINDINGS");
        }
        assertEquals(Set.of("FINDINGS", "RECOMMENDATION"), kinds,
                "delivered package must carry only FINDINGS + RECOMMENDATION kinds (SC-003)");

        // 5. Seeded mutating-write attempt (research R2, FR-006): call the T017 choke point directly
        //    with an artifact kind OUTSIDE the allowlist, simulating a rogue/mutating persona output.
        //    It must be refused (no Artifact/ArtifactRevision row), audited, and NOT disrupt the
        //    already-Delivered case.
        UUID caseUuid = UUID.fromString(caseId);
        PersonaOutputPort.ValidatedOutput rogueOutput = new PersonaOutputPort.ValidatedOutput(
                "rogue-mutator", UUID.randomUUID(), "s3://rogue/output.txt", "deadbeef");
        Optional<ArtifactRevision> refused = withWorkspaceContext(() ->
                artifactService.createRevision(WORKSPACE_ID, caseUuid, caseUuid, rogueOutput, "SPEC_CHANGE"));
        assertTrue(refused.isEmpty(), "a write outside the read-only allowlist must be refused, not persisted");

        Long refusalAuditCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_entry WHERE workspace_id = ? AND subject_id = ? AND action = 'ARTIFACT_WRITE_REFUSED'",
                Long.class, WORKSPACE_ID, caseUuid);
        assertEquals(1L, refusalAuditCount, "the refused write must be durably audited");

        // The case is untouched by the refused attempt — still Delivered, no new artifact materialized.
        ResponseEntity<Map> postRefusalCase = rest.exchange(
                url("/api/v1/cases/" + caseId), HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        assertEquals("Delivered", postRefusalCase.getBody().get("status"), "the case must still complete");
        assertFalse(artifactTypesFor(caseId).contains("rogue-mutator"),
                "the refused output must never become a materialized artifact");

        // 6. SC-003: the Feature's Q2 guard columns are STILL untouched after the full run — Assessment
        //    never sets active_mutating_case_id, and aggregate_version never advances.
        assertFeatureSlotFree();
    }

    private void assertFeatureSlotFree() {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT active_mutating_case_id, aggregate_version FROM feature WHERE id = ?", featureId);
        assertNull(row.get("active_mutating_case_id"), "Assessment must never occupy the Q2 mutating slot");
        assertEquals(0L, ((Number) row.get("aggregate_version")).longValue(),
                "Assessment must never advance the Feature's guard version (byte-unchanged baseline)");
    }

    /** ArtifactService.createRevision needs RLS bound (same as any repository call outside a request). */
    private <T> T withWorkspaceContext(java.util.function.Supplier<T> action) {
        com.d2os.tenancy.WorkspaceContext.set(WORKSPACE_ID);
        try {
            return action.get();
        } finally {
            com.d2os.tenancy.WorkspaceContext.clear();
        }
    }

    // ---- helpers --------------------------------------------------------------------------------------

    private String submit(HttpHeaders headers, Map<String, Object> formFields) {
        Map<String, Object> formData = new java.util.LinkedHashMap<>(formFields);
        formData.put("description", "Assessment read-only integration test submission");
        Map<String, Object> body = Map.of("formData", formData);
        ResponseEntity<Map> resp = rest.exchange(
                url("/api/v1/submissions"), HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
        assertEquals(201, resp.getStatusCode().value(), () -> "submit failed: " + resp.getBody());
        String id = (String) resp.getBody().get("id");
        assertNotNull(id);
        return id;
    }

    private Map<String, Object> confirm(HttpHeaders headers, String submissionId, String caseType) {
        ResponseEntity<Map> resp = rest.exchange(
                url("/api/v1/submissions/" + submissionId + "/case-type/confirm"), HttpMethod.POST,
                new HttpEntity<>(Map.of("caseType", caseType), headers), Map.class);
        assertEquals(200, resp.getStatusCode().value(), () -> "confirm failed: " + resp.getBody());
        return resp.getBody();
    }

    private ResponseEntity<Map> createCase(HttpHeaders headers, String submissionId, UUID featureId) {
        return rest.exchange(url("/api/v1/cases"), HttpMethod.POST,
                new HttpEntity<>(Map.of("submissionId", submissionId, "featureId", featureId.toString()), headers),
                Map.class);
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
        fail("assessment case did not reach a terminal state within " + timeout);
        return null;
    }

    private Set<String> artifactTypesFor(String caseId) throws Exception {
        Set<String> types = new HashSet<>();
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

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Workspace-Id", WORKSPACE_ID.toString());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
