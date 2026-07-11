package com.d2os.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.d2os.app.support.StubAiGatewayClient;
import com.d2os.projection.Projector;
import com.d2os.testsupport.ContainerFixtures;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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

/**
 * Cross-tenant leakage suite (T047/T048, US3, SC-003). Creates a Case in workspace ALPHA and proves
 * workspace BETA cannot read it through the API — RLS returns not-found, indistinguishable from a
 * genuinely absent record (contracts/api.yaml). Phase 2 (T046, SC-008) adds a concurrent
 * two-workspace scenario: two full Cases run their parallel specialist blocks at the same time, and
 * RLS still keeps each workspace blind to the other's runtime rows under genuine concurrency
 * (Principle IV, research R4).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StubAiGatewayClient.class)
class LeakageSuiteIT {

  @LocalServerPort int port;
  @Autowired TestRestTemplate rest;
  @Autowired DataSource dataSource;

  @Autowired
  @Qualifier("projectorDataSource")
  DataSource projectorDataSource;

  @Autowired JdbcTemplate jdbcTemplate;
  @Autowired Projector projector;

  private static final UUID ALPHA = UUID.randomUUID();
  private static final UUID BETA = UUID.randomUUID();
  private static UUID alphaFeatureId;
  private static UUID betaFeatureId;

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
    // T020 (Phase 7 US2): this class now also exercises Projector/the graph tables, which need
    // the d2os_projector-bound second datasource (ProjectorDataSourceConfig, T007) pointed at
    // the same Testcontainers instance — every other full-context Phase-7 IT (RebuildEquivalenceIT,
    // PayloadSufficiencyIT, ProjectionIdempotencyIT) already sets this; this class predates that
    // module and had not needed it until now.
    registry.add("spring.datasource.projector.url", () -> jdbcUrl);
    registry.add("d2os.storage.endpoint", () -> ContainerFixtures.MINIO.getS3URL());
    registry.add("d2os.storage.access-key", ContainerFixtures.MINIO::getUserName);
    registry.add("d2os.storage.secret-key", ContainerFixtures.MINIO::getPassword);
  }

  @BeforeEach
  void seed() throws Exception {
    seedWorkspace(ALPHA);
    alphaFeatureId = seedWorkspaceFeature(ALPHA);
    seedWorkspace(BETA);
    betaFeatureId = seedWorkspaceFeature(BETA);
  }

  @Test
  void betaCannotReadAlphasCase() {
    // Create a Case in ALPHA.
    HttpHeaders alpha = headers(ALPHA);
    String submissionId =
        (String)
            post("/api/v1/submissions", Map.of("formData", Map.of("category", "initiation")), alpha)
                .getBody()
                .get("id");
    post(
        "/api/v1/submissions/" + submissionId + "/confirm-classification",
        Map.of("confirmedCaseType", "initiation"),
        alpha);
    ResponseEntity<Map> caseResp =
        post(
            "/api/v1/cases",
            Map.of("submissionId", submissionId, "featureId", alphaFeatureId.toString()),
            alpha);
    assertEquals(201, caseResp.getStatusCode().value());
    String alphaCaseId = (String) caseResp.getBody().get("id");

    // ALPHA can read its own case.
    ResponseEntity<Map> ownRead =
        rest.exchange(
            url("/api/v1/cases/" + alphaCaseId),
            HttpMethod.GET,
            new HttpEntity<>(headers(ALPHA)),
            Map.class);
    assertEquals(200, ownRead.getStatusCode().value(), "ALPHA must see its own case");

    // BETA cannot — RLS hides it, surfaced as 404 (never 200 with data).
    ResponseEntity<Map> crossRead =
        rest.exchange(
            url("/api/v1/cases/" + alphaCaseId),
            HttpMethod.GET,
            new HttpEntity<>(headers(BETA)),
            Map.class);
    assertNotEquals(
        200,
        crossRead.getStatusCode().value(),
        "BETA must NOT be able to read ALPHA's case (SC-003)");
    assertEquals(404, crossRead.getStatusCode().value());
  }

  @Test
  void concurrentCasesInTwoWorkspacesDoNotLeakDuringParallelBlock() throws Exception {
    HttpHeaders alpha = headers(ALPHA), beta = headers(BETA);

    // Start both Cases; the engine's async executor runs their parallel specialist blocks
    // concurrently (no threads needed here — /start returns 202 and execution proceeds async).
    String alphaCase = submitOpenAndStart(alpha, alphaFeatureId);
    String betaCase = submitOpenAndStart(beta, betaFeatureId);

    // initiation-v3 (now the latest published initiation case_type) parks each run at an embedded
    // review-gate callActivity before delivery. This is a tenant-isolation test, so ALPHA's gate is
    // approved via ALPHA's own workspace context and BETA's via BETA's — never mixed — so the fix
    // itself doesn't introduce a cross-workspace leak or perturb the isolation assertions below.
    approveOpenGate(alphaCase, ALPHA, Duration.ofSeconds(120));
    approveOpenGate(betaCase, BETA, Duration.ofSeconds(120));

    assertEquals(
        "Delivered",
        poll(alphaCase, alpha, Duration.ofSeconds(200)),
        "ALPHA's case should deliver while BETA runs concurrently");
    assertEquals(
        "Delivered",
        poll(betaCase, beta, Duration.ofSeconds(200)),
        "BETA's case should deliver while ALPHA runs concurrently");

    // The two ran their parallel blocks at the same time; RLS still isolates their runtime rows.
    assertTrue(operationsVisibleUnder(ALPHA, alphaCase) > 0, "ALPHA must see its own operations");
    assertTrue(operationsVisibleUnder(BETA, betaCase) > 0, "BETA must see its own operations");
    assertEquals(
        0L,
        operationsVisibleUnder(ALPHA, betaCase),
        "ALPHA must NOT see BETA's operations even under concurrency (SC-008)");
    assertEquals(
        0L,
        operationsVisibleUnder(BETA, alphaCase),
        "BETA must NOT see ALPHA's operations even under concurrency (SC-008)");

    // API cross-read is still a clean 404, indistinguishable from an absent record.
    ResponseEntity<Map> crossRead =
        rest.exchange(
            url("/api/v1/cases/" + alphaCase), HttpMethod.GET, new HttpEntity<>(beta), Map.class);
    assertEquals(404, crossRead.getStatusCode().value(), "BETA must not read ALPHA's case");
  }

  /**
   * T020 (Phase 7 US2, SC-005): traceability/node queries never leak across workspaces, at any
   * traversal depth. Projects a small real CASE -&gt; SUBMISSION lineage into ALPHA via the same
   * {@link Projector} path {@code TraceabilityQueryIT} exercises, then asserts BETA gets a clean
   * 404 from both graph endpoints for ALPHA's node id (API level) AND that a direct SQL query bound
   * to BETA's RLS session returns ZERO rows for that id even when the query ALSO carries an
   * explicit {@code workspace_id = ALPHA} predicate (SQL level) &mdash; proving RLS itself blocks
   * the row regardless of what an explicit predicate elsewhere in the query claims, exactly the
   * "RLS + explicit predicate both hold" defense-in-depth {@link
   * com.d2os.projection.query.TraceabilityQueryService} relies on (Principle IV).
   */
  @Test
  void graphQueriesDoNotLeakAcrossWorkspacesRegardlessOfDepth() throws Exception {
    UUID submissionId = UUID.randomUUID();
    UUID caseId = UUID.randomUUID();
    try (Connection c = dataSource.getConnection()) {
      exec(c, "SET app.workspace_id = '" + ALPHA + "'");
      ins(
          c,
          "INSERT INTO problem_submission (id, workspace_id, form_data, created_by) "
              + "VALUES (?, ?, '{}'::jsonb, ?)",
          submissionId,
          ALPHA,
          "test");
      ins(
          c,
          "INSERT INTO case_instance (id, workspace_id, feature_id, submission_id, case_type_key, "
              + "case_type_version, mode, status, token_budget, created_by) "
              + "VALUES (?, ?, ?, ?, 'initiation', '1.0.0', 'mutating', 'Delivered', 1000, 'test')",
          caseId,
          ALPHA,
          alphaFeatureId,
          submissionId);
      ins(
          c,
          "INSERT INTO event_outbox (id, workspace_id, aggregate_type, aggregate_id, event_type, payload) "
              + "VALUES (?, ?, 'case_instance', ?, 'Delivered', '{}'::jsonb)",
          UUID.randomUUID(),
          ALPHA,
          caseId);
      ins(
          c,
          "INSERT INTO trace_link (id, workspace_id, from_type, from_id, to_type, to_id, link_type) "
              + "VALUES (?, ?, 'case_instance', ?, 'problem_submission', ?, 'DERIVES_FROM')",
          UUID.randomUUID(),
          ALPHA,
          caseId,
          submissionId);
    }
    projector.sweep(); // sweeps every active workspace (ALPHA and BETA both), builds generation 0

    UUID caseNodeId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM graph_node WHERE workspace_id = ? AND generation = 0 "
                + "AND node_type = 'CASE' AND natural_key = ?",
            UUID.class,
            ALPHA,
            caseId.toString());

    // --- API level ---
    HttpHeaders betaHeaders = headers(BETA);
    ResponseEntity<Map> crossTraceability =
        rest.exchange(
            url(
                "/api/v1/graph/traceability?nodeType=CASE&naturalKey="
                    + caseId
                    + "&relation=TRACES_TO&direction=BOTH&maxDepth=20"),
            HttpMethod.GET,
            new HttpEntity<>(betaHeaders),
            Map.class);
    assertEquals(
        404,
        crossTraceability.getStatusCode().value(),
        "BETA must not be able to start a traceability query from ALPHA's CASE node (SC-005)");

    ResponseEntity<Map> crossNode =
        rest.exchange(
            url("/api/v1/graph/nodes/" + caseNodeId),
            HttpMethod.GET,
            new HttpEntity<>(betaHeaders),
            Map.class);
    assertEquals(
        404,
        crossNode.getStatusCode().value(),
        "BETA must not be able to read ALPHA's graph_node by id (SC-005)");

    HttpHeaders alphaHeaders = headers(ALPHA);
    ResponseEntity<Map> ownTraceability =
        rest.exchange(
            url(
                "/api/v1/graph/traceability?nodeType=CASE&naturalKey="
                    + caseId
                    + "&relation=TRACES_TO&direction=BOTH&maxDepth=20"),
            HttpMethod.GET,
            new HttpEntity<>(alphaHeaders),
            Map.class);
    assertEquals(200, ownTraceability.getStatusCode().value(), "ALPHA must see its own graph");

    // --- SQL level: RLS + the explicit workspace_id predicate both hold ---
    assertEquals(
        1L,
        countGraphNodeVisible(ALPHA, ALPHA, caseNodeId),
        "ALPHA sees its own graph_node row (session + predicate agree)");
    assertEquals(
        0L,
        countGraphNodeVisible(BETA, BETA, caseNodeId),
        "BETA sees zero of ALPHA's graph_node rows under its own session+predicate");
    assertEquals(
        0L,
        countGraphNodeVisible(BETA, ALPHA, caseNodeId),
        "RLS alone blocks the row even when the query's OWN predicate claims workspace_id = ALPHA "
            + "while the session is bound to BETA (SC-005, Principle IV)");
    assertTrue(
        countGraphEdgesTouchingNode(ALPHA, caseNodeId) > 0,
        "ALPHA has a real projected edge (CASE -DERIVES_FROM-> SUBMISSION) to compare against");
    assertEquals(
        0L,
        countGraphEdgesTouchingNode(BETA, caseNodeId),
        "BETA must see ZERO of ALPHA's graph_edge rows regardless of traversal depth (SC-005)");
  }

  /**
   * Bound to {@code sessionWs}'s RLS session; the query ALSO carries an explicit {@code
   * workspace_id = predicateWs} predicate.
   */
  private long countGraphNodeVisible(UUID sessionWs, UUID predicateWs, UUID nodeId)
      throws Exception {
    try (Connection c = dataSource.getConnection()) {
      exec(c, "SET app.workspace_id = '" + sessionWs + "'");
      try (PreparedStatement ps =
          c.prepareStatement("SELECT count(*) FROM graph_node WHERE id = ? AND workspace_id = ?")) {
        ps.setObject(1, nodeId);
        ps.setObject(2, predicateWs);
        try (ResultSet rs = ps.executeQuery()) {
          return rs.next() ? rs.getLong(1) : 0L;
        }
      }
    }
  }

  private long countGraphEdgesTouchingNode(UUID sessionWs, UUID nodeId) throws Exception {
    try (Connection c = dataSource.getConnection()) {
      exec(c, "SET app.workspace_id = '" + sessionWs + "'");
      try (PreparedStatement ps =
          c.prepareStatement(
              "SELECT count(*) FROM graph_edge WHERE from_node = ? OR to_node = ?")) {
        ps.setObject(1, nodeId);
        ps.setObject(2, nodeId);
        try (ResultSet rs = ps.executeQuery()) {
          return rs.next() ? rs.getLong(1) : 0L;
        }
      }
    }
  }

  private String submitOpenAndStart(HttpHeaders h, UUID feature) {
    String submissionId =
        (String)
            post("/api/v1/submissions", Map.of("formData", Map.of("category", "initiation")), h)
                .getBody()
                .get("id");
    post(
        "/api/v1/submissions/" + submissionId + "/confirm-classification",
        Map.of("confirmedCaseType", "initiation"),
        h);
    String caseId =
        (String)
            post(
                    "/api/v1/cases",
                    Map.of("submissionId", submissionId, "featureId", feature.toString()),
                    h)
                .getBody()
                .get("id");
    rest.exchange(
        url("/api/v1/cases/" + caseId + "/start"),
        HttpMethod.POST,
        new HttpEntity<>(null, h),
        Void.class);
    return caseId;
  }

  /**
   * Poll {@code GET /api/v1/gates?caseId=} (the same worklist endpoint {@code GateFlowIT} drives)
   * until an OPEN gate appears for this case, then APPROVE it via the real {@code POST
   * /api/v1/gates/{gateId}/decision} endpoint — using {@code ws}'s own workspace headers so a
   * cross-workspace call is never made, which matters especially in this leakage suite. {@code
   * reviewer-1} is not the case's own submitter (every {@code POST /api/v1/cases} records {@code
   * createdBy = "api"}, per CaseController), so this doesn't trip GateService's non-self-review
   * guard.
   */
  private void approveOpenGate(String caseId, UUID ws, Duration timeout)
      throws InterruptedException {
    HttpHeaders h = headers(ws);
    Instant deadline = Instant.now().plus(timeout);
    while (Instant.now().isBefore(deadline)) {
      List<Map<String, Object>> openGates = listOpenGates(caseId, h);
      if (!openGates.isEmpty()) {
        String gateId = (String) openGates.get(0).get("id");
        decide(gateId, ws, "reviewer-1", "APPROVE");
        return;
      }
      Thread.sleep(500);
    }
    fail("review-gate did not open for case " + caseId + " within " + timeout);
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> listOpenGates(String caseId, HttpHeaders h) {
    ResponseEntity<List> resp =
        rest.exchange(
            url("/api/v1/gates?caseId=" + caseId), HttpMethod.GET, new HttpEntity<>(h), List.class);
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

  private void decide(String gateId, UUID ws, String actor, String verb) {
    HttpHeaders h = headers(ws);
    h.set("X-Actor", actor);
    ResponseEntity<Map> resp =
        rest.exchange(
            url("/api/v1/gates/" + gateId + "/decision"),
            HttpMethod.POST,
            new HttpEntity<>(Map.of("verb", verb), h),
            Map.class);
    assertEquals(
        200, resp.getStatusCode().value(), () -> "gate decision failed: " + resp.getBody());
  }

  private String poll(String caseId, HttpHeaders h, Duration timeout) throws InterruptedException {
    Instant deadline = Instant.now().plus(timeout);
    String status = null;
    while (Instant.now().isBefore(deadline)) {
      ResponseEntity<Map> r =
          rest.exchange(
              url("/api/v1/cases/" + caseId), HttpMethod.GET, new HttpEntity<>(h), Map.class);
      status = (String) r.getBody().get("status");
      if ("Delivered".equals(status) || "Escalated".equals(status) || "Suspended".equals(status))
        return status;
      Thread.sleep(500);
    }
    return status;
  }

  /**
   * Count operation_execution rows for {@code caseId} that are visible when RLS is bound to {@code
   * ws}.
   */
  private long operationsVisibleUnder(UUID ws, String caseId) throws Exception {
    try (Connection c = dataSource.getConnection()) {
      exec(c, "SET app.workspace_id = '" + ws + "'");
      String sql =
          "SELECT count(*) FROM operation_execution oe "
              + "JOIN persona_invocation pi ON pi.id = oe.persona_invocation_id "
              + "WHERE pi.case_instance_id = '"
              + caseId
              + "'";
      try (PreparedStatement ps = c.prepareStatement(sql);
          ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getLong(1) : 0L;
      }
    }
  }

  private ResponseEntity<Map> post(String path, Object body, HttpHeaders headers) {
    return rest.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
  }

  private HttpHeaders headers(UUID ws) {
    HttpHeaders h = new HttpHeaders();
    h.set("X-Workspace-Id", ws.toString());
    h.setContentType(MediaType.APPLICATION_JSON);
    return h;
  }

  private void seedWorkspace(UUID ws) throws Exception {
    try (Connection c = dataSource.getConnection()) {
      exec(c, "SET app.workspace_id = '" + ws + "'");
      ins(
          c,
          "INSERT INTO workspace (id, name, created_by) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
          ws,
          "ws-" + ws,
          "test");
    }
  }

  private UUID seedWorkspaceFeature(UUID ws) throws Exception {
    UUID projectId = UUID.randomUUID(),
        versionId = UUID.randomUUID(),
        featureId = UUID.randomUUID();
    try (Connection c = dataSource.getConnection()) {
      exec(c, "SET app.workspace_id = '" + ws + "'");
      ins(
          c,
          "INSERT INTO project (id, workspace_id, name, created_by) VALUES (?, ?, ?, ?)",
          projectId,
          ws,
          "p",
          "test");
      ins(
          c,
          "INSERT INTO project_version (id, workspace_id, project_id, label, created_by) VALUES (?, ?, ?, ?, ?)",
          versionId,
          ws,
          projectId,
          "v1",
          "test");
      ins(
          c,
          "INSERT INTO feature (id, workspace_id, project_version_id, name, created_by) VALUES (?, ?, ?, ?, ?)",
          featureId,
          ws,
          versionId,
          "f",
          "test");
    }
    return featureId;
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

  private String url(String path) {
    return "http://localhost:" + port + path;
  }
}
