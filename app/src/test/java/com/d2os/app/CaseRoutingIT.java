package com.d2os.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.d2os.app.support.StubAiGatewayClient;
import com.d2os.testsupport.ContainerFixtures;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
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

/**
 * US1 acceptance IT (T013, SC-001, SC-002): a submission is classified against the
 * case-type-classification DMN (T008), proposes a case type, and a mandatory human confirm/override
 * step (recorded as a D4 Decision, T011) gates Case creation (T012) — so a misread problem never
 * launches the wrong pipeline.
 *
 * <p><b>Scope note (why this test doesn't assert Assessment reaches Delivered):</b> Phase 3 (this
 * feature's US1) only builds classification + confirm + the {@code 412}-until-confirmed gate on
 * Case creation. Assessment's {@code CaseTypeDefinition} catalog content (and its BPMN workflow)
 * ships in a later phase (US2, tasks T014-T019) — until then, no published {@code case_type}
 * definition exists for that key. So for Assessment this test isolates exactly what Phase 3
 * guarantees: (a) the DMN proposes the right type, (b) Case creation is blocked 412 before confirm,
 * and (c) after confirm the 412 is gone and creation now fails for a DIFFERENT, later-phase reason
 * (422 — "no published case_type definition"), never for lack of confirmation.
 *
 * <p>Enhancement's catalog content ships with THIS phase (US3, T020-T026 — {@code
 * CatalogSeedLoader#seedEnhancement}), so its test below is asserted all the way to {@code 201
 * Planned}, same as Initiation — with a directly-seeded Delivered baseline Case satisfying T024's
 * confirm-time gate (the no-baseline 422 rejection itself, and a full baseline-anchored run, are
 * EnhancementBaselineIT's job).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.context.annotation.Import(StubAiGatewayClient.class)
class CaseRoutingIT {

  @LocalServerPort int port;

  @Autowired TestRestTemplate rest;

  @Autowired JdbcTemplate jdbcTemplate;

  @Autowired DataSource dataSource;

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
    S3Client s3 =
        S3Client.builder()
            .endpointOverride(URI.create(ContainerFixtures.MINIO.getS3URL()))
            .region(Region.US_EAST_1)
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(
                        ContainerFixtures.MINIO.getUserName(),
                        ContainerFixtures.MINIO.getPassword())))
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
      try (PreparedStatement setCtx =
          conn.prepareStatement("SET app.workspace_id = '" + WORKSPACE_ID + "'")) {
        setCtx.execute();
      }
      insert(
          conn,
          "INSERT INTO workspace (id, name, created_by) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
          WORKSPACE_ID,
          "case-routing-ws",
          "test");
      insert(
          conn,
          "INSERT INTO project (id, workspace_id, name, created_by) VALUES (?, ?, ?, ?)",
          projectId,
          WORKSPACE_ID,
          "case-routing-project",
          "test");
      insert(
          conn,
          "INSERT INTO project_version (id, workspace_id, project_id, label, created_by) VALUES (?, ?, ?, ?, ?)",
          projectVersionId,
          WORKSPACE_ID,
          projectId,
          "v1",
          "test");
    }
  }

  // ---- 1. Three-way classification -> confirm -> Case creation isolates the Phase 3 guarantee
  // ----

  @Test
  void initiationShapeClassifiesConfirmsAndOpensACase() {
    HttpHeaders headers = headers();

    // No subject on record -> the DMN's "no subject" rule -> INITIATION (SC-001).
    String submissionId = submit(headers, Map.of("subjectExists", false));
    assertProposal(headers, submissionId, "INITIATION", null, "PROPOSED", false);

    Map<String, Object> confirmed = confirm(headers, submissionId, "INITIATION", null);
    assertEquals("CONFIRMED", confirmed.get("classificationStatus"));
    assertEquals("INITIATION", confirmed.get("confirmedCaseType"));
    assertEquals(Boolean.FALSE, confirmed.get("overridden"));

    // Initiation's case_type catalog content predates this phase -> Case creation actually
    // succeeds.
    ResponseEntity<Map> caseResp = createCase(headers, submissionId, newFeature());
    assertEquals(
        201, caseResp.getStatusCode().value(), () -> "case create body: " + caseResp.getBody());
    assertEquals("Planned", caseResp.getBody().get("status"));
  }

  @Test
  void assessmentShapeClassifiesAndIsGatedThenFailsForCatalogNotClassificationReasons() {
    HttpHeaders headers = headers();

    // Subject exists, no delivered baseline yet, requester wants it evaluated -> ASSESSMENT.
    String submissionId =
        submit(
            headers,
            Map.of(
                "subjectExists", true, "hasDeliveredBaseline", false, "requestIntent", "evaluate"));
    assertProposal(headers, submissionId, "ASSESSMENT", null, "PROPOSED", false);

    // Pre-confirm: Case creation is blocked 412 regardless of case type (T012, contracts /cases
    // 412).
    ResponseEntity<Map> preConfirm = createCase(headers, submissionId, newFeature());
    assertEquals(
        412,
        preConfirm.getStatusCode().value(),
        "unconfirmed classification must block Case creation");

    Map<String, Object> confirmed = confirm(headers, submissionId, "ASSESSMENT", null);
    assertEquals("CONFIRMED", confirmed.get("classificationStatus"));
    assertEquals(
        Boolean.FALSE,
        confirmed.get("overridden"),
        "confirming the proposal as-is is not an override");

    // Post-confirm: the 412 is gone. Assessment's CaseTypeDefinition ships in a LATER phase (US2,
    // T015) — until then this fails 422 ("no published case_type definition"), a DIFFERENT failure
    // mode than the classification gate this test targets. Asserting "not 412" (and specifically
    // 422, not e.g. a 5xx) is exactly what Phase 3 guarantees for a case type without catalog
    // content yet.
    ResponseEntity<Map> postConfirm = createCase(headers, submissionId, newFeature());
    assertEquals(
        422,
        postConfirm.getStatusCode().value(),
        () ->
            "expected the catalog-content failure (US2 not yet built), not the classification "
                + "gate; body="
                + postConfirm.getBody());
  }

  @Test
  void enhancementShapeClassifiesConfirmsAgainstADeliveredBaselineAndOpensACase() {
    HttpHeaders headers = headers();

    // Phase 5 (T024, US3, research R4): confirming ENHANCEMENT now requires a Feature with a
    // Delivered baseline Case (read out of the submission's formData.featureId — see
    // SubmissionService#requireDeliveredBaseline). Seed one directly via JDBC (no real pipeline run
    // needed just to prove routing/creation — the full baseline-anchored run is
    // EnhancementBaselineIT,
    // T026); this test only needs the Feature to genuinely carry a Delivered Case row.
    UUID featureId = newFeature();
    seedDeliveredBaselineCase(featureId);

    // Subject exists, a baseline was already delivered, requester wants it changed -> ENHANCEMENT.
    String submissionId =
        submit(
            headers,
            Map.of(
                "subjectExists",
                true,
                "hasDeliveredBaseline",
                true,
                "requestIntent",
                "change",
                "featureId",
                featureId.toString()));
    assertProposal(headers, submissionId, "ENHANCEMENT", null, "PROPOSED", false);

    ResponseEntity<Map> preConfirm = createCase(headers, submissionId, featureId);
    assertEquals(
        412,
        preConfirm.getStatusCode().value(),
        "unconfirmed classification must block Case creation");

    // Confirm succeeds because formData names a Feature with a genuine Delivered baseline (seeded
    // above) — the 422 "no baseline" rejection path itself is exercised, with its own dedicated
    // assertions, by EnhancementBaselineIT (T026).
    Map<String, Object> confirmed = confirm(headers, submissionId, "ENHANCEMENT", null);
    assertEquals("CONFIRMED", confirmed.get("classificationStatus"));

    // Enhancement's CaseTypeDefinition now ships with this phase (US3, T021) — Case creation
    // succeeds, same as Initiation's test above.
    ResponseEntity<Map> postConfirm = createCase(headers, submissionId, featureId);
    assertEquals(
        201,
        postConfirm.getStatusCode().value(),
        () -> "case create body: " + postConfirm.getBody());
    assertEquals("Planned", postConfirm.getBody().get("status"));
  }

  // ---- 2. Override subtest
  // ------------------------------------------------------------------------

  @Test
  void overridingTheProposalRecordsADecisionAndPreservesTheOriginalProposal() {
    HttpHeaders headers = headers();

    String submissionId = submit(headers, Map.of("subjectExists", false)); // proposes INITIATION
    assertProposal(headers, submissionId, "INITIATION", null, "PROPOSED", false);

    // Human overrides: confirms ASSESSMENT instead of the proposed INITIATION.
    Map<String, Object> confirmed =
        confirm(headers, submissionId, "ASSESSMENT", "human judged it read-only");
    assertEquals("ASSESSMENT", confirmed.get("confirmedCaseType"));
    assertEquals(
        "INITIATION",
        confirmed.get("proposedCaseType"),
        "the original proposal is never overwritten");
    assertEquals(Boolean.TRUE, confirmed.get("overridden"));
    UUID decisionId = UUID.fromString((String) confirmed.get("decisionId"));

    // GET reflects the same, persisted state (not just the POST response).
    assertProposal(headers, submissionId, "INITIATION", "ASSESSMENT", "CONFIRMED", true);

    // A D4 Decision was written (case_instance_id NULL — no Case exists yet, V19) referencing the
    // submission via inputs_ref, plus an AuditEntry recording the override — both in the confirm
    // tx.
    Map<String, Object> decisionRow =
        jdbcTemplate.queryForMap(
            "SELECT decision_type, inputs_ref, case_instance_id, rationale FROM decision WHERE id = ?",
            decisionId);
    assertEquals("D4", decisionRow.get("decision_type"));
    assertEquals(submissionId, decisionRow.get("inputs_ref"));
    assertEquals(
        null,
        decisionRow.get("case_instance_id"),
        "no Case exists yet at confirm time (Phase 4, V19)");
    assertEquals("human judged it read-only", decisionRow.get("rationale"));

    Long auditCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM audit_entry WHERE workspace_id = ? AND subject_id = ? AND action = 'CASE_TYPE_OVERRIDDEN'",
            Long.class,
            WORKSPACE_ID,
            UUID.fromString(submissionId));
    assertEquals(1L, auditCount, "the override is durably audited");

    // Re-confirming an already-CONFIRMED submission is rejected 409, not silently re-decided.
    HttpEntity<Map<String, Object>> secondConfirm =
        new HttpEntity<>(Map.of("caseType", "ENHANCEMENT"), headers);
    ResponseEntity<Map> conflict =
        rest.exchange(
            url("/api/v1/submissions/" + submissionId + "/case-type/confirm"),
            HttpMethod.POST,
            secondConfirm,
            Map.class);
    assertEquals(409, conflict.getStatusCode().value());
    // The first confirmation is untouched by the rejected second attempt.
    assertProposal(headers, submissionId, "INITIATION", "ASSESSMENT", "CONFIRMED", true);
  }

  // ---- 3. Ambiguity subtest
  // -------------------------------------------------------------------------

  @Test
  void ambiguousSubmissionSurfacesUndeterminedAndIsStillGatedThenConfirmable() {
    HttpHeaders headers = headers();

    // subject exists but request_intent matches neither "evaluate" (needs baseline=false, which
    // holds) NOR "change" (needs baseline=true) -- "new" matches nothing in the UNIQUE table, so
    // zero rules fire. Per the DMN's header note and CaseTypeClassificationService, this is
    // UNDETERMINED, not a guess.
    String submissionId =
        submit(
            headers,
            Map.of("subjectExists", true, "hasDeliveredBaseline", false, "requestIntent", "new"));
    assertProposal(headers, submissionId, "UNDETERMINED", null, "PROPOSED", false);

    // Still gated: UNDETERMINED is a proposal like any other, not an exemption from the 412 gate.
    ResponseEntity<Map> preConfirm = createCase(headers, submissionId, newFeature());
    assertEquals(412, preConfirm.getStatusCode().value());

    // Per contracts/api.yaml, confirm is allowed even when the proposal was UNDETERMINED -- the
    // human resolves the ambiguity directly; confirming a definite type here is inherently an
    // "override" of a non-answer proposal.
    Map<String, Object> confirmed =
        confirm(headers, submissionId, "INITIATION", "human resolved the ambiguity");
    assertEquals("CONFIRMED", confirmed.get("classificationStatus"));
    assertEquals("UNDETERMINED", confirmed.get("proposedCaseType"));
    assertEquals("INITIATION", confirmed.get("confirmedCaseType"));
    assertEquals(
        Boolean.TRUE,
        confirmed.get("overridden"),
        "resolving UNDETERMINED to a real type counts as an override");

    // The gate lifts: Case creation for INITIATION (catalog content present) now succeeds.
    ResponseEntity<Map> caseResp = createCase(headers, submissionId, newFeature());
    assertEquals(
        201, caseResp.getStatusCode().value(), () -> "case create body: " + caseResp.getBody());
  }

  // ---- helpers
  // --------------------------------------------------------------------------------------

  private String submit(HttpHeaders headers, Map<String, Object> formFields) {
    Map<String, Object> formData = new java.util.LinkedHashMap<>(formFields);
    formData.put("description", "Integration test submission");
    Map<String, Object> body = Map.of("formData", formData);
    ResponseEntity<Map> resp =
        rest.exchange(
            url("/api/v1/submissions"),
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            Map.class);
    assertEquals(201, resp.getStatusCode().value(), () -> "submit failed: " + resp.getBody());
    String id = (String) resp.getBody().get("id");
    assertNotNull(id);
    return id;
  }

  private void assertProposal(
      HttpHeaders headers,
      String submissionId,
      String expectedProposed,
      String expectedConfirmed,
      String expectedStatus,
      boolean expectedOverridden) {
    ResponseEntity<Map> resp =
        rest.exchange(
            url("/api/v1/submissions/" + submissionId + "/case-type"),
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);
    assertEquals(200, resp.getStatusCode().value());
    Map<?, ?> body = resp.getBody();
    assertEquals(expectedProposed, body.get("proposedCaseType"));
    assertEquals(expectedConfirmed, body.get("confirmedCaseType"));
    assertEquals(expectedStatus, body.get("classificationStatus"));
    assertEquals(expectedOverridden, body.get("overridden"));
  }

  private Map<String, Object> confirm(
      HttpHeaders headers, String submissionId, String caseType, String rationale) {
    Map<String, Object> body =
        rationale == null
            ? Map.of("caseType", caseType)
            : Map.of("caseType", caseType, "rationale", rationale);
    ResponseEntity<Map> resp =
        rest.exchange(
            url("/api/v1/submissions/" + submissionId + "/case-type/confirm"),
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            Map.class);
    assertEquals(200, resp.getStatusCode().value(), () -> "confirm failed: " + resp.getBody());
    assertTrue(resp.getBody().get("decisionId") != null, "confirm must record a Decision");
    return resp.getBody();
  }

  private ResponseEntity<Map> createCase(HttpHeaders headers, String submissionId, UUID featureId) {
    return rest.exchange(
        url("/api/v1/cases"),
        HttpMethod.POST,
        new HttpEntity<>(
            Map.of("submissionId", submissionId, "featureId", featureId.toString()), headers),
        Map.class);
  }

  /**
   * Fresh Feature per case-creation attempt so the Q1/FR-016 mutating-case-per-feature index never
   * interferes with these classification-gate assertions.
   */
  private UUID newFeature() {
    UUID featureId = UUID.randomUUID();
    try (Connection conn = dataSource.getConnection()) {
      try (PreparedStatement setCtx =
          conn.prepareStatement("SET app.workspace_id = '" + WORKSPACE_ID + "'")) {
        setCtx.execute();
      }
      insert(
          conn,
          "INSERT INTO feature (id, workspace_id, project_version_id, name, created_by) VALUES (?, ?, ?, ?, ?)",
          featureId,
          WORKSPACE_ID,
          projectVersionId,
          "f-" + featureId,
          "test");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    return featureId;
  }

  /**
   * Directly insert a Delivered {@code case_instance} row on {@code featureId} (T024's confirm-time
   * baseline check only needs a genuine Delivered Case to exist — it doesn't require that case to
   * have run a real pipeline; {@code submission_id} has no FK, so a fresh random id is fine here).
   */
  private void seedDeliveredBaselineCase(UUID featureId) {
    try (Connection conn = dataSource.getConnection()) {
      try (PreparedStatement setCtx =
          conn.prepareStatement("SET app.workspace_id = '" + WORKSPACE_ID + "'")) {
        setCtx.execute();
      }
      insert(
          conn,
          "INSERT INTO case_instance (id, workspace_id, feature_id, submission_id, "
              + "case_type_key, case_type_version, mode, status, created_by) "
              + "VALUES (?, ?, ?, ?, ?, ?, 'mutating', 'Delivered', ?)",
          UUID.randomUUID(),
          WORKSPACE_ID,
          featureId,
          UUID.randomUUID(),
          "initiation",
          "2.0.0",
          "test");
    } catch (Exception e) {
      throw new RuntimeException(e);
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
