package com.d2os.app;

import com.d2os.app.support.StubAiGatewayClient;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * US3 acceptance IT (T026, SC-004): running an Enhancement case against a Feature with a Delivered
 * baseline produces a delta-doc + impact-analysis package, 100% of whose ArtifactRevisions carry
 * {@code DERIVES_FROM} trace links to the specific pinned baseline revisions (research R4); a
 * baseline-less Feature is rejected {@code 422} at confirm (T024); and a baseline artifact that gains a
 * newer revision after this case's pinning still resolves to its ORIGINAL pinned revision, with {@code
 * superseded=true} surfaced via {@code GET /cases/{id}/baseline} (T025).
 *
 * <p>Follows the {@code AssessmentReadOnlyIT}/{@code SubmitToDeliverIT} IT skeleton: Testcontainers via
 * {@link ContainerFixtures#startAll()}, a {@link StubAiGatewayClient} import so persona calls are
 * deterministic and provider-free, direct-JDBC tenancy seeding (no REST endpoint for Workspace/Project/
 * Feature provisioning), and the same {@code approveOpenGate} dance {@code SubmitToDeliverIT} needed
 * once the Phase 5 governance-gates spec (005) embedded a {@code review-gate} callActivity into
 * Initiation's latest published workflow ({@code initiation-v3}) — this test delivers an Initiation case
 * as its real baseline, so it must clear that same gate. Enhancement's own workflow
 * ({@code enhancement-v1}, T020) carries NO gate (this phase adds none), so the Enhancement run itself
 * never needs gate handling.
 *
 * <p><b>Cannot actually run in this environment</b> (no Docker — {@code ContainerFixtures.startAll()}
 * requires Testcontainers, consistent with every prior phase in this delivery chain). This test is
 * hand-traced against the real merged code (BaselineResolutionDelegate, ArtifactService#linkToBaseline/
 * materializeForCase, SubmissionService#requireDeliveredBaseline, CaseController#baseline) rather than
 * verified by an actual test run — flagged honestly, not claimed as passing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.context.annotation.Import(StubAiGatewayClient.class)
class EnhancementBaselineIT {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    DataSource dataSource;

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
                    WORKSPACE_ID, "enhancement-baseline-ws", "test");
            insert(conn, "INSERT INTO project (id, workspace_id, name, created_by) VALUES (?, ?, ?, ?)",
                    projectId, WORKSPACE_ID, "enhancement-baseline-project", "test");
            insert(conn, "INSERT INTO project_version (id, workspace_id, project_id, label, created_by) VALUES (?, ?, ?, ?, ?)",
                    projectVersionId, WORKSPACE_ID, projectId, "v1", "test");
            insert(conn, "INSERT INTO feature (id, workspace_id, project_version_id, name, created_by) VALUES (?, ?, ?, ?, ?)",
                    featureId, WORKSPACE_ID, projectVersionId, "enhancement-baseline-feature", "test");
        }
    }

    @Test
    void enhancementCaseDeliversDeltaAndImpactTraceLinkedToTheDeliveredBaseline() throws Exception {
        HttpHeaders headers = headers();

        // ---- 1. No-baseline subtest (T024, 422): a fresh Feature with no Delivered Case is rejected
        //         at confirm, before any Enhancement Case is ever created. ------------------------------
        UUID freshFeatureId = newFeature();
        String noBaselineSubmissionId = submit(headers, Map.of(
                "subjectExists", true, "hasDeliveredBaseline", true, "requestIntent", "change",
                "featureId", freshFeatureId.toString()));
        ResponseEntity<String> rejected = rest.exchange(
                url("/api/v1/submissions/" + noBaselineSubmissionId + "/case-type/confirm"), HttpMethod.POST,
                new HttpEntity<>(Map.of("caseType", "ENHANCEMENT"), headers), String.class);
        assertEquals(422, rejected.getStatusCode().value(),
                "an ENHANCEMENT confirm against a Feature with no Delivered Case must be rejected 422");

        // ---- 2. Deliver a real Initiation baseline Case on the REAL Feature. --------------------------
        String baselineCaseId = deliverInitiationBaseline(headers);

        // ---- 3. Real Enhancement case against that Delivered baseline. --------------------------------
        String submissionId = submit(headers, Map.of(
                "subjectExists", true, "hasDeliveredBaseline", true, "requestIntent", "change",
                "featureId", featureId.toString()));
        Map<String, Object> confirmed = confirm(headers, submissionId, "ENHANCEMENT");
        assertEquals("CONFIRMED", confirmed.get("classificationStatus"));
        assertEquals("ENHANCEMENT", confirmed.get("confirmedCaseType"));

        ResponseEntity<Map> caseResp = createCase(headers, submissionId, featureId);
        assertEquals(201, caseResp.getStatusCode().value(), () -> "case create body: " + caseResp.getBody());
        String caseId = (String) caseResp.getBody().get("id");
        assertEquals("Planned", caseResp.getBody().get("status"));

        ResponseEntity<Void> startResp = rest.exchange(
                url("/api/v1/cases/" + caseId + "/start"), HttpMethod.POST, new HttpEntity<>(null, headers), Void.class);
        assertEquals(202, startResp.getStatusCode().value());

        // enhancement-v1 (T020) carries no review-gate — unlike initiation-v3/assessment-v2, this run
        // goes straight from start to Delivered with the stub gateway.
        String finalStatus = pollUntilTerminal(caseId, headers, Duration.ofSeconds(120));
        assertEquals("Delivered", finalStatus, "enhancement case should reach Delivered with the stub gateway");

        // ---- 4. GET /baseline lists the pinned baseline revisions (T025). -----------------------------
        ResponseEntity<Map> baselineResp = rest.exchange(
                url("/api/v1/cases/" + caseId + "/baseline"), HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        assertEquals(200, baselineResp.getStatusCode().value(), () -> "baseline body: " + baselineResp.getBody());
        assertEquals(baselineCaseId, baselineResp.getBody().get("baselineCaseId"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entriesBeforeSupersede =
                (List<Map<String, Object>>) baselineResp.getBody().get("entries");
        assertFalse(entriesBeforeSupersede.isEmpty(), "baseline must carry at least one pinned revision");
        for (Map<String, Object> entry : entriesBeforeSupersede) {
            assertEquals(Boolean.FALSE, entry.get("superseded"), "no baseline artifact has a newer revision yet");
            assertEquals(Boolean.FALSE, entry.get("deprecated"), "deprecated is always false (documented placeholder)");
        }

        // ---- 5. SC-004: the delivered package is exactly the delta/impact persona roster. -------------
        Set<String> artifactTypes = artifactTypesFor(caseId);
        Set<String> expectedPersonas = Set.of(
                "requirements-delta-analyst", "design-delta-analyst", "delta-doc", "impact-analysis");
        assertEquals(expectedPersonas, artifactTypes,
                "package must cover exactly the enhancement delta/impact persona suite");

        // ---- 6. 100% of this case's ArtifactRevisions carry a DERIVES_FROM trace_link (T023/SC-004). --
        Long revisionsWithoutLink = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM artifact_revision ar "
                        + "JOIN artifact a ON a.id = ar.artifact_id "
                        + "WHERE a.case_instance_id = ? "
                        + "AND NOT EXISTS (SELECT 1 FROM trace_link tl "
                        + "WHERE tl.from_type = 'artifact_revision' AND tl.from_id = ar.id "
                        + "AND tl.link_type = 'DERIVES_FROM')",
                Long.class, UUID.fromString(caseId));
        assertEquals(0L, revisionsWithoutLink, "every delta/impact revision must carry >=1 DERIVES_FROM link");

        Long derivesFromCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM trace_link tl "
                        + "JOIN artifact_revision ar ON ar.id = tl.from_id AND tl.from_type = 'artifact_revision' "
                        + "JOIN artifact a ON a.id = ar.artifact_id "
                        + "WHERE a.case_instance_id = ? AND tl.link_type = 'DERIVES_FROM'",
                Long.class, UUID.fromString(caseId));
        assertTrue(derivesFromCount >= entriesBeforeSupersede.size(),
                "each of this case's revisions fans out a DERIVES_FROM edge to every baseline revision");

        // ---- 7. Superseded subtest: bump one baseline artifact to a newer revision (simulating the
        //         baseline Feature changing again after this Enhancement case pinned its baseline), then
        //         confirm the pinned reference is UNCHANGED while superseded=true is now surfaced. -------
        UUID supersededArtifactId = UUID.fromString((String) entriesBeforeSupersede.get(0).get("artifactId"));
        UUID pinnedRevisionId = UUID.fromString((String) entriesBeforeSupersede.get(0).get("revisionId"));
        int pinnedRevisionNo = (Integer) entriesBeforeSupersede.get(0).get("revisionNo");
        insertNewerRevision(supersededArtifactId, pinnedRevisionNo + 1);

        ResponseEntity<Map> baselineResp2 = rest.exchange(
                url("/api/v1/cases/" + caseId + "/baseline"), HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        assertEquals(200, baselineResp2.getStatusCode().value());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entriesAfterSupersede =
                (List<Map<String, Object>>) baselineResp2.getBody().get("entries");
        Map<String, Object> supersededEntry = entriesAfterSupersede.stream()
                .filter(e -> supersededArtifactId.toString().equals(e.get("artifactId")))
                .findFirst().orElseThrow(() -> new AssertionError("superseded artifact missing from baseline"));
        assertEquals(pinnedRevisionId.toString(), supersededEntry.get("revisionId"),
                "the pinned revision reference itself must never change");
        assertEquals(pinnedRevisionNo, supersededEntry.get("revisionNo"),
                "the pinned revision number itself must never change");
        assertEquals(Boolean.TRUE, supersededEntry.get("superseded"),
                "a newer revision now exists on this baseline artifact — superseded must flip to true");
    }

    // ---- helpers --------------------------------------------------------------------------------------

    /**
     * Real submit -> confirm-classification -> create -> start -> (gate approve) -> Delivered flow for
     * an Initiation case on {@code featureId}, so there is a genuine Delivered Case with real
     * ArtifactRevisions for BaselineResolutionDelegate to anchor to. Mirrors {@code SubmitToDeliverIT}.
     */
    private String deliverInitiationBaseline(HttpHeaders headers) throws InterruptedException {
        Map<String, Object> submissionBody = Map.of(
                "formData", Map.of("category", "initiation", "description", "Enhancement baseline IT seed"));
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
        assertEquals(201, caseResp.getStatusCode().value(), () -> "baseline case create body: " + caseResp.getBody());
        String caseId = (String) caseResp.getBody().get("id");

        ResponseEntity<Void> startResp = rest.exchange(
                url("/api/v1/cases/" + caseId + "/start"), HttpMethod.POST, new HttpEntity<>(null, headers), Void.class);
        assertEquals(202, startResp.getStatusCode().value());

        // initiation's latest published case_type is initiation-v3 (Phase 5 governance-gates spec, 005),
        // gate-embedded before assemble-package — same mechanism fix SubmitToDeliverIT/AssessmentReadOnlyIT
        // already need.
        approveOpenGate(caseId, headers, Duration.ofSeconds(120));

        String finalStatus = pollUntilTerminal(caseId, headers, Duration.ofSeconds(120));
        assertEquals("Delivered", finalStatus, "baseline initiation case must reach Delivered");
        return caseId;
    }

    private String submit(HttpHeaders headers, Map<String, Object> formFields) {
        Map<String, Object> formData = new java.util.LinkedHashMap<>(formFields);
        formData.put("description", "Enhancement baseline integration test submission");
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
        fail("case " + caseId + " did not reach a terminal state within " + timeout);
        return null;
    }

    private void approveOpenGate(String caseId, HttpHeaders headers, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            List<Map<String, Object>> openGates = listOpenGates(caseId, headers);
            if (!openGates.isEmpty()) {
                String gateId = (String) openGates.get(0).get("id");
                decide(headersWithActor("reviewer-1"), gateId, "APPROVE");
                return;
            }
            Thread.sleep(500);
        }
        fail("review-gate did not open for baseline case " + caseId + " within " + timeout);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOpenGates(String caseId, HttpHeaders headers) {
        ResponseEntity<List> resp = rest.exchange(
                url("/api/v1/gates?caseId=" + caseId), HttpMethod.GET, new HttpEntity<>(headers), List.class);
        assertEquals(200, resp.getStatusCode().value());
        List<Map<String, Object>> gates = (List<Map<String, Object>>) (List<?>) resp.getBody();
        List<Map<String, Object>> open = new java.util.ArrayList<>();
        for (Map<String, Object> gate : gates) {
            if ("OPEN".equals(gate.get("status"))) {
                open.add(gate);
            }
        }
        return open;
    }

    private void decide(HttpHeaders headers, String gateId, String verb) {
        ResponseEntity<Map> resp = rest.exchange(
                url("/api/v1/gates/" + gateId + "/decision"), HttpMethod.POST,
                new HttpEntity<>(Map.of("verb", verb), headers), Map.class);
        assertEquals(200, resp.getStatusCode().value(), () -> "gate decision failed: " + resp.getBody());
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

    /** Insert a newer revision on {@code artifactId} directly (T026 superseded subtest). */
    private void insertNewerRevision(UUID artifactId, int newRevisionNo) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement setCtx = conn.prepareStatement("SET app.workspace_id = '" + WORKSPACE_ID + "'")) {
                setCtx.execute();
            }
            insert(conn, "INSERT INTO artifact_revision (id, workspace_id, artifact_id, revision_no, "
                            + "storage_ref, content_hash) VALUES (?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(), WORKSPACE_ID, artifactId, Integer.valueOf(newRevisionNo),
                    "s3://test/superseded-" + newRevisionNo, "deadbeef" + newRevisionNo);
        }
    }

    private void insert(Connection conn, String sql, Object... params) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                Object p = params[i];
                if (p instanceof UUID uuid) ps.setObject(i + 1, uuid);
                else if (p instanceof Integer n) ps.setInt(i + 1, n);
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

    private HttpHeaders headersWithActor(String actor) {
        HttpHeaders headers = headers();
        headers.set("X-Actor", actor);
        return headers;
    }

    /** Fresh Feature for the no-baseline subtest, isolated from the class-level `featureId`. */
    private UUID newFeature() {
        UUID id = UUID.randomUUID();
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement setCtx = conn.prepareStatement("SET app.workspace_id = '" + WORKSPACE_ID + "'")) {
                setCtx.execute();
            }
            insert(conn, "INSERT INTO feature (id, workspace_id, project_version_id, name, created_by) VALUES (?, ?, ?, ?, ?)",
                    id, WORKSPACE_ID, projectVersionId, "f-" + id, "test");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return id;
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
