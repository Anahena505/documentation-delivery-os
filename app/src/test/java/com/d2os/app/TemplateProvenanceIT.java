package com.d2os.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.d2os.app.support.StubAiGatewayClient;
import com.d2os.artifacts.storage.ObjectStoreClient;
import com.d2os.replay.ReplayHarness;
import com.d2os.replay.ReplayReport;
import com.d2os.tenancy.WorkspaceContext;
import com.d2os.testsupport.ContainerFixtures;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
 * US6 acceptance IT (T059, SC-009, FR-014): running an Assessment case to Delivered produces
 * package-facing artifacts whose content is RENDERED from their governed TemplateDefinition (not a
 * placeholder), and whose {@code artifact_revision} row carries provenance back to the exact {@code
 * source_template_id} + {@code template_version} it was produced from — while the case still
 * replays byte-identically.
 *
 * <p>Uses the same Testcontainers/stub-gateway/gate-approval skeleton as {@code
 * AssessmentReadOnlyIT} (Assessment's latest published case type is the gate-embedded {@code
 * assessment-v2}, so the run pauses at a review gate that this test approves before it reaches
 * Delivered). Assessment pins two renderable templates — {@code template:assessment-findings}
 * (producedBy {@code assessment-findings}) and {@code template:assessment-recommendation}
 * (producedBy {@code assessment-recommendation}) — so those two delivered artifacts are the ones
 * that carry rendered content + provenance; the other persona outputs (intake, capability/gap/risk
 * analysts) pin no template and keep the pre-US6 default (persona output IS the content, provenance
 * NULL), which this test also asserts to prove the change is additive and behavior-preserving.
 *
 * <p><b>Byte-identical replay</b> ({@link ReplayHarness}) is unaffected by rendering: the harness
 * re-hashes the stored {@code operation_execution} outputs (the persona bytes), which US6 never
 * touches — rendered artifact content is written to a separate content-addressed object and does
 * not participate in operation replay. The rendering itself is deterministic (pure {@code {{slot}}}
 * substitution over the immutable template body + immutable persona output, no AI call), so it too
 * is reproducible.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.context.annotation.Import(StubAiGatewayClient.class)
class TemplateProvenanceIT {

  @LocalServerPort int port;

  @Autowired TestRestTemplate rest;

  @Autowired JdbcTemplate jdbcTemplate;

  @Autowired DataSource dataSource;

  @Autowired ObjectStoreClient objectStoreClient;

  @Autowired ReplayHarness replayHarness;

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
    featureId = UUID.randomUUID();

