package com.d2os.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.d2os.app.support.StubAiGatewayClient;
import com.d2os.testsupport.ContainerFixtures;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

/**
 * US3 verification (T033, SC-004): the Consistency-Check blocks a seeded hard cross-artifact
 * contradiction from delivery and lets a coherent case through.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.context.annotation.Import(StubAiGatewayClient.class)
class ConsistencyCheckIT {

  @LocalServerPort int port;
  @Autowired TestRestTemplate rest;
  @Autowired DataSource dataSource;
  @Autowired StubAiGatewayClient.LatencyControllableGateway gateway;

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
    }
  }

  @BeforeEach
  void setup() throws Exception {
    gateway.reset();
    UUID projectId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    featureId = UUID.randomUUID();
    try (Connection conn = dataSource.getConnection()) {
      exec(conn, "SET app.workspace_id = '" + WORKSPACE_ID + "'");
      insert(
          conn,
          "INSERT INTO workspace (id, name, created_by) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
          WORKSPACE_ID,
          "it-ws",
          "test");
      insert(
          conn,
          "INSERT INTO project (id, workspace_id, name, created_by) VALUES (?, ?, ?, ?)",
          projectId,
          WORKSPACE_ID,
          "it-proj",
          "test");
      insert(
          conn,
          "INSERT INTO project_version (id, workspace_id, project_id, label, created_by) VALUES (?, ?, ?, ?, ?)",
          versionId,
          WORKSPACE_ID,
          projectId,
          "v1",
          "test");
      insert(
          conn,
          "INSERT INTO feature (id, workspace_id, project_version_id, name, created_by) VALUES (?, ?, ?, ?, ?)",
          featureId,
          WORKSPACE_ID,
          versionId,
          "it-feat",
          "test");
    }
  }

  @Test
  void coherentCasePassesConsistencyAndDelivers() throws Exception {
    HttpHeaders h = headers();
    String caseId = submitOpenAndStart(h);
    assertEquals(
        "Delivered",
        pollStatus(caseId, h, Duration.ofSeconds(150), "Delivered"),
        "a coherent case should pass the consistency check and deliver");
    // No blocking deterministic findings for a coherent case.
    assertEquals(
        0, deterministicOpenCount(caseId), "coherent case should have no deterministic findings");
  }

  @Test
  void seededContradictionBlocksDeliveryAndCannotBeWaived() throws Exception {
    // Two specialists assert a contradictory value for the same attribute — a hard, machine-
    // detectable contradiction the deterministic tier must catch (padded so both outputs still
    // pass their own rubric and become validated inputs to the check).
    String pad = "Specialist analysis produced for the consistency integration test. ".repeat(3);
    gateway.respondWith("Data Architect", pad + "\nattr: region=us-east");
    gateway.respondWith("Infrastructure Engineer", pad + "\nattr: region=eu-west");

    HttpHeaders h = headers();
    String caseId = submitOpenAndStart(h);

    // The contradiction blocks the package: the case escalates instead of delivering.
    assertEquals(
        "Escalated",
        pollStatus(caseId, h, Duration.ofSeconds(150), "Escalated"),
        () -> "a deterministic contradiction should block delivery\n" + dump(caseId));

    // The finding is recorded and visible.
    List<Map<String, Object>> findings = listFindings(caseId, h);
    assertTrue(!findings.isEmpty(), "expected at least one consistency finding");
    Map<String, Object> deterministic =
        findings.stream()
            .filter(f -> "DETERMINISTIC".equals(f.get("tier")))
            .findFirst()
            .orElse(null);
    assertNotNull(deterministic, () -> "expected a DETERMINISTIC finding, got " + findings);
    assertEquals("ATTRIBUTE_CONTRADICTION", deterministic.get("kind"));

    // A deterministic contradiction is non-waivable → 409.
    String findingId = (String) deterministic.get("id");
    ResponseEntity<Void> waive =
        rest.exchange(
            url("/api/v1/cases/" + caseId + "/consistency-findings/" + findingId + "/resolve"),
            HttpMethod.POST,
            new HttpEntity<>(Map.of("resolution", "WAIVED", "rationale", "try to waive"), h),
            Void.class);
    assertEquals(
        409, waive.getStatusCode().value(), "waiving a deterministic finding must be rejected");

    // The contradiction produced a CONFLICTS_WITH edge between the two outputs (AD-7).
    assertTrue(conflictsWithEdges(caseId) >= 1, "expected a CONFLICTS_WITH trace_link edge");
  }

  // ---- helpers ----------------------------------------------------------------------------------

  private String submitOpenAndStart(HttpHeaders h) {
    ResponseEntity<Map> sub =
        rest.exchange(
            url("/api/v1/submissions"),
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of(
                    "formData", Map.of("category", "initiation", "description", "consistency IT")),
                h),
            Map.class);
    assertEquals(201, sub.getStatusCode().value());
    String submissionId = (String) sub.getBody().get("id");
    rest.exchange(
        url("/api/v1/submissions/" + submissionId + "/confirm-classification"),
        HttpMethod.POST,
        new HttpEntity<>(Map.of("confirmedCaseType", "initiation"), h),
        Map.class);
    ResponseEntity<Map> caseResp =
        rest.exchange(
            url("/api/v1/cases"),
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of("submissionId", submissionId, "featureId", featureId.toString()), h),
            Map.class);
    assertEquals(201, caseResp.getStatusCode().value(), () -> "case body: " + caseResp.getBody());
    String caseId = (String) caseResp.getBody().get("id");
    ResponseEntity<Void> start =
        rest.exchange(
            url("/api/v1/cases/" + caseId + "/start"),
            HttpMethod.POST,
            new HttpEntity<>(null, h),
            Void.class);
    assertEquals(202, start.getStatusCode().value());
    return caseId;
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> listFindings(String caseId, HttpHeaders h) {
    ResponseEntity<List> resp =
        rest.exchange(
            url("/api/v1/cases/" + caseId + "/consistency-findings"),
            HttpMethod.GET,
            new HttpEntity<>(h),
            List.class);
    assertEquals(200, resp.getStatusCode().value());
    return resp.getBody();
  }

  private String pollStatus(String caseId, HttpHeaders h, Duration timeout, String target)
      throws InterruptedException {
    Instant deadline = Instant.now().plus(timeout);
    String status = null;
    while (Instant.now().isBefore(deadline)) {
      ResponseEntity<Map> resp =
          rest.exchange(
              url("/api/v1/cases/" + caseId), HttpMethod.GET, new HttpEntity<>(h), Map.class);
      status = (String) resp.getBody().get("status");
      if (target.equals(status)) return status;
      if ("Cancelled".equals(status)) break;
      Thread.sleep(500);
    }
    return status;
  }

  private long deterministicOpenCount(String caseId) throws Exception {
    return scalarLong(
        "SELECT count(*) FROM consistency_finding WHERE case_id = '"
            + caseId
            + "' AND tier = 'DETERMINISTIC' AND status = 'OPEN'");
  }

  private long conflictsWithEdges(String caseId) throws Exception {
    // CONFLICTS_WITH links two operation_execution rows of this case.
    return scalarLong(
        "SELECT count(*) FROM trace_link tl WHERE tl.link_type = 'CONFLICTS_WITH' AND tl.from_id IN "
            + "(SELECT oe.id FROM operation_execution oe JOIN persona_invocation pi ON pi.id = oe.persona_invocation_id "
            + "WHERE pi.case_instance_id = '"
            + caseId
            + "')");
  }

  private long scalarLong(String sql) throws Exception {
    try (Connection conn = dataSource.getConnection()) {
      exec(conn, "SET app.workspace_id = '" + WORKSPACE_ID + "'");
      try (PreparedStatement ps = conn.prepareStatement(sql);
          ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getLong(1) : 0L;
      }
    }
  }

  private String dump(String caseId) {
    try {
      return "case="
          + scalarLong("SELECT 1")
          + " deterministicOpen="
          + deterministicOpenCount(caseId)
          + " findings="
          + scalarLong("SELECT count(*) FROM consistency_finding WHERE case_id='" + caseId + "'");
    } catch (Exception e) {
      return "dump failed: " + e.getMessage();
    }
  }

  private void exec(Connection conn, String sql) throws Exception {
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
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

  private HttpHeaders headers() {
    HttpHeaders h = new HttpHeaders();
    h.set("X-Workspace-Id", WORKSPACE_ID.toString());
    h.setContentType(MediaType.APPLICATION_JSON);
    return h;
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }
}
