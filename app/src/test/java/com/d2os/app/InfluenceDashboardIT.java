package com.d2os.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.d2os.projection.Projector;
import com.d2os.projection.api.InfluenceController.InfluenceEntryView;
import com.d2os.testsupport.ContainerFixtures;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * T028 — US4 acceptance IT (SC-006, FR-010, research R9): a seeded case with a Phase 3 injection
 * snapshot and a recorded {@code knowledge_influence} KPI sample attributes that reading to the
 * exact item version, navigable to the operations/artifacts it touched; an item that was injected
 * but never sampled reports {@code NOT_YET_MEASURABLE}.
 *
 * <p><b>Cannot actually run in this environment</b> — Testcontainers/Docker confirmed
 * non-functional in this sandbox (client/server API version mismatch, documented throughout this
 * delivery chain). Written to be logically sound against the real {@link
 * com.d2os.projection.influence.InfluenceAnalyticsService}/{@link
 * com.d2os.projection.api.InfluenceController} code and the actual {@code
 * knowledge_injection_snapshot}/{@code kpi_sample}/{@code artifact_revision} schemas (V9/V14/V15/
 * V28), traced by hand rather than asserted to pass.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InfluenceDashboardIT {

  @LocalServerPort int port;
  @Autowired TestRestTemplate rest;
  @Autowired DataSource dataSource;
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
  void
      measuredItemAttributesReadingsAndNavigatesToTouchedNodesWhileUnsampledItemReportsNotYetMeasurable()
          throws Exception {
    UUID workspaceId = UUID.randomUUID();
    Seeded measured = seedInjectedItemWithArtifact(workspaceId, "measured-item", 1, true);
    Seeded unsampled = seedInjectedItemWithArtifact(workspaceId, "unsampled-item", 1, false);

    projector.sweep();

    List<InfluenceEntryView> measuredResult = influence(workspaceId, "measured-item");
    assertEquals(1, measuredResult.size());
    InfluenceEntryView measuredEntry = measuredResult.get(0);
    assertEquals("MEASURED", measuredEntry.state());
    assertTrue(
        !measuredEntry.readings().isEmpty(),
        "the recorded knowledge_influence sample is attributed");
    assertEquals(0.42, measuredEntry.readings().get(0).delta(), 0.0001);
    assertTrue(
        measuredEntry.touchedOperations().stream()
            .anyMatch(n -> measured.operationExecutionId.toString().equals(n.naturalKey())),
        "navigable to the exact operation touched (INJECTED_INTO)");
    assertTrue(
        measuredEntry.touchedArtifacts().stream()
            .anyMatch(n -> measured.revisionId.toString().equals(n.naturalKey())),
        "navigable to the exact artifact touched (PRODUCED)");

    List<InfluenceEntryView> unsampledResult = influence(workspaceId, "unsampled-item");
    assertEquals(1, unsampledResult.size());
    assertEquals("NOT_YET_MEASURABLE", unsampledResult.get(0).state());
    assertTrue(unsampledResult.get(0).readings().isEmpty());
  }

  // ---- seeding -------------------------------------------------------------------------------

  private record Seeded(UUID operationExecutionId, UUID revisionId) {}

  private Seeded seedInjectedItemWithArtifact(
      UUID workspaceId, String itemKey, int itemVersion, boolean withKpiSample) throws Exception {
    UUID projectId = UUID.randomUUID();
    UUID projectVersionId = UUID.randomUUID();
    UUID featureId = UUID.randomUUID();
    UUID caseId = UUID.randomUUID();
    UUID personaInvocationId = UUID.randomUUID();
    UUID operationExecutionId = UUID.randomUUID();
    UUID artifactId = UUID.randomUUID();
    UUID revisionId = UUID.randomUUID();
    UUID knowledgeItemId = UUID.randomUUID();

    try (Connection conn = dataSource.getConnection()) {
      setWorkspace(conn, workspaceId);
      insert(
          conn,
          "INSERT INTO workspace (id, name, created_by) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
          workspaceId,
          "influence-it-ws",
          "test");
      insert(
          conn,
          "INSERT INTO project (id, workspace_id, name, created_by) VALUES (?, ?, ?, ?)",
          projectId,
          workspaceId,
          "p-" + itemKey,
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
          "f-" + itemKey,
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
      insert(
          conn,
          "INSERT INTO event_outbox (id, workspace_id, aggregate_type, aggregate_id, event_type, "
              + "payload) VALUES (?, ?, 'case_instance', ?, 'Delivered', '{}'::jsonb)",
          UUID.randomUUID(),
          workspaceId,
          caseId);

      insert(
          conn,
          "INSERT INTO persona_invocation (id, workspace_id, case_instance_id, "
              + "persona_definition_id, persona_definition_version, sequence_no, status) "
              + "VALUES (?, ?, ?, ?, '1.0.0', 1, 'validated')",
          personaInvocationId,
          workspaceId,
          caseId,
          UUID.randomUUID());
      insert(
          conn,
          "INSERT INTO operation_execution (id, workspace_id, persona_invocation_id, "
              + "prompt_definition_id, prompt_definition_version, model_id, model_version, "
              + "inputs, attempt_no) VALUES (?, ?, ?, ?, '1.0.0', 'test-model', '1', '{}'::jsonb, 1)",
          operationExecutionId,
          workspaceId,
          personaInvocationId,
          UUID.randomUUID());

      insert(
          conn,
          "INSERT INTO artifact (id, workspace_id, case_instance_id, template_definition_id, "
              + "template_definition_version, artifact_type) VALUES (?, ?, ?, ?, '1.0.0', 'brd')",
          artifactId,
          workspaceId,
          caseId,
          UUID.randomUUID());
      insert(
          conn,
          "INSERT INTO artifact_revision (id, workspace_id, artifact_id, revision_no, storage_ref, "
              + "content_hash, produced_by_operation_execution_id) "
              + "VALUES (?, ?, ?, 1, 's3://x', 'deadbeef', ?)",
          revisionId,
          workspaceId,
          artifactId,
          operationExecutionId);

      insert(
          conn,
          "INSERT INTO knowledge_injection_snapshot (id, workspace_id, operation_execution_id, "
              + "knowledge_item_id, knowledge_item_key, knowledge_item_version, content_hash, position) "
              + "VALUES (?, ?, ?, ?, ?, ?, 'deadbeef', 0)",
          UUID.randomUUID(),
          workspaceId,
          operationExecutionId,
          knowledgeItemId,
          itemKey,
          itemVersion);

      if (withKpiSample) {
        String dimensions = "{\"key\":\"" + itemKey + "\",\"version\":\"" + itemVersion + "\"}";
        insert(
            conn,
            "INSERT INTO kpi_sample (id, workspace_id, metric, value, dimensions) "
                + "VALUES (?, ?, 'knowledge_influence', 0.42, ?::jsonb)",
            UUID.randomUUID(),
            workspaceId,
            dimensions);
      }
    }
    return new Seeded(operationExecutionId, revisionId);
  }

  // ---- helpers -------------------------------------------------------------------------------

  private List<InfluenceEntryView> influence(UUID workspaceId, String knowledgeKey) {
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Workspace-Id", workspaceId.toString());
    headers.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<InfluenceEntryView[]> response =
        rest.exchange(
            "http://localhost:" + port + "/api/v1/graph/influence?knowledgeKey=" + knowledgeKey,
            HttpMethod.GET,
            new HttpEntity<>(null, headers),
            InfluenceEntryView[].class);
    assertEquals(200, response.getStatusCode().value());
    return List.of(response.getBody());
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
        if (p instanceof UUID u) ps.setObject(i + 1, u);
        else if (p instanceof Integer n) ps.setInt(i + 1, n);
        else ps.setString(i + 1, p.toString());
      }
      ps.executeUpdate();
    }
  }
}
