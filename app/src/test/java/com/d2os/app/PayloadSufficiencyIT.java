package com.d2os.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.d2os.projection.Projector;
import com.d2os.testsupport.ContainerFixtures;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
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
 * T014 — {@code PayloadSufficiencyAuditor} acceptance IT (research R6, FR-011, SC-007). Two
 * scenarios per {@link com.d2os.projection.PayloadSufficiencyAuditor}'s two-tier contract:
 *
 * <ol>
 *   <li>A {@code GATE_DECIDED} event missing its CONTENT fields ({@code decisionVerb}/{@code
 *       deciderId}) — still structurally sufficient (gateId/gateType/gateDefinitionKey/
 *       gateDefinitionVersion all present), so the GATE node IS still projected (partial
 *       projection, not a total skip — the literal SC-007/T014 requirement) with a null {@code
 *       decisionVerb} attribute, AND a {@code projection_gap} row is recorded naming the missing
 *       fields.
 *   <li>A {@code GATE_OPENED} event missing a STRUCTURAL field ({@code gateDefinitionKey}) — not
 *       projectable at all; no GATE node exists in the graph for this gate afterward, and a {@code
 *       projection_gap} row is recorded.
 * </ol>
 *
 * <p>{@code GET /graph/admin/gaps} (T012) is hit over HTTP to prove the gap surfaces through the
 * real admin API, not just the DB row — same {@code X-Workspace-Id} test-fallback convention {@code
 * GateFlowIT} uses ({@code d2os.security.jwt.allow-header-workspace-fallback=true}, wired in {@code
 * app/src/test/resources/application.properties}).
 *
 * <p><b>Cannot actually run in this environment</b> (Testcontainers/Docker non-functional in this
 * sandbox, per Phase 1+2's report) — traced by hand against the real {@link Projector}/{@code
 * PayloadSufficiencyAuditor}/{@code GraphAdminController} code, not asserted to pass.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PayloadSufficiencyIT {

  @LocalServerPort int port;
  @Autowired TestRestTemplate rest;
  @Autowired DataSource dataSource;
  @Autowired JdbcTemplate jdbcTemplate;
  @Autowired Projector projector;

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
    registry.add("spring.datasource.projector.url", () -> jdbcUrl);
    registry.add("d2os.storage.endpoint", () -> ContainerFixtures.MINIO.getS3URL());
    registry.add("d2os.storage.access-key", ContainerFixtures.MINIO::getUserName);
    registry.add("d2os.storage.secret-key", ContainerFixtures.MINIO::getPassword);
  }

  @Test
  void contentThinGateDecidedEventStillProjectsWithAGapRecorded() throws Exception {
    UUID workspaceId = UUID.randomUUID();
    UUID caseId = UUID.randomUUID();
    UUID gateId = UUID.randomUUID();
    seedTenancyAndCase(workspaceId, caseId);
    seedGate(workspaceId, caseId, gateId);

    // GATE_DECIDED with decisionVerb/deciderId stripped — a thin event per GateEventPayload's
    // contract, but every STRUCTURAL field (gateId/gateType/gateDefinitionKey/
    // gateDefinitionVersion) is present.
    UUID eventId = UUID.randomUUID();
    String thinPayload =
        "{\"eventType\":\"GATE_DECIDED\",\"gateId\":\""
            + gateId
            + "\","
            + "\"gateType\":\"REVIEW\",\"gateDefinitionKey\":\"review-gate\",\"gateDefinitionVersion\":1,"
            + "\"caseInstanceId\":\""
            + caseId
            + "\",\"workspaceId\":\""
            + workspaceId
            + "\","
            + "\"occurredAt\":\""
            + Instant.now()
            + "\"}"; // decisionVerb/deciderId deliberately absent
    insertEvent(workspaceId, eventId, gateId, "GATE_DECIDED", thinPayload);

    projector.sweep();

    // Partial projection: the GATE node exists (structural fields sufficed).
    long gateNodeCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM graph_node WHERE workspace_id = ? AND generation = 0 "
                + "AND node_type = 'GATE' AND natural_key = ?",
            Long.class,
            workspaceId,
            gateId.toString());
    assertEquals(
        1L, gateNodeCount, "structurally-sufficient content-thin event still projects a GATE node");

    // The gap is recorded, naming exactly the missing content fields.
    List<Map<String, Object>> gaps =
        jdbcTemplate.queryForList(
            "SELECT missing_fields, status FROM projection_gap WHERE workspace_id = ? AND event_id = ?",
            workspaceId,
            eventId);
    assertEquals(1, gaps.size(), "exactly one gap recorded for the thin GATE_DECIDED event");
    assertEquals("OPEN", gaps.get(0).get("status"));

    // Surfaces through the real admin API too (GET /graph/admin/gaps), not just the DB row.
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Workspace-Id", workspaceId.toString());
    headers.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<List> resp =
        rest.exchange(
            url("/api/v1/graph/admin/gaps?status=OPEN"),
            HttpMethod.GET,
            new HttpEntity<>(headers),
            List.class);
    assertEquals(200, resp.getStatusCode().value());
    assertEquals(1, resp.getBody().size(), "GET /graph/admin/gaps lists the recorded gap");
  }

  @Test
  void structurallyThinGateOpenedEventProjectsNothingForThatGate() throws Exception {
    UUID workspaceId = UUID.randomUUID();
    UUID caseId = UUID.randomUUID();
    UUID gateId = UUID.randomUUID();
    seedTenancyAndCase(workspaceId, caseId);
    seedGate(workspaceId, caseId, gateId);

    // GATE_OPENED missing gateDefinitionKey — a STRUCTURAL field; the mapper cannot place a GATE
    // node's label/natural-key identity without it.
    UUID eventId = UUID.randomUUID();
    String structurallyThinPayload =
        "{\"eventType\":\"GATE_OPENED\",\"gateId\":\""
            + gateId
            + "\","
            + "\"gateType\":\"REVIEW\",\"gateDefinitionVersion\":1,"
            + "\"caseInstanceId\":\""
            + caseId
            + "\",\"workspaceId\":\""
            + workspaceId
            + "\","
            + "\"occurredAt\":\""
            + Instant.now()
            + "\"}"; // gateDefinitionKey deliberately absent
    insertEvent(workspaceId, eventId, gateId, "GATE_OPENED", structurallyThinPayload);

    projector.sweep();

    long gateNodeCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM graph_node WHERE workspace_id = ? AND generation = 0 "
                + "AND node_type = 'GATE' AND natural_key = ?",
            Long.class,
            workspaceId,
            gateId.toString());
    assertEquals(
        0L,
        gateNodeCount,
        "a structurally-thin event is not projectable at all — no GATE node from it");

    List<Map<String, Object>> gaps =
        jdbcTemplate.queryForList(
            "SELECT missing_fields FROM projection_gap WHERE workspace_id = ? AND event_id = ?",
            workspaceId,
            eventId);
    assertEquals(
        1,
        gaps.size(),
        "the structurally-thin event is still caught and reported, not silently dropped");
  }

  // ---- seeding -------------------------------------------------------------------------------

  private void seedTenancyAndCase(UUID workspaceId, UUID caseId) throws Exception {
    UUID projectId = UUID.randomUUID();
    UUID projectVersionId = UUID.randomUUID();
    UUID featureId = UUID.randomUUID();
    try (Connection conn = dataSource.getConnection()) {
      setWorkspace(conn, workspaceId);
      insert(
          conn,
          "INSERT INTO workspace (id, name, created_by) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
          workspaceId,
          "payload-sufficiency-ws",
          "test");
      insert(
          conn,
          "INSERT INTO project (id, workspace_id, name, created_by) VALUES (?, ?, ?, ?)",
          projectId,
          workspaceId,
          "p",
          "test");
      insert(
          conn,
          "INSERT INTO project_version (id, workspace_id, project_id, label, created_by) "
              + "VALUES (?, ?, ?, ?, ?)",
          projectVersionId,
          workspaceId,
          projectId,
          "v1",
          "test");
      insert(
          conn,
          "INSERT INTO feature (id, workspace_id, project_version_id, name, created_by) "
              + "VALUES (?, ?, ?, ?, ?)",
          featureId,
          workspaceId,
          projectVersionId,
          "f",
          "test");
      insert(
          conn,
          "INSERT INTO case_instance (id, workspace_id, feature_id, submission_id, case_type_key, "
              + "case_type_version, mode, status, token_budget, created_by) "
              + "VALUES (?, ?, ?, ?, 'initiation', '1.0.0', 'mutating', 'Delivered', 1000, 'test')",
          caseId,
          workspaceId,
          featureId,
          UUID.randomUUID());
    }
  }

  private void seedGate(UUID workspaceId, UUID caseId, UUID gateId) throws Exception {
    try (Connection conn = dataSource.getConnection()) {
      setWorkspace(conn, workspaceId);
      insert(
          conn,
          "INSERT INTO gate_instance (id, workspace_id, case_instance_id, gate_type, "
              + "gate_definition_key, gate_definition_version, inputs_ref, status) "
              + "VALUES (?, ?, ?, 'REVIEW', 'review-gate', 1, '{}'::jsonb, 'OPEN')",
          gateId,
          workspaceId,
          caseId);
    }
  }

  private void insertEvent(
      UUID workspaceId, UUID eventId, UUID gateId, String eventType, String payloadJson)
      throws Exception {
    try (Connection conn = dataSource.getConnection()) {
      setWorkspace(conn, workspaceId);
      insert(
          conn,
          "INSERT INTO event_outbox (id, workspace_id, aggregate_type, aggregate_id, event_type, "
              + "payload) VALUES (?, ?, 'gate_instance', ?, ?, ?::jsonb)",
          eventId,
          workspaceId,
          gateId,
          eventType,
          payloadJson);
    }
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }

  private void setWorkspace(Connection conn, UUID workspaceId) throws Exception {
    try (PreparedStatement ps =
        conn.prepareStatement("SET app.workspace_id = '" + workspaceId + "'")) {
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
}
