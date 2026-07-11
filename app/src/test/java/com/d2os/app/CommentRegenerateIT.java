package com.d2os.app;

import com.d2os.app.support.StubAiGatewayClient;
import com.d2os.testsupport.ContainerFixtures;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * US2 acceptance IT (T023, SC-002): REQUEST_CHANGES on a gate re-enters the persona path
 * ({@code RegenerationDelegate}, T019) and produces a new immutable ArtifactRevision — never an
 * in-place edit of the one under review — plus a deterministic delta report (T020), re-presented at a
 * fresh gate cycle (T021's {@code GET /gates/{id}/delta-report}).
 *
 * <p>Follows {@code GateFlowIT}'s exact IT skeleton (Testcontainers via {@link
 * ContainerFixtures#startAll()}, {@link StubAiGatewayClient} import, direct-JDBC tenancy seeding) and
 * drives the same {@code assessment-v2} workflow to its embedded {@code review-gate}. <b>Cannot
 * actually run in this environment</b> (no Docker) for the three container-backed tests below — traced
 * by hand against the real {@code RegenerationDelegate}/{@code GateTaskBridge}/{@code
 * ArtifactService}/{@code DeltaReportService}/{@code GateController} code and the
 * {@code assessment-v2.bpmn20.xml} gateway wiring authored alongside it, not asserted to pass. The
 * fourth test ({@link #noArtifactContentWritePathExistsOutsideCreateRevision()}) is pure bytecode
 * reflection over the compiled classpath (ArchUnit's {@code ClassFileImporter}) — it needs no Spring
 * context, no database, no Docker, and genuinely could run standalone; it is bundled into this
 * {@code @SpringBootTest} class only because T023 names it as part of {@code CommentRegenerateIT}, so
 * in THIS environment it is still gated by the class's shared (Docker-requiring) Spring context like
 * every other method here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.context.annotation.Import(StubAiGatewayClient.class)
class CommentRegenerateIT {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    DataSource dataSource;

    @Autowired
    StubAiGatewayClient.LatencyControllableGateway gateway;

    private static final UUID WORKSPACE_ID = UUID.randomUUID();
    private static UUID projectVersionId;

    private static final String REVIEWER_COMMENTS =
            "please strengthen the risk register section with a mitigation owner per risk";
    private static final String REGENERATED_BODY =
            "This is the REGENERATED deterministic stub artifact for CommentRegenerateIT. ".repeat(6);

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
        gateway.reset();
        // The reviewer's exact comment text is also the response marker (T1-a delimited framing means
        // PromptRenderer echoes it verbatim inside "[BEGIN REVIEWER COMMENTS...]" — present ONLY in the
        // regenerated call's rendered prompt, never the original), so the stub deterministically returns
        // different content on regeneration without needing to inspect call order.
        gateway.respondWith(REVIEWER_COMMENTS, REGENERATED_BODY);

        UUID projectId = UUID.randomUUID();
        projectVersionId = UUID.randomUUID();

        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement setCtx = conn.prepareStatement("SET app.workspace_id = '" + WORKSPACE_ID + "'")) {
                setCtx.execute();
            }
            insert(conn, "INSERT INTO workspace (id, name, created_by) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                    WORKSPACE_ID, "comment-regenerate-ws", "test");
            insert(conn, "INSERT INTO project (id, workspace_id, name, created_by) VALUES (?, ?, ?, ?)",
                    projectId, WORKSPACE_ID, "comment-regenerate-project", "test");
            insert(conn, "INSERT INTO project_version (id, workspace_id, project_id, label, created_by) VALUES (?, ?, ?, ?, ?)",
                    projectVersionId, WORKSPACE_ID, projectId, "v1", "test");
        }
    }

    // ---- 1. REQUEST_CHANGES -> new ArtifactRevision, original byte-unchanged, both in history --------

    @Test
    void requestChangesProducesANewRevisionAndRetainsTheOriginal() throws Exception {
        HttpHeaders headers = headers();
        String caseId = openCaseToGate(headers);
        String firstGateId = listGates(headers, caseId).get(0).get("id").toString();

        // The original revision materialized ahead of review (GateTaskBridge's T019 auto-derivation) —
        // resolve it via the gate detail's subjectArtifactRevisionId (populated even though neither
        // initiation-v3 nor assessment-v2 maps it explicitly, T019's GateTaskBridge change).
        Map<String, Object> firstGateDetail = getGate(headers, firstGateId);
        String originalRevisionId = (String) firstGateDetail.get("subjectArtifactRevisionId");
        assertNotNull(originalRevisionId, "GateTaskBridge must resolve a subject artifact revision at gate-open");

        Map<String, Object> originalRevisionRow = jdbcTemplate.queryForMap(
                "SELECT artifact_id, storage_ref, content_hash, revision_no FROM artifact_revision WHERE id = ?",
                UUID.fromString(originalRevisionId));
        String artifactId = originalRevisionRow.get("artifact_id").toString();
        String originalContentHash = (String) originalRevisionRow.get("content_hash");
        assertEquals(1, ((Number) originalRevisionRow.get("revision_no")).intValue());

        // REQUEST_CHANGES — transitions the gate to REGENERATING and stores reviewerComments (T014,
        // already covered by GateFlowIT); this test's focus is what happens AFTER: RegenerationDelegate
        // re-enters the persona path, ArtifactService.createRevision appends revision 2 to the SAME
        // artifact, DeltaReportService diffs 1 vs 2, and the loop re-arrives at a NEW OPEN gate.
        Map<String, Object> decided = decide(headers, firstGateId, "reviewer-5", "REQUEST_CHANGES", REVIEWER_COMMENTS);
        assertEquals("REGENERATING", decided.get("status"));

        String secondGateId = pollUntilRegeneratedGateOpens(caseId, headers, Duration.ofSeconds(120));
        assertNotEquals(firstGateId, secondGateId, "regeneration re-arrives at a NEW gate cycle, not the old row");

        // The SAME Artifact now has exactly two revisions: rev 1 (original, byte-unchanged) + rev 2
        // (regenerated) — never an in-place edit of rev 1's row.
        List<Map<String, Object>> revisions = jdbcTemplate.queryForList(
                "SELECT id, revision_no, content_hash FROM artifact_revision WHERE artifact_id = ? ORDER BY revision_no ASC",
                UUID.fromString(artifactId));
        assertEquals(2, revisions.size(), "both the original and the regenerated content must be in history");
        assertEquals(1, ((Number) revisions.get(0).get("revision_no")).intValue());
        assertEquals(originalContentHash, revisions.get(0).get("content_hash"),
                "the original revision's content_hash is untouched — it was never mutated");
        assertEquals(2, ((Number) revisions.get(1).get("revision_no")).intValue());
        assertNotEquals(originalContentHash, revisions.get(1).get("content_hash"),
                "the regenerated revision must carry genuinely different content");

        String newRevisionId = revisions.get(1).get("id").toString();

        // The new gate's subjectArtifactRevisionId points at the regenerated revision (T019's
        // GateTaskBridge re-resolves it on every open, and the idempotent-by-hash guard in
        // ArtifactService means it picks up rev 2, not a spurious rev 3).
        Map<String, Object> secondGateDetail = getGate(headers, secondGateId);
        assertEquals(newRevisionId, secondGateDetail.get("subjectArtifactRevisionId"));

        // The delta report is attached to the NEW gate cycle (T020's "set the resulting delta_report.id
        // onto the new GateInstance.deltaReportId field"), not the old REGENERATING row.
        String deltaReportId = (String) secondGateDetail.get("deltaReportId");
        assertNotNull(deltaReportId, "the new gate cycle must carry the delta report id");

        Map<String, Object> deltaRow = jdbcTemplate.queryForMap(
                "SELECT artifact_id, from_revision_id, to_revision_id, diff_content, diff_hash FROM delta_report WHERE id = ?",
                UUID.fromString(deltaReportId));
        assertEquals(artifactId, deltaRow.get("artifact_id").toString());
        assertEquals(originalRevisionId, deltaRow.get("from_revision_id").toString());
        assertEquals(newRevisionId, deltaRow.get("to_revision_id").toString());
        assertNotNull(deltaRow.get("diff_hash"), "diff_hash must be present (T020, SHA-256 over diff_content)");
        assertFalse(((String) deltaRow.get("diff_hash")).isBlank());
        assertFalse(((String) deltaRow.get("diff_content")).isBlank(), "genuinely different content must diff non-empty");

        // GATE_REGENERATION_TRIGGERED (T022) carries the produced revision id, matching contracts/
        // api.yaml's GateEventPayload.producedArtifactRevisionId.
        Map<String, Object> event = jdbcTemplate.queryForMap(
                "SELECT payload FROM event_outbox WHERE aggregate_id = ? AND event_type = 'GATE_REGENERATION_TRIGGERED'",
                UUID.fromString(secondGateId));
        String payload = event.get("payload").toString();
        assertTrue(payload.contains("\"producedArtifactRevisionId\":\"" + newRevisionId + "\""),
                "GATE_REGENERATION_TRIGGERED must carry the produced revision id");
    }

    // ---- 2. GET /gates/{id}/delta-report -----------------------------------------------------------

    @Test
    void deltaReportEndpointReturnsTheReportAnd404sBeforeAnyRegeneration() throws Exception {
        HttpHeaders headers = headers();
        String caseId = openCaseToGate(headers);
        String firstGateId = listGates(headers, caseId).get(0).get("id").toString();

        // Before any REQUEST_CHANGES, the gate has no delta report yet — 404 (T021).
        ResponseEntity<Map> before = rest.exchange(
                url("/api/v1/gates/" + firstGateId + "/delta-report"), HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        assertEquals(404, before.getStatusCode().value(), "no regeneration has happened yet");

        decide(headers, firstGateId, "reviewer-6", "REQUEST_CHANGES", REVIEWER_COMMENTS);
        String secondGateId = pollUntilRegeneratedGateOpens(caseId, headers, Duration.ofSeconds(120));

        ResponseEntity<Map> after = rest.exchange(
                url("/api/v1/gates/" + secondGateId + "/delta-report"), HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        assertEquals(200, after.getStatusCode().value());
        assertNotNull(after.getBody().get("diffHash"));
        assertNotNull(after.getBody().get("diffContent"));
        assertNotEquals(firstGateId, secondGateId);

        // A gate id that was never opened at all still 404s (not a 500/NPE).
        ResponseEntity<Map> missing = rest.exchange(
                url("/api/v1/gates/" + UUID.randomUUID() + "/delta-report"), HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        assertEquals(404, missing.getStatusCode().value());
    }

    // ---- 3. API-surface scan: no artifact-content write path exists outside ArtifactService.createRevision

    /**
     * T023's "API-surface scan" (SC-002, FR-004): pure bytecode reflection (ArchUnit's {@code
     * ClassFileImporter}, no Spring context / DB / Docker needed) over every {@code @RestController} in
     * the {@code artifacts} module (the module that owns {@code artifact}/{@code artifact_revision}).
     * The only controller there today is {@code PackageController} (read + verify, GET/POST-verify), so
     * this is a real, meaningful assertion, not a vacuous one: it fails the moment anyone adds a PUT/
     * PATCH endpoint (the REST verbs that conventionally mean "replace/modify this resource's content
     * in place") or a POST endpoint whose name suggests it accepts raw content, to ANY controller in
     * {@code com.d2os.artifacts}. Interpretation note (per the task's own latitude to choose the most
     * meaningful, actually-executable check): a full "every write anywhere in the app module" scan would
     * also need to rule out JDBC/JPA writes reachable from non-controller code, which ArchUnit can do
     * too (a rule restricting who may call {@code ArtifactService.createRevision} or save an {@code
     * ArtifactRevision}) — deliberately NOT attempted here because {@code ArtifactService.createRevision}
     * is ALREADY the single choke point by construction (T017, spec 004) and by this phase's own design
     * (T019/T020 route both the gate-open auto-materialization and the regeneration path through it);
     * the highest-value, lowest-false-positive check is therefore "no OTHER entry point (a REST
     * endpoint) exists that could bypass it" — exactly what this scan asserts.
     */
    @Test
    void noArtifactContentWritePathExistsOutsideCreateRevision() {
        JavaClasses artifactsClasses = new ClassFileImporter().importPackages("com.d2os.artifacts");

        List<String> violations = new ArrayList<>();
        for (JavaClass clazz : artifactsClasses) {
            if (!clazz.isAnnotatedWith(RestController.class)) {
                continue;
            }
            for (JavaMethod method : clazz.getMethods()) {
                if (method.isAnnotatedWith(PutMapping.class) || method.isAnnotatedWith(PatchMapping.class)) {
                    violations.add(clazz.getSimpleName() + "#" + method.getName()
                            + " exposes a PUT/PATCH endpoint — artifact content must only ever be "
                            + "written via ArtifactService.createRevision (a new revision), never replaced in place");
                }
                if (method.isAnnotatedWith(PostMapping.class) && method.getName().toLowerCase().contains("content")) {
                    violations.add(clazz.getSimpleName() + "#" + method.getName()
                            + " is a POST endpoint whose name suggests it accepts raw artifact content directly");
                }
            }
        }

        assertTrue(violations.isEmpty(), () -> "artifact-content write path(s) found outside "
                + "ArtifactService.createRevision: " + violations);

        // Belt-and-suspenders: assert the artifacts module's REST surface is exactly what we expect
        // today (PackageController only) — a NEW controller class showing up here is itself a signal
        // worth re-reviewing this scan for, even if it happens to pass the PUT/PATCH check above.
        List<String> restControllers = new ArrayList<>();
        for (JavaClass clazz : artifactsClasses) {
            if (clazz.isAnnotatedWith(RestController.class)) {
                restControllers.add(clazz.getSimpleName());
            }
        }
        assertEquals(List.of("PackageController"), restControllers,
                "a new artifacts REST controller appeared — re-verify it has no content write path");
    }

    // ---- helpers --------------------------------------------------------------------------------------

    /** Same as {@code GateFlowIT}'s helper: classify + confirm as ASSESSMENT, run to the first gate. */
    private String openCaseToGate(HttpHeaders headers) throws Exception {
        UUID featureId = UUID.randomUUID();
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement setCtx = conn.prepareStatement("SET app.workspace_id = '" + WORKSPACE_ID + "'")) {
                setCtx.execute();
            }
            insert(conn, "INSERT INTO feature (id, workspace_id, project_version_id, name, created_by) VALUES (?, ?, ?, ?, ?)",
                    featureId, WORKSPACE_ID, projectVersionId, "f-" + featureId, "test");
        }

        String submissionId = submit(headers, Map.of(
                "subjectExists", true, "hasDeliveredBaseline", false, "requestIntent", "evaluate"));
        confirm(headers, submissionId, "ASSESSMENT");

        ResponseEntity<Map> caseResp = createCase(headers, submissionId, featureId);
        assertEquals(201, caseResp.getStatusCode().value(), () -> "case create body: " + caseResp.getBody());
        String caseId = (String) caseResp.getBody().get("id");

        ResponseEntity<Void> startResp = rest.exchange(
                url("/api/v1/cases/" + caseId + "/start"), HttpMethod.POST, new HttpEntity<>(null, headers), Void.class);
        assertEquals(202, startResp.getStatusCode().value());

        pollUntilGateOpens(caseId, headers, Duration.ofSeconds(120));
        return caseId;
    }

    private void pollUntilGateOpens(String caseId, HttpHeaders headers, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (!listGates(headers, caseId).isEmpty()) {
                return;
            }
            Thread.sleep(500);
        }
        fail("review-gate did not open for case " + caseId + " within " + timeout);
    }

    /** Waits for a SECOND gate (the regeneration loop-back's fresh cycle) to appear OPEN for the case. */
    private String pollUntilRegeneratedGateOpens(String caseId, HttpHeaders headers, Duration timeout)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            List<Map<String, Object>> gates = listGates(headers, caseId);
            if (gates.size() >= 2) {
                for (Map<String, Object> g : gates) {
                    if ("OPEN".equals(g.get("status"))) {
                        return (String) g.get("id");
                    }
                }
            }
            Thread.sleep(500);
        }
        fail("a regenerated gate cycle did not open for case " + caseId + " within " + timeout);
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listGates(HttpHeaders headers, String caseId) {
        ResponseEntity<List> resp = rest.exchange(
                url("/api/v1/gates?caseId=" + caseId), HttpMethod.GET, new HttpEntity<>(headers), List.class);
        assertEquals(200, resp.getStatusCode().value());
        return (List<Map<String, Object>>) (List<?>) resp.getBody();
    }

    private Map<String, Object> getGate(HttpHeaders headers, String gateId) {
        ResponseEntity<Map> resp = rest.exchange(
                url("/api/v1/gates/" + gateId), HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        assertEquals(200, resp.getStatusCode().value());
        return resp.getBody();
    }

    private Map<String, Object> decide(HttpHeaders headers, String gateId, String actor, String verb, String comments) {
        Map<String, Object> body = comments == null
                ? Map.of("verb", verb)
                : Map.of("verb", verb, "comments", comments);
        ResponseEntity<Map> resp = rest.exchange(
                url("/api/v1/gates/" + gateId + "/decision"), HttpMethod.POST,
                new HttpEntity<>(body, headersWithActor(actor)), Map.class);
        assertEquals(200, resp.getStatusCode().value(), () -> "decide failed: " + resp.getBody());
        return resp.getBody();
    }

    private String submit(HttpHeaders headers, Map<String, Object> formFields) {
        Map<String, Object> formData = new java.util.LinkedHashMap<>(formFields);
        formData.put("description", "Comment-regenerate integration test submission");
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

    private HttpHeaders headersWithActor(String actor) {
        HttpHeaders headers = headers();
        headers.set("X-Actor", actor);
        return headers;
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
