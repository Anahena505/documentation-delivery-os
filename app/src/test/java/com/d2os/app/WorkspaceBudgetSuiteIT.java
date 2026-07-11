package com.d2os.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.d2os.app.support.StubAiGatewayClient;
import com.d2os.testsupport.ContainerFixtures;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.test.context.TestPropertySource;

/**
 * Workspace-budget suspension (T047, US4, FR-017, SC-008). The per-Case budget is generous, but the
 * <em>workspace</em> ceiling is set below one AI call's estimate — so the budget guard must suspend
 * the offending Case before the first call, proving the per-workspace rollup can halt a Case even
 * when its own per-Case budget is fine. The workspace-cap variant of the token-budget guarantee
 * (co-located with {@code TokenBudgetSuiteIT}'s per-Case variant but a separate class, since the
 * per-Case budget is set class-wide via {@code @TestPropertySource}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StubAiGatewayClient.class)
@TestPropertySource(
    properties = "d2os.case.default-token-budget=1000000") // per-Case budget never the cause
class WorkspaceBudgetSuiteIT {

  @LocalServerPort int port;
  @Autowired TestRestTemplate rest;
  @Autowired DataSource dataSource;

  private static final UUID WORKSPACE_ID = UUID.randomUUID();
  private static UUID featureId;

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
      // A workspace ceiling below one call's estimate (~2000 tokens) — the guard must halt before
      // the call.
      ins(
          c,
          "INSERT INTO workspace_budget (workspace_id, token_cap) VALUES (?, ?) "
              + "ON CONFLICT (workspace_id) DO UPDATE SET token_cap = EXCLUDED.token_cap",
          WORKSPACE_ID,
          100L);
    }
  }

  @Test
  void caseExceedingWorkspaceCapIsSuspended() throws Exception {
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Workspace-Id", WORKSPACE_ID.toString());
    headers.setContentType(MediaType.APPLICATION_JSON);

    String submissionId =
        (String)
            post(
                    "/api/v1/submissions",
                    Map.of("formData", Map.of("category", "initiation")),
                    headers)
                .getBody()
                .get("id");
    post(
        "/api/v1/submissions/" + submissionId + "/confirm-classification",
        Map.of("confirmedCaseType", "initiation"),
        headers);
    String caseId =
        (String)
            post(
                    "/api/v1/cases",
                    Map.of("submissionId", submissionId, "featureId", featureId.toString()),
                    headers)
                .getBody()
                .get("id");
    rest.exchange(
        url("/api/v1/cases/" + caseId + "/start"),
        HttpMethod.POST,
        new HttpEntity<>(null, headers),
        Void.class);

    String status = pollUntilTerminal(caseId, headers, Duration.ofSeconds(60));
    assertEquals(
        "Suspended",
        status,
        "a Case over its WORKSPACE token cap must Suspend, even with a generous per-Case budget (FR-017)");
  }

  private String pollUntilTerminal(String caseId, HttpHeaders headers, Duration timeout)
      throws InterruptedException {
    Instant deadline = Instant.now().plus(timeout);
    while (Instant.now().isBefore(deadline)) {
      ResponseEntity<Map> r =
          rest.exchange(
              url("/api/v1/cases/" + caseId), HttpMethod.GET, new HttpEntity<>(headers), Map.class);
      String status = (String) r.getBody().get("status");
      if ("Delivered".equals(status) || "Suspended".equals(status) || "Escalated".equals(status))
        return status;
      Thread.sleep(500);
    }
    fail("case did not reach terminal state within " + timeout);
    return null;
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
        else if (params[i] instanceof Number n)
          ps.setObject(i + 1, n); // numeric columns (e.g. token_cap bigint)
        else ps.setString(i + 1, params[i].toString());
      }
      ps.executeUpdate();
    }
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }
}
