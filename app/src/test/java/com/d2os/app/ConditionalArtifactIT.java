package com.d2os.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.d2os.app.support.StubAiGatewayClient;
import com.d2os.artifacts.PackageAssemblyService;
import com.d2os.tenancy.WorkspaceContext;
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
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Conditional artifacts, the zero-schema-change extension (T035, US5, research R6, FR-014/015,
 * SC-006). {@code personal_data=true} on a submission's form data adds {@code template.dpia}
 * (artifact kind {@code DPIA}) to the pinned expected-artifact set as a CONDITIONAL entry, on top
 * of the case type's normal BASE templates — and {@code PackageAssemblyService}'s completeness gate
 * (T033) refuses to assemble a package while it is unfulfilled. Without the flag, only the BASE set
 * is required.
 *
 * <p>No persona in this codebase currently produces a {@code DPIA} artifact (a documented scope
 * boundary of this phase — wiring one into a workflow is out of scope for the zero-schema-change
 * extension mechanism itself), so the {@code personal_data=true} scenario is asserted at the
 * mechanism level: the requirement is correctly pinned and surfaced, and assembly is refused for as
 * long as it stays unfulfilled — never driven all the way to a Delivered case that could never
 * actually happen.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StubAiGatewayClient.class)
class ConditionalArtifactIT {

  @LocalServerPort int port;
  @Autowired TestRestTemplate rest;
  @Autowired DataSource dataSource;
  @Autowired PackageAssemblyService packageAssemblyService;

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
  }

  @BeforeEach
  void seed() throws Exception {
    UUID projectId = UUID.randomUUID(), versionId = UUID.randomUUID();
    featureId = UUID.randomUUID();
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
    }
  }

  @Test
  void personalDataFlagPinsAConditionalDpiaRequirementAndBlocksDelivery() throws Exception {
    HttpHeaders h = headers();
    String submissionId =
        (String)
            post(
                    "/api/v1/submissions",
                    Map.of("formData", Map.of("category", "assessment", "personalData", true)),
                    h)
                .getBody()
                .get("id");
    post(
        "/api/v1/submissions/" + submissionId + "/confirm-classification",
        Map.of("confirmedCaseType", "assessment"),
        h);
    String caseId =
        (String)
            post(
                    "/api/v1/cases",
                    Map.of("submissionId", submissionId, "featureId", featureId.toString()),
                    h)
                .getBody()
                .get("id");

    List<Map<String, Object>> required = requiredArtifacts(caseId, h);
    List<Map<String, Object>> conditional =
        required.stream().filter(e -> "CONDITIONAL".equals(e.get("source"))).toList();
    assertEquals(
        1,
        conditional.size(),
        "exactly one CONDITIONAL requirement from the personal_data=true rule");
    assertEquals("template.dpia", conditional.get(0).get("templateKey"));
    assertEquals("DPIA", conditional.get(0).get("artifactKind"));
    assertEquals("personal_data=true", conditional.get(0).get("conditionalReason"));
    assertFalse(
        (Boolean) conditional.get(0).get("fulfilled"), "no persona has produced a DPIA yet");

    long baseCount = required.stream().filter(e -> "BASE".equals(e.get("source"))).count();
    assertEquals(
        2, baseCount, "Assessment's two package-facing templates (findings + recommendation)");

    WorkspaceContext.set(WORKSPACE_ID);
    try {
      assertThrows(
          IllegalStateException.class,
          () -> packageAssemblyService.assemble(WORKSPACE_ID, UUID.fromString(caseId)),
          "package assembly must refuse while the CONDITIONAL DPIA requirement is unfulfilled (T033)");
    } finally {
      WorkspaceContext.clear();
    }
  }

  @Test
  void withoutThePersonalDataFlagOnlyTheBaseSetIsRequired() throws Exception {
    HttpHeaders h = headers();
    String submissionId =
        (String)
            post("/api/v1/submissions", Map.of("formData", Map.of("category", "assessment")), h)
                .getBody()
                .get("id");
    post(
        "/api/v1/submissions/" + submissionId + "/confirm-classification",
        Map.of("confirmedCaseType", "assessment"),
        h);
    String caseId =
        (String)
            post(
                    "/api/v1/cases",
                    Map.of("submissionId", submissionId, "featureId", featureId.toString()),
                    h)
                .getBody()
                .get("id");

    List<Map<String, Object>> required = requiredArtifacts(caseId, h);
    assertTrue(
        required.stream().noneMatch(e -> "CONDITIONAL".equals(e.get("source"))),
        "personal_data absent/false must add zero CONDITIONAL requirements (SC-006)");
    assertEquals(2, required.size(), "the BASE set alone: findings + recommendation");
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> requiredArtifacts(String caseId, HttpHeaders h) {
    ResponseEntity<List> resp =
        rest.exchange(
            url("/api/v1/cases/" + caseId + "/required-artifacts"),
            HttpMethod.GET,
            new HttpEntity<>(h),
            List.class);
    assertEquals(200, resp.getStatusCode().value());
    return (List<Map<String, Object>>) (List<?>) resp.getBody();
  }

  private ResponseEntity<Map> post(String path, Object body, HttpHeaders headers) {
    return rest.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
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
