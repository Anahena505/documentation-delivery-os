package com.d2os.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.d2os.testsupport.ContainerFixtures;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
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

/**
 * Deprecation impact, deprecate-with-report-id, fork provenance, and the compatibility matrix
 * (T024, US3, research R4/R5, FR-010/011/012/017, SC-005, SC-008). Seeds a real pinned case, a
 * dependent definition, and a subscription copy directly (targeted policy test, mirroring {@code
 * ReopenPolicyIT}'s style — not a full case pipeline run).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LifecycleToolingIT {

  @LocalServerPort int port;
  @Autowired TestRestTemplate rest;
  @Autowired DataSource dataSource;
  @Autowired JdbcTemplate jdbcTemplate;

  private static final UUID WORKSPACE_ID = UUID.randomUUID();
  private UUID targetDefinitionId;
  private UUID caseId;

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
  }

  @BeforeEach
  void seed() throws Exception {
    UUID projectId = UUID.randomUUID(),
        versionId = UUID.randomUUID(),
        featureId = UUID.randomUUID();
    UUID submissionId = UUID.randomUUID();
    caseId = UUID.randomUUID();
    targetDefinitionId = UUID.randomUUID();
    UUID dependentDefinitionId = UUID.randomUUID();
    UUID subscriberWorkspace = UUID.randomUUID();
    UUID copiedDefinitionId = UUID.randomUUID();

    try (Connection c = dataSource.getConnection()) {
      exec(c, "SET app.workspace_id = '" + WORKSPACE_ID + "'");
      ins(
          c,
          "INSERT INTO workspace (id,name,created_by) VALUES (?,?,?) ON CONFLICT DO NOTHING",
          WORKSPACE_ID,
          "ws",
          "t");
      ins(
          c,
          "INSERT INTO project (id,workspace_id,name,created_by) VALUES (?,?,?,?)",
          projectId,
          WORKSPACE_ID,
          "p",
          "t");
      ins(
          c,
          "INSERT INTO project_version (id,workspace_id,project_id,label,created_by) VALUES (?,?,?,?,?)",
          versionId,
          WORKSPACE_ID,
          projectId,
          "v1",
          "t");
      ins(
          c,
          "INSERT INTO feature (id,workspace_id,project_version_id,name,created_by) VALUES (?,?,?,?,?)",
          featureId,
          WORKSPACE_ID,
          versionId,
          "f",
          "t");
      ins(
          c,
          "INSERT INTO problem_submission (id,workspace_id,form_data,created_by) VALUES (?,?,'{}'::jsonb,?)",
          submissionId,
          WORKSPACE_ID,
          "t");
      ins(
          c,
          "INSERT INTO case_instance (id,workspace_id,feature_id,submission_id,case_type_key,case_type_version,mode,status,token_budget,created_by) "
              + "VALUES (?,?,?,?,'initiation','1.0.0','mutating','Running',1000,'t')",
          caseId,
          WORKSPACE_ID,
          featureId,
          submissionId);

      // The target Published definition X v1.0.0 (LifecycleToolingIT-owned key).
      ins(
          c,
          "INSERT INTO definition_asset (id,workspace_id,key,version,type,status,locale,body,checksum,created_by) "
              + "VALUES (?,?,?,?,?,?,?,?,?,?)",
          targetDefinitionId,
          WORKSPACE_ID,
          "lifecycle-test-target",
          "1.0.0",
          "template",
          "Published",
          "en",
          "{\"name\":\"target\"}",
          "hashx",
          "test");

      // A pinned Case: its snapshot entries exact-pin X v1.0.0.
      ins(
          c,
          "INSERT INTO case_definition_snapshot (id,workspace_id,case_instance_id,entries) VALUES (?,?,?,?::jsonb)",
          UUID.randomUUID(),
          WORKSPACE_ID,
          caseId,
          "[{\"type\":\"template\",\"key\":\"lifecycle-test-target\",\"version\":\"1.0.0\"}]");

      // A dependent Published definition whose dependsOn names X.
      ins(
          c,
          "INSERT INTO definition_asset (id,workspace_id,key,version,type,status,locale,body,checksum,created_by) "
              + "VALUES (?,?,?,?,?,?,?,?,?,?)",
          dependentDefinitionId,
          WORKSPACE_ID,
          "lifecycle-test-dependent",
          "1.0.0",
          "case_type",
          "Published",
          "en",
          "{\"dependsOn\":[\"template:lifecycle-test-target\"]}",
          "hashy",
          "test");

      // A subscription copy in another workspace.
      ins(
          c,
          "INSERT INTO workspace (id,name,created_by) VALUES (?,?,?) ON CONFLICT DO NOTHING",
          subscriberWorkspace,
          "sub-ws",
          "t");
      ins(
          c,
          "INSERT INTO definition_asset (id,workspace_id,key,version,type,status,locale,body,checksum,copied_from_id,created_by) "
              + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
          copiedDefinitionId,
          subscriberWorkspace,
          "lifecycle-test-target",
          "1.0.0",
          "template",
          "Published",
          "en",
          "{\"name\":\"target\"}",
          "hashx",
          targetDefinitionId,
          "test");
      ins(
          c,
          "INSERT INTO library_subscription (id,workspace_id,source_definition_id,copied_definition_id,subscribed_by) "
              + "VALUES (?,?,?,?,?)",
          UUID.randomUUID(),
          subscriberWorkspace,
          targetDefinitionId,
          copiedDefinitionId,
          "test");
    }
  }

  @Test
  void deprecationImpactReportListsEveryDependent() {
    HttpHeaders h = headers();
    ResponseEntity<Map> resp =
        rest.exchange(
            url("/api/v1/catalog/definitions/" + targetDefinitionId + "/deprecation-impact"),
            HttpMethod.GET,
            new HttpEntity<>(h),
            Map.class);
    assertEquals(200, resp.getStatusCode().value());
    Map<String, Object> body = resp.getBody();
    assertNotEquals(null, body.get("impactReportId"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> pinned = (List<Map<String, Object>>) body.get("pinnedActiveCases");
    assertEquals(1, pinned.size(), "the pinned Running case must appear (zero omissions)");
    assertEquals(caseId.toString(), pinned.get(0).get("caseInstanceId"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> dependents = (List<Map<String, Object>>) body.get("dependents");
    assertEquals(1, dependents.size());
    assertEquals("lifecycle-test-dependent", dependents.get(0).get("key"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> copies = (List<Map<String, Object>>) body.get("subscriptionCopies");
    assertEquals(1, copies.size());
  }

  @Test
  void deprecateRequiresAFreshImpactReportIdAndKeepsThePinnedCaseRunning() {
    HttpHeaders h = headers();

    ResponseEntity<Map> without =
        rest.exchange(
            url("/api/v1/catalog/definitions/" + targetDefinitionId + "/deprecate"),
            HttpMethod.POST,
            new HttpEntity<>(Map.of(), h),
            Map.class);
    assertEquals(
        409, without.getStatusCode().value(), "deprecate without an impactReportId must 409");

    ResponseEntity<Map> with =
        rest.exchange(
            url("/api/v1/catalog/definitions/" + targetDefinitionId + "/deprecate"),
            HttpMethod.POST,
            new HttpEntity<>(Map.of("impactReportId", UUID.randomUUID().toString()), h),
            Map.class);
    assertEquals(200, with.getStatusCode().value());
    assertEquals("Deprecated", with.getBody().get("status"));

    // The pinned case's own snapshot and status are completely untouched (Principle I).
    String caseStatus =
        jdbcTemplate.queryForObject(
            "SELECT status FROM case_instance WHERE id = ?", String.class, caseId);
    assertEquals(
        "Running",
        caseStatus,
        "deprecating an upstream definition must never affect a case already pinned to it");

    long auditRows =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM audit_entry WHERE subject_id = ? AND action = 'DEFINITION_DEPRECATED'",
            Long.class,
            targetDefinitionId);
    assertTrue(auditRows >= 1, "deprecation must be an audited event (FR-017)");
  }

  @Test
  void forkingADeprecatedSourceYieldsAnIndependentDraft() throws Exception {
    // First deprecate the source (forking a Deprecated definition is explicitly allowed, FR-012).
    try (Connection c = dataSource.getConnection()) {
      exec(c, "SET app.workspace_id = '" + WORKSPACE_ID + "'");
      exec(
          c,
          "UPDATE definition_asset SET status = 'Deprecated' WHERE id = '"
              + targetDefinitionId
              + "'");
    }

    HttpHeaders h = headers();
    ResponseEntity<Map> forked =
        rest.exchange(
            url("/api/v1/catalog/definitions/" + targetDefinitionId + "/fork"),
            HttpMethod.POST,
            new HttpEntity<>(Map.of("newVersion", "2.0.0"), h),
            Map.class);
    assertEquals(201, forked.getStatusCode().value(), () -> "fork failed: " + forked.getBody());

    String newId = (String) forked.getBody().get("id");
    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT status, derived_from_id, version FROM definition_asset WHERE id = ?",
            UUID.fromString(newId));
    assertEquals("Draft", row.get("status"), "a fork is an independent, unpublished Draft");
    assertEquals(targetDefinitionId.toString(), row.get("derived_from_id").toString());
    assertEquals("2.0.0", row.get("version"));

    long auditRows =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM audit_entry WHERE subject_id = ? AND action = 'DEFINITION_FORKED'",
            Long.class,
            UUID.fromString(newId));
    assertTrue(auditRows >= 1, "forking must be an audited event (FR-017)");
  }

  @Test
  void compatibilityMatrixFlagsAnOutOfRangePin() throws Exception {
    UUID declaringDefinitionId = UUID.randomUUID();
    try (Connection c = dataSource.getConnection()) {
      exec(c, "SET app.workspace_id = '" + WORKSPACE_ID + "'");
      // Declares compatibility with lifecycle-test-target only up to <1.0.0 — X is 1.0.0, so
      // this must be flagged out-of-range.
      ins(
          c,
          "INSERT INTO definition_asset (id,workspace_id,key,version,type,status,locale,body,checksum,created_by) "
              + "VALUES (?,?,?,?,?,?,?,?,?,?)",
          declaringDefinitionId,
          WORKSPACE_ID,
          "lifecycle-test-declarer",
          "1.0.0",
          "case_type",
          "Published",
          "en",
          "{\"compatibleWith\":[{\"type\":\"template\",\"key\":\"lifecycle-test-target\",\"range\":\">=0.1.0 <1.0.0\"}]}",
          "hashz",
          "test");
    }

    HttpHeaders h = headers();
    ResponseEntity<List> resp =
        rest.exchange(
            url("/api/v1/catalog/compatibility-matrix"),
            HttpMethod.GET,
            new HttpEntity<>(h),
            List.class);
    assertEquals(200, resp.getStatusCode().value());

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> matrix = (List<Map<String, Object>>) (List<?>) resp.getBody();
    Map<String, Object> entry =
        matrix.stream()
            .filter(e -> "lifecycle-test-declarer".equals(e.get("definitionKey")))
            .findFirst()
            .orElseThrow(
                () -> new AssertionError("expected matrix entry for lifecycle-test-declarer"));
    assertFalse((Boolean) entry.get("inRange"), "1.0.0 is out of the declared <1.0.0 range");
    assertEquals("1.0.0", entry.get("resolvedVersion"));
  }

  private void exec(Connection c, String sql) throws Exception {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.execute();
    }
  }

  private void ins(Connection c, String sql, Object... params) throws Exception {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        if (params[i] instanceof UUID u) ps.setObject(i + 1, u);
        else ps.setString(i + 1, params[i].toString());
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
