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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * US1 acceptance IT (T018, SC-001): drives an Assessment case through the {@code assessment-v2}
 * workflow (T015) to its embedded {@code review-gate} callActivity (T012/T014), exercising the full
 * gate surface (T017) — worklist, detail, and the three decision verbs — plus the non-self-review and
 * non-decidable-state guards. Uses Assessment (not Initiation) as the driver case type: assessment-v2's
 * 6-persona suite reaches the gate far faster than initiation-v3's 13-persona + parallel + consistency
 * pipeline, while still exercising the identical {@code review-gate} ProcessDefinition/GateService path
 * (R1: "the same gate is reused across case types by reference").
 *
 * <p><b>Cannot actually run in this environment</b> (no Docker, confirmed in prior phases' ITs) — this
 * class is written to be logically sound against the real {@code GateService}/{@code GateController}/
 * {@code GateTaskBridge}/{@code EngineGateReleasePortImpl} code and the {@code review-gate.bpmn20.xml}
 * / {@code assessment-v2.bpmn20.xml} process definitions authored alongside it, traced by hand rather
 * than asserted to pass.
 *
 * <p>Follows the {@code AssessmentReadOnlyIT}/{@code CaseRoutingIT} IT skeleton: Testcontainers via
 * {@link ContainerFixtures#startAll()}, a {@link StubAiGatewayClient} import so persona calls are
 * deterministic and provider-free, and direct-JDBC tenancy seeding.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.context.annotation.Import(StubAiGatewayClient.class)
class GateFlowIT {

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

        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement setCtx = conn.prepareStatement("SET app.workspace_id = '" + WORKSPACE_ID + "'")) {
                setCtx.execute();
            }
            insert(conn, "INSERT INTO workspace (id, name, created_by) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                    WORKSPACE_ID, "gate-flow-ws", "test");
            insert(conn, "INSERT INTO project (id, workspace_id, name, created_by) VALUES (?, ?, ?, ?)",
                    projectId, WORKSPACE_ID, "gate-flow-project", "test");
            insert(conn, "INSERT INTO project_version (id, workspace_id, project_id, label, created_by) VALUES (?, ?, ?, ?, ?)",
                    projectVersionId, WORKSPACE_ID, projectId, "v1", "test");
        }
    }

    // ---- 1. APPROVE: gate opens, worklist/detail show it, APPROVE releases the engine task and the
    //         case proceeds to Delivered (SC-001: full decision-record path) ------------------------

    @Test
    void approvedGateReleasesTheEngineTaskAndTheCaseIsDelivered() throws Exception {
        HttpHeaders headers = headers();
        String caseId = openCaseToGate(headers);

        // GET /gates?caseId= lists exactly one OPEN REVIEW gate for this case (T017).
        List<Map<String, Object>> gates = listGates(headers, caseId);
        assertEquals(1, gates.size(), "assessment-v2 opens exactly one review-gate");
        Map<String, Object> gate = gates.get(0);
        assertEquals("OPEN", gate.get("status"));
        assertEquals("REVIEW", gate.get("gateType"));
        assertEquals("review-gate", gate.get("gateDefinitionKey"));
        String gateId = (String) gate.get("id");

        // GET /gates/{id} resolves inputsRef (FR-002/003) alongside the summary fields.
        ResponseEntity<Map> detailResp = rest.exchange(
                url("/api/v1/gates/" + gateId), HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        assertEquals(200, detailResp.getStatusCode().value());
        assertNotNull(detailResp.getBody().get("inputsRef"), "gate detail resolves inputsRef");

        // APPROVE — a distinct actor from the case's own submitter (createdBy = "api" for every
        // /api/v1/cases POST, per CaseController; using a different X-Actor exercises the "not barred
        // by non-self-review" path, mirrored by the self-review 403 test below).
        Map<String, Object> decided = decide(headers, gateId, "reviewer-1", "APPROVE", null);
        assertEquals("APPROVED", decided.get("status"));
        assertNotNull(decided.get("decisionId"), "APPROVE writes a Decision");

        // The Decision row carries the reviewer/verb/timestamp/outcome (SC-001's who/what/when/why).
        String decisionId = (String) decided.get("decisionId");
        Map<String, Object> decisionRow = jdbcTemplate.queryForMap(
                "SELECT decision_type, decided_by, inputs_ref FROM decision WHERE id = ?",
                UUID.fromString(decisionId));
        assertEquals("GATE_APPROVE", decisionRow.get("decision_type"));
        assertEquals("reviewer-1", decisionRow.get("decided_by"));
        assertEquals(gateId, decisionRow.get("inputs_ref"), "the Decision points back at the gate it decided");

        // Outbox events were emitted (T016) for this gate: GateService.open/decide each call
        // AuditWriter.record directly (action-named, e.g. "GATE_APPROVE" — the general audit trail,
        // same convention as PromotionGateService) PLUS GateEventPublisher emits the contract-named
        // "GATE_OPENED"/"GATE_DECIDED" events (research R8's projection-sufficient GateEventPayload,
        // what Phase 7's projector consumes) — so a full open+decide cycle leaves THREE event_outbox
        // rows for this gate, not two. Assert the two contract events specifically rather than an exact
        // count/order (the action-named and GATE_DECIDED writes can land in the same transaction-commit
        // millisecond, so ordering between them is not guaranteed).
        Long openedCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM event_outbox WHERE aggregate_id = ? AND event_type = 'GATE_OPENED'",
                Long.class, UUID.fromString(gateId));
        assertEquals(1L, openedCount, "GATE_OPENED is emitted exactly once, at open");

        Map<String, Object> decidedEvent = jdbcTemplate.queryForMap(
                "SELECT payload FROM event_outbox WHERE aggregate_id = ? AND event_type = 'GATE_DECIDED'",
                UUID.fromString(gateId));
        // payload is JSONB — the PostgreSQL JDBC driver hands it back as a PGobject, not a String, so
        // use toString() (PGobject.toString() delegates to getValue(), the raw JSON text) rather than
        // an unsafe (String) cast.
        String decidedPayload = decidedEvent.get("payload").toString();
        assertTrue(decidedPayload.contains("\"gateId\""), "payload carries gateId");
        assertTrue(decidedPayload.contains("\"decisionVerb\":\"APPROVE\""), "payload carries the decision verb");

        // APPROVE released the parked userTask (EngineGateReleasePortImpl.completeGateTask) — the
        // assessment-v2 callActivity's flowable:out mapping propagates gateDecision='APPROVE' back to
        // the parent, whose gw-gate exclusiveGateway routes to assemble-package. The case reaches
        // Delivered exactly like AssessmentReadOnlyIT's un-gated assessment-v1 run.
        String finalStatus = pollUntilTerminal(caseId, headers, Duration.ofSeconds(120));
        assertEquals("Delivered", finalStatus, "APPROVE must let the case proceed to delivery");
    }

    // ---- 2. Self-review: the case's own submitter cannot decide its own gate (FR-018) -------------

    @Test
    void selfReviewIsRejected() throws Exception {
        HttpHeaders headers = headers();
        String caseId = openCaseToGate(headers);
        String gateId = listGates(headers, caseId).get(0).get("id").toString();

        // Every /api/v1/cases POST records CaseInstance.createdBy = "api" (CaseController — there is
        // no per-request actor header on that endpoint yet). GateService.requireNotSelfReview compares
        // the deciding actor against that same field, so X-Actor: api IS the case's own submitter.
        ResponseEntity<Map> resp = rest.exchange(
                url("/api/v1/gates/" + gateId + "/decision"), HttpMethod.POST,
                new HttpEntity<>(Map.of("verb", "APPROVE"), headersWithActor("api")), Map.class);
        assertEquals(403, resp.getStatusCode().value(), "the case's own submitter may not decide its own gate");

        // The gate is untouched by the refused attempt — still OPEN, no Decision recorded.
        ResponseEntity<Map> gateResp = rest.exchange(
                url("/api/v1/gates/" + gateId), HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        assertEquals("OPEN", gateResp.getBody().get("status"), "a refused self-review must not change gate state");
    }

    // ---- 3. REJECT then re-decide: 409 on an already-decided gate ---------------------------------

    @Test
    void rejectTransitionsTheGateThenRedecidingIsConflict() throws Exception {
        HttpHeaders headers = headers();
        String caseId = openCaseToGate(headers);
        String gateId = listGates(headers, caseId).get(0).get("id").toString();

        Map<String, Object> decided = decide(headers, gateId, "reviewer-2", "REJECT", null);
        assertEquals("REJECTED", decided.get("status"));

        Map<String, Object> decisionRow = jdbcTemplate.queryForMap(
                "SELECT decision_type FROM decision WHERE id = ?", UUID.fromString((String) decided.get("decisionId")));
        assertEquals("GATE_REJECT", decisionRow.get("decision_type"));

        // REJECTED is terminal (GateStatus) — deciding again is 409, never silently re-decided.
        ResponseEntity<Map> secondDecision = rest.exchange(
                url("/api/v1/gates/" + gateId + "/decision"), HttpMethod.POST,
                new HttpEntity<>(Map.of("verb", "APPROVE"), headersWithActor("reviewer-3")), Map.class);
        assertEquals(409, secondDecision.getStatusCode().value(), "an already-decided gate is not re-decidable");
    }

    // ---- 4. REQUEST_CHANGES: reviewerComments recorded, gate -> REGENERATING -----------------------

    @Test
    void requestChangesRecordsReviewerCommentsAndTransitionsToRegenerating() throws Exception {
        HttpHeaders headers = headers();
        String caseId = openCaseToGate(headers);
        String gateId = listGates(headers, caseId).get(0).get("id").toString();

        // REQUEST_CHANGES requires non-blank comments — the endpoint 400s otherwise (T017).
        ResponseEntity<Map> missingComments = rest.exchange(
                url("/api/v1/gates/" + gateId + "/decision"), HttpMethod.POST,
                new HttpEntity<>(Map.of("verb", "REQUEST_CHANGES"), headersWithActor("reviewer-4")), Map.class);
        assertEquals(400, missingComments.getStatusCode().value(), "REQUEST_CHANGES without comments is a 400");

        Map<String, Object> decided = decide(headers, gateId, "reviewer-4", "REQUEST_CHANGES",
                "please clarify the risk register section");
        assertEquals("REGENERATING", decided.get("status"));

        // reviewerComments + the Decision's rationale both carry the comment text (Q4's
        // comment-and-regenerate contract — no content-edit path exists, only this typed verb+comment).
        ResponseEntity<Map> gateDetail = rest.exchange(
                url("/api/v1/gates/" + gateId), HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        assertEquals("please clarify the risk register section", gateDetail.getBody().get("reviewerComments"));

        Map<String, Object> decisionRow = jdbcTemplate.queryForMap(
                "SELECT decision_type, rationale FROM decision WHERE id = ?",
                UUID.fromString((String) decided.get("decisionId")));
        assertEquals("GATE_REQUEST_CHANGES", decisionRow.get("decision_type"));
        assertEquals("please clarify the risk register section", decisionRow.get("rationale"));
    }

    // ---- helpers --------------------------------------------------------------------------------------

    /**
     * Classify + confirm as ASSESSMENT, open + start a Case, and poll until the embedded review-gate
     * userTask has opened a GateInstance (GET /gates?caseId= becomes non-empty). Returns the case id.
     * assessment's latest published case_type is now v5.0.0 (T015, CatalogSeedLoader.seedAssessmentV2)
     * — assessment-v1's un-gated v4.0.0 stays available only for cases already pinned to it
     * (Principle I), so a freshly created case always resolves to assessment-v2's gated workflow.
     */
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
        fail("case did not reach a terminal state within " + timeout);
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listGates(HttpHeaders headers, String caseId) {
        ResponseEntity<List> resp = rest.exchange(
                url("/api/v1/gates?caseId=" + caseId), HttpMethod.GET, new HttpEntity<>(headers), List.class);
        assertEquals(200, resp.getStatusCode().value());
        return (List<Map<String, Object>>) (List<?>) resp.getBody();
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
        formData.put("description", "Gate flow integration test submission");
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
