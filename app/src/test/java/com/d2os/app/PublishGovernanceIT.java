package com.d2os.app;

import com.d2os.app.support.StubAiGatewayClient;
import com.d2os.catalog.DefinitionAsset;
import com.d2os.catalog.DefinitionPublishService;
import com.d2os.catalog.DraftService;
import com.d2os.tenancy.WorkspaceContext;
import com.d2os.testsupport.ContainerFixtures;
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
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US2 acceptance IT (tasks.md T019, SC-002/003/004). Drives the real {@code PublishController} /
 * {@code PublishService} / {@code GateService} / {@code DeltaReportService} code — submit-review
 * opens a D4 gate and produces a delta report against the prior published version; editing an
 * InReview draft is refused (the InReview-freeze half {@code StudioAuthoringIT}, T012, explicitly
 * deferred here since T013 is what first calls {@code DefinitionAsset#markInReview()}); publish is
 * refused before the gate decides; APPROVE unblocks publish, which stamps a checksum and writes a
 * {@code DEFINITION_PUBLISHED} audit entry; duplicate/semver/tamper conflicts each 409; a
 * MAJOR-version draft opens two gates and stays blocked until both are APPROVED.
 *
 * <p><b>Cannot actually run in this environment</b> (no Docker, confirmed in every prior phase's
 * IT) — written to be logically sound against the real code and hand-traced rather than asserted
 * to pass, following the {@code StudioAuthoringIT}/{@code GateFlowIT}/{@code CommentRegenerateIT}
 * skeleton: Testcontainers via {@link ContainerFixtures#startAll()}, a {@link StubAiGatewayClient}
 * import, direct-JDBC tenancy seeding, and (following {@code DeprecationIT}'s convention for
 * service-bean calls made directly from the test thread rather than over HTTP) explicit {@link
 * WorkspaceContext#set} before any direct {@link DraftService}/{@link DefinitionPublishService}
 * call, since {@code WorkspaceAwareDataSource} stamps {@code app.workspace_id} from {@link
 * WorkspaceContext} at connection-checkout time regardless of whether the call arrived over HTTP.
 *
 * <p><b>One documented, honest scope gap</b> (T019's own brief: "flag any real gaps honestly"):
 * the "duplicate (type,key,version) → 409 on publish" scenario the task brief describes is, on
 * inspection of the real schema, structurally unreachable AT PUBLISH specifically —
 * {@code uq_definition_type_key_version} (V3) is a GLOBAL unique constraint (no workspace_id
 * component), so a genuine duplicate tuple is already refused at draft CREATE time
 * ({@code DraftController}, T008, already covered by {@code StudioAuthoringIT}'s conflict path)
 * long before a row could ever reach InReview/publish holding a colliding tuple. {@link
 * #duplicateTypeKeyVersionIsRejectedAtCreateNotPublish} exercises the actually-reachable form of
 * this conflict (create-time, against an already-Published row) and documents why the publish-time
 * form cannot occur; {@code PublishService.publish}'s defensive {@code
 * DataIntegrityViolationException} catch (T016) is still present as belt-and-suspenders even though
 * this suite cannot exercise it directly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StubAiGatewayClient.class)
class PublishGovernanceIT {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    DataSource dataSource;

    @Autowired
    DraftService draftService;

    @Autowired
    DefinitionPublishService definitionPublishService;

    private static final UUID WORKSPACE_ID = UUID.randomUUID();

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
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement setCtx = conn.prepareStatement("SET app.workspace_id = '" + WORKSPACE_ID + "'")) {
                setCtx.execute();
            }
            insert(conn, "INSERT INTO workspace (id, name, created_by) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                    WORKSPACE_ID, "publish-governance-ws", "test");
        }
    }

    // ---- 1. submit-review opens D4 and produces a delta report against the prior published version ---

    @Test
    void submitReviewOpensD4GateAndProducesDeltaReport() {
        HttpHeaders headers = headers();
        String key = "pub-delta-" + UUID.randomUUID();

        seedPublished("prompt", key, "1.0.0",
                "{\"personaKey\":\"p\",\"template\":\"Hello {{a}}\"}");

        Map<String, Object> draft = createDraft(headers, "prompt", key, "1.1.0",
                Map.of("personaKey", "p", "template", "Hello {{a}}, and also {{b}}"));
        String draftId = (String) draft.get("id");

        Map<String, Object> submitted = submitReview(headers, draftId);
        String d4GateId = (String) submitted.get("d4GateId");
        assertNotNull(d4GateId, "submit-review must open the D4 gate");
        assertNull(submitted.get("architectureBoardGateId"), "a MINOR bump must not open the board gate");
        assertEquals(Boolean.FALSE, submitted.get("majorVersionBump"));
        String deltaReportId = (String) submitted.get("deltaReportId");
        assertNotNull(deltaReportId, "a prior published version exists — a delta report must be produced");

        Map<String, Object> gate = getGate(headers, d4GateId);
        assertEquals("OPEN", gate.get("status"));
        assertEquals("APPROVAL", gate.get("gateType"));
        assertEquals("catalog-publish-review", gate.get("gateDefinitionKey"));
        assertEquals("DEFINITION_VERSION", gate.get("subjectType"));
        assertEquals(draftId, gate.get("subjectId"));

        ResponseEntity<Map> deltaResp = rest.exchange(
                url("/api/v1/gates/" + d4GateId + "/delta-report"), HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        assertEquals(200, deltaResp.getStatusCode().value());
        assertTrue(((String) deltaResp.getBody().get("diffContent")).contains("also"),
                "the unified diff must show the added template text");
        assertNotNull(deltaResp.getBody().get("diffHash"));
        assertNotNull(deltaResp.getBody().get("toDefinitionId"), "definition-pair shape carries toDefinitionId");
    }

    // ---- 2. editing an InReview draft is refused (409) — the half StudioAuthoringIT deferred to T019 ---

    @Test
    void editWhileInReviewIsConflict() {
        HttpHeaders headers = headers();
        String key = "pub-freeze-" + UUID.randomUUID();
        Map<String, Object> draft = createDraft(headers, "template", key, "1.0.0", Map.of("v", 1));
        String draftId = (String) draft.get("id");

        submitReview(headers, draftId);

        ResponseEntity<Map> updateResp = rest.exchange(
                url("/api/v1/catalog/drafts/" + draftId), HttpMethod.PUT,
                new HttpEntity<>(Map.of("body", Map.of("v", 2)), headers), Map.class);
        assertEquals(409, updateResp.getStatusCode().value(), "an InReview draft must refuse edits");
    }

    // ---- 3. publish before the gate is decided is refused (409) ------------------------------------

    @Test
    void publishBeforeGateDecidedIsConflict() {
        HttpHeaders headers = headers();
        String key = "pub-early-" + UUID.randomUUID();
        Map<String, Object> draft = createDraft(headers, "playbook", key, "1.0.0", Map.of("k", "v"));
        String draftId = (String) draft.get("id");
        submitReview(headers, draftId);

        ResponseEntity<Map> publishResp = rest.exchange(
                url("/api/v1/catalog/drafts/" + draftId + "/publish"), HttpMethod.POST,
                new HttpEntity<>(null, headers), Map.class);
        assertEquals(409, publishResp.getStatusCode().value(), "publish before the gate is APPROVED must be refused");
    }

    // ---- 4. APPROVE unblocks publish: checksum recorded, status flips, audited in one transaction ----

    @Test
    void approvedGateUnblocksPublishWithChecksumAndAudit() {
        HttpHeaders headers = headers();
        String key = "pub-happy-" + UUID.randomUUID();
        Map<String, Object> draft = createDraft(headers, "case_type", key, "1.0.0", Map.of("k", "v"));
        String draftId = (String) draft.get("id");

        Map<String, Object> submitted = submitReview(headers, draftId);
        String d4GateId = (String) submitted.get("d4GateId");

        Map<String, Object> decided = decide(headers, d4GateId, "reviewer-1", "APPROVE", null);
        assertEquals("APPROVED", decided.get("status"));

        Map<String, Object> published = publish(headers, draftId);
        assertEquals("Published", published.get("status"));
        String checksum = (String) published.get("checksum");
        assertNotNull(checksum);
        assertEquals(64, checksum.length(), "checksum is a SHA-256 hex digest");

        Long auditCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_entry WHERE workspace_id = ? AND subject_id = ? AND action = 'DEFINITION_PUBLISHED'",
                Long.class, WORKSPACE_ID, UUID.fromString(draftId));
        assertEquals(1L, auditCount, "publish must write exactly one DEFINITION_PUBLISHED audit entry");

        String statusInDb = jdbcTemplate.queryForObject(
                "SELECT status FROM definition_asset WHERE id = ?", String.class, UUID.fromString(draftId));
        assertEquals("Published", statusInDb);
    }

    // ---- 5. duplicate (type,key,version): reachable at CREATE (global unique), not at publish ---------

    @Test
    void duplicateTypeKeyVersionIsRejectedAtCreateNotPublish() {
        HttpHeaders headers = headers();
        String key = "pub-dup-" + UUID.randomUUID();
        seedPublished("rule", key, "1.0.0", "{\"placeholder\":true}");

        // uq_definition_type_key_version (V3) has NO workspace_id component — it is global — so a
        // second row claiming the exact same (type,key,version) is refused right here, long before
        // a row could ever reach InReview/publish holding a colliding tuple (see class javadoc).
        ResponseEntity<Map> createResp = rest.exchange(
                url("/api/v1/catalog/drafts"), HttpMethod.POST,
                new HttpEntity<>(Map.of("type", "rule", "key", key, "version", "1.0.0",
                        "body", Map.of("placeholder", true)), headers), Map.class);
        assertEquals(409, createResp.getStatusCode().value(),
                "a duplicate (type,key,version) against an already-Published row is refused at create");
    }

    // ---- 6. unordered semver is refused at publish (409) -----------------------------------------------

    @Test
    void unorderedSemverIsConflictAtPublish() {
        HttpHeaders headers = headers();
        String key = "pub-semver-" + UUID.randomUUID();
        seedPublished("rubric", key, "2.0.0",
                "{\"personaKey\":\"p\",\"criteria\":[{\"name\":\"a\",\"weight\":1.0,\"critical\":true}]}");

        Map<String, Object> draft = createDraft(headers, "rubric", key, "1.5.0", Map.of(
                "personaKey", "p",
                "criteria", java.util.List.of(Map.of("name", "a", "weight", 1.0, "critical", true))));
        String draftId = (String) draft.get("id");

        Map<String, Object> submitted = submitReview(headers, draftId);
        assertEquals(Boolean.FALSE, submitted.get("majorVersionBump"), "1.5.0 after 2.0.0 is not a MAJOR bump");
        decide(headers, (String) submitted.get("d4GateId"), "reviewer-2", "APPROVE", null);

        ResponseEntity<Map> publishResp = rest.exchange(
                url("/api/v1/catalog/drafts/" + draftId + "/publish"), HttpMethod.POST,
                new HttpEntity<>(null, headers), Map.class);
        assertEquals(409, publishResp.getStatusCode().value(),
                "1.5.0 is not greater than the prior published 2.0.0 — publish must be refused");
    }

    // ---- 7. a body changed after the hash was pinned is refused at publish (409, tamper guard) --------

    @Test
    void changedContentHashAfterPinIsConflictAtPublish() {
        HttpHeaders headers = headers();
        String key = "pub-tamper-" + UUID.randomUUID();
        Map<String, Object> draft = createDraft(headers, "playbook", key, "1.0.0", Map.of("k", "v"));
        String draftId = (String) draft.get("id");

        Map<String, Object> submitted = submitReview(headers, draftId);

        // Simulate an anomaly (direct DB write) bypassing the InReview freeze the ordinary
        // /api/v1/catalog/drafts/{id} PUT path already refuses (test 2 above) — this is exactly the
        // "shouldn't be possible... but check defensively" scenario tasks.md T016 calls out.
        jdbcTemplate.update("UPDATE definition_asset SET body = ?::jsonb WHERE id = ?",
                "{\"k\":\"tampered\"}", UUID.fromString(draftId));

        decide(headers, (String) submitted.get("d4GateId"), "reviewer-3", "APPROVE", null);

        ResponseEntity<Map> publishResp = rest.exchange(
                url("/api/v1/catalog/drafts/" + draftId + "/publish"), HttpMethod.POST,
                new HttpEntity<>(null, headers), Map.class);
        assertEquals(409, publishResp.getStatusCode().value(),
                "a body mutated after the content hash was pinned must fail the tamper guard at publish");
    }

    // ---- 8. a MAJOR bump opens two gates; publish stays blocked until BOTH are APPROVED -----------------

    @Test
    void majorVersionBumpRequiresBothGatesApproved() {
        HttpHeaders headers = headers();
        String key = "pub-major-" + UUID.randomUUID();
        seedPublished("workflow", key, "1.0.0", "{\"k\":\"v1\"}");

        Map<String, Object> draft = createDraft(headers, "workflow", key, "2.0.0", Map.of("k", "v2"));
        String draftId = (String) draft.get("id");

        Map<String, Object> submitted = submitReview(headers, draftId);
        assertEquals(Boolean.TRUE, submitted.get("majorVersionBump"), "1.0.0 -> 2.0.0 is a MAJOR bump");
        String d4GateId = (String) submitted.get("d4GateId");
        String boardGateId = (String) submitted.get("architectureBoardGateId");
        assertNotNull(boardGateId, "a MAJOR bump must open the architecture-board gate too");

        ResponseEntity<Map> tooEarly = rest.exchange(
                url("/api/v1/catalog/drafts/" + draftId + "/publish"), HttpMethod.POST,
                new HttpEntity<>(null, headers), Map.class);
        assertEquals(409, tooEarly.getStatusCode().value(), "neither gate is decided yet");

        decide(headers, d4GateId, "reviewer-4", "APPROVE", null);
        ResponseEntity<Map> stillBlocked = rest.exchange(
                url("/api/v1/catalog/drafts/" + draftId + "/publish"), HttpMethod.POST,
                new HttpEntity<>(null, headers), Map.class);
        assertEquals(409, stillBlocked.getStatusCode().value(),
                "the D4 gate alone is not enough for a MAJOR bump — the board gate is still OPEN");

        decide(headers, boardGateId, "reviewer-5", "APPROVE", null);
        Map<String, Object> published = publish(headers, draftId);
        assertEquals("Published", published.get("status"), "both gates APPROVED must unblock publish");
    }

    // ---- helpers ------------------------------------------------------------------------------------

    /** Seed a Published prior version directly through the real services (bypassing the gate flow —
     * this is what the PRIOR version being superseded looks like; not what this phase's IT is testing). */
    private DefinitionAsset seedPublished(String type, String key, String version, String bodyJson) {
        WorkspaceContext.set(WORKSPACE_ID);
        try {
            DefinitionAsset draft = draftService.create(type, key, version, bodyJson, WORKSPACE_ID, "seed");
            return definitionPublishService.publish(draft.getId());
        } finally {
            WorkspaceContext.clear();
        }
    }

    private Map<String, Object> createDraft(HttpHeaders headers, String type, String key, String version, Object body) {
        ResponseEntity<Map> resp = rest.exchange(
                url("/api/v1/catalog/drafts"), HttpMethod.POST,
                new HttpEntity<>(Map.of("type", type, "key", key, "version", version, "body", body), headers), Map.class);
        assertEquals(201, resp.getStatusCode().value(), () -> "create " + type + " failed: " + resp.getBody());
        return resp.getBody();
    }

    private Map<String, Object> submitReview(HttpHeaders headers, String draftId) {
        ResponseEntity<Map> resp = rest.exchange(
                url("/api/v1/catalog/drafts/" + draftId + "/submit-review"), HttpMethod.POST,
                new HttpEntity<>(null, headers), Map.class);
        assertEquals(200, resp.getStatusCode().value(), () -> "submit-review failed: " + resp.getBody());
        return resp.getBody();
    }

    private Map<String, Object> publish(HttpHeaders headers, String draftId) {
        ResponseEntity<Map> resp = rest.exchange(
                url("/api/v1/catalog/drafts/" + draftId + "/publish"), HttpMethod.POST,
                new HttpEntity<>(null, headers), Map.class);
        assertEquals(200, resp.getStatusCode().value(), () -> "publish failed: " + resp.getBody());
        return resp.getBody();
    }

    private Map<String, Object> getGate(HttpHeaders headers, String gateId) {
        ResponseEntity<Map> resp = rest.exchange(
                url("/api/v1/gates/" + gateId), HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        assertEquals(200, resp.getStatusCode().value());
        return resp.getBody();
    }

    private Map<String, Object> decide(HttpHeaders headers, String gateId, String actor, String verb, String comments) {
        Map<String, Object> body = comments == null ? Map.of("verb", verb) : Map.of("verb", verb, "comments", comments);
        ResponseEntity<Map> resp = rest.exchange(
                url("/api/v1/gates/" + gateId + "/decision"), HttpMethod.POST,
                new HttpEntity<>(body, headersWithActor(actor)), Map.class);
        assertEquals(200, resp.getStatusCode().value(), () -> "decide failed: " + resp.getBody());
        return resp.getBody();
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