    try (Connection conn = dataSource.getConnection()) {
      try (PreparedStatement setCtx =
          conn.prepareStatement("SET app.workspace_id = '" + WORKSPACE_ID + "'")) {
        setCtx.execute();
      }
      insert(
          conn,
          "INSERT INTO workspace (id, name, created_by) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
          WORKSPACE_ID,
          "template-provenance-ws",
          "test");
      insert(
          conn,
          "INSERT INTO project (id, workspace_id, name, created_by) VALUES (?, ?, ?, ?)",
          projectId,
          WORKSPACE_ID,
          "template-provenance-project",
          "test");
      insert(
          conn,
          "INSERT INTO project_version (id, workspace_id, project_id, label, created_by) VALUES (?, ?, ?, ?, ?)",
          projectVersionId,
          WORKSPACE_ID,
          projectId,
          "v1",
          "test");
      insert(
          conn,
          "INSERT INTO feature (id, workspace_id, project_version_id, name, created_by) VALUES (?, ?, ?, ?, ?)",
          featureId,
          WORKSPACE_ID,
          projectVersionId,
          "template-provenance-feature",
          "test");
    }
  }

  @Test
  void deliveredArtifactsRenderFromTemplateWithProvenanceAndReplayIsByteIdentical()
      throws Exception {
    HttpHeaders headers = headers();

    // 1. Classify + confirm as ASSESSMENT, open + run the case, approve its embedded review gate.
    String submissionId =
        submit(
            headers,
            Map.of(
                "subjectExists", true, "hasDeliveredBaseline", false, "requestIntent", "evaluate"));
    Map<String, Object> confirmed = confirm(headers, submissionId, "ASSESSMENT");
    assertEquals("CONFIRMED", confirmed.get("classificationStatus"));

    ResponseEntity<Map> caseResp = createCase(headers, submissionId, featureId);
    assertEquals(
        201, caseResp.getStatusCode().value(), () -> "case create body: " + caseResp.getBody());
    String caseId = (String) caseResp.getBody().get("id");

    ResponseEntity<Void> startResp =
        rest.exchange(
            url("/api/v1/cases/" + caseId + "/start"),
            HttpMethod.POST,
            new HttpEntity<>(null, headers),
            Void.class);
    assertEquals(202, startResp.getStatusCode().value());

    approveOpenGate(caseId, headers, Duration.ofSeconds(120));
    String finalStatus = pollUntilTerminal(caseId, headers, Duration.ofSeconds(120));
    assertEquals(
        "Delivered", finalStatus, "assessment case should reach Delivered with the stub gateway");

    // 2. Provenance (SC-009, FR-014): the two package-facing artifacts carry a non-null
    //    source_template_id + template_version, and their stored content is RENDERED from the
    //    governed template (contains the template's literal skeleton marker), not a placeholder.
    List<RevisionRow> rows = latestRevisionsFor(caseId);
    assertFalse(rows.isEmpty(), "the delivered case must have materialized artifact revisions");

    boolean sawFindings = false;
    boolean sawRecommendation = false;
    for (RevisionRow row : rows) {
      boolean templateBacked =
          row.artifactType.equals("assessment-findings")
              || row.artifactType.equals("assessment-recommendation");
      if (templateBacked) {
        assertNotNull(
            row.sourceTemplateId,
            () ->
                "template-backed artifact " + row.artifactType + " must carry source_template_id");
        assertNotNull(
            row.templateVersion,
            () -> "template-backed artifact " + row.artifactType + " must carry template_version");

        String content = new String(objectStoreClient.get(row.storageRef), StandardCharsets.UTF_8);
        assertTrue(
            content.contains("Rendered from the governed"),
            () ->
                "artifact "
                    + row.artifactType
                    + " content must derive from its template, was: "
                    + content);
        if (row.artifactType.equals("assessment-findings")) {
          assertTrue(
              content.contains("# Assessment Findings"),
              "findings content must be rendered from the Assessment Findings template skeleton");
          sawFindings = true;
        } else {
          assertTrue(
              content.contains("# Assessment Recommendation"),
              "recommendation content must be rendered from the Assessment Recommendation template skeleton");
          sawRecommendation = true;
        }
      } else {
        // Additive / behavior-preserving: persona outputs with no pinned template keep the
        // pre-US6 default — provenance stays NULL, the persona output is the content.
        assertEquals(
            null,
            row.sourceTemplateId,
            () ->
                "artifact "
                    + row.artifactType
                    + " has no pinned template, provenance must stay NULL");
      }
    }
    assertTrue(sawFindings, "delivered package must include a template-rendered FINDINGS artifact");
    assertTrue(
        sawRecommendation,
        "delivered package must include a template-rendered RECOMMENDATION artifact");

    // 3. Byte-identical replay: the persona operation outputs re-hash exactly (rendering never
    //    touches operation_execution outputs — see class javadoc).
    WorkspaceContext.set(WORKSPACE_ID);
    ReplayReport report;
    try {
      report = replayHarness.replay(UUID.fromString(caseId));
    } finally {
      WorkspaceContext.clear();
    }
    assertTrue(report.totalOperations() > 0, "expected recorded operations to replay");
    assertEquals(
        0,
        report.mismatched(),
        () ->
            "the case must replay byte-identically; mismatched="
                + report.mismatched()
                + " of "
                + report.totalOperations());
  }

  /**
   * The latest revision (highest revision_no) per artifact for the case, with provenance columns.
   */
  private List<RevisionRow> latestRevisionsFor(String caseId) throws Exception {
    List<RevisionRow> result = new ArrayList<>();
    try (Connection conn = dataSource.getConnection()) {
      try (PreparedStatement setCtx =
          conn.prepareStatement("SET app.workspace_id = '" + WORKSPACE_ID + "'")) {
        setCtx.execute();
      }
      try (PreparedStatement ps =
          conn.prepareStatement(
              "SELECT a.artifact_type, ar.source_template_id, ar.template_version, ar.storage_ref "
                  + "FROM artifact_revision ar JOIN artifact a ON a.id = ar.artifact_id "
                  + "WHERE a.case_instance_id = ? "
                  + "AND ar.revision_no = (SELECT max(r2.revision_no) FROM artifact_revision r2 "
                  + "                      WHERE r2.artifact_id = ar.artifact_id)")) {
        ps.setObject(1, UUID.fromString(caseId));
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            Object tid = rs.getObject("source_template_id");
            result.add(
                new RevisionRow(
                    rs.getString("artifact_type"),
                    tid == null ? null : (UUID) tid,
                    rs.getString("template_version"),
                    rs.getString("storage_ref")));
          }
        }
      }
    }
    return result;
  }

  private record RevisionRow(
      String artifactType, UUID sourceTemplateId, String templateVersion, String storageRef) {}

  // ---- helpers (same skeleton as AssessmentReadOnlyIT)
  // --------------------------------------------

  private String submit(HttpHeaders headers, Map<String, Object> formFields) {
    Map<String, Object> formData = new java.util.LinkedHashMap<>(formFields);
    formData.put("description", "Template provenance integration test submission");
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

  private Map<String, Object> confirm(HttpHeaders headers, String submissionId, String caseType) {
    ResponseEntity<Map> resp =
        rest.exchange(
            url("/api/v1/submissions/" + submissionId + "/case-type/confirm"),
            HttpMethod.POST,
            new HttpEntity<>(Map.of("caseType", caseType), headers),
            Map.class);
    assertEquals(200, resp.getStatusCode().value(), () -> "confirm failed: " + resp.getBody());
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

  private String pollUntilTerminal(String caseId, HttpHeaders headers, Duration timeout)
      throws InterruptedException {
    Instant deadline = Instant.now().plus(timeout);
    while (Instant.now().isBefore(deadline)) {
      ResponseEntity<Map> resp =
          rest.exchange(
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

  private void approveOpenGate(String caseId, HttpHeaders headers, Duration timeout)
      throws InterruptedException {
    Instant deadline = Instant.now().plus(timeout);
    while (Instant.now().isBefore(deadline)) {
      List<Map<String, Object>> openGates = listOpenGates(caseId, headers);
      if (!openGates.isEmpty()) {
        String gateId = (String) openGates.get(0).get("id");
        decide(headers, gateId, "reviewer-1", "APPROVE", null);
        return;
      }
      Thread.sleep(500);
    }
    fail("review-gate did not open for case " + caseId + " within " + timeout);
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> listOpenGates(String caseId, HttpHeaders headers) {
    ResponseEntity<List> resp =
        rest.exchange(
            url("/api/v1/gates?caseId=" + caseId),
            HttpMethod.GET,
            new HttpEntity<>(headers),
            List.class);
    assertEquals(200, resp.getStatusCode().value());
    List<Map<String, Object>> gates = (List<Map<String, Object>>) (List<?>) resp.getBody();
    List<Map<String, Object>> open = new ArrayList<>();
    for (Map<String, Object> gate : gates) {
      if ("OPEN".equals(gate.get("status"))) {
        open.add(gate);
      }
    }
    return open;
  }

  private Map<String, Object> decide(
      HttpHeaders headers, String gateId, String actor, String verb, String comments) {
    Map<String, Object> body =
        comments == null ? Map.of("verb", verb) : Map.of("verb", verb, "comments", comments);
    ResponseEntity<Map> resp =
        rest.exchange(
            url("/api/v1/gates/" + gateId + "/decision"),
            HttpMethod.POST,
            new HttpEntity<>(body, headersWithActor(actor)),
            Map.class);
    assertEquals(
        200, resp.getStatusCode().value(), () -> "gate decision failed: " + resp.getBody());
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
