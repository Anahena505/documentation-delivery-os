package com.d2os.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.d2os.projection.Projector;
import com.d2os.projection.RebuildJob;
import com.d2os.projection.api.CycleController.CycleFinding;
import com.d2os.projection.cycle.CycleDetector;
import com.d2os.testsupport.ContainerFixtures;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
 * T024 — US3 acceptance IT (SC-004, FR-008/009, research R8): a dependency cycle spanning two cases
 * is detected and alerted; an acyclic graph produces zero false positives; a cycle that only ever
 * entered the graph through {@link RebuildJob} (never through {@link Projector#sweep}'s incremental
 * path) is still caught by {@link CycleDetector#scheduledSweep}.
 *
 * <p>Seeds {@code dependency} rows directly via SQL — per {@link Projector}'s and {@link
 * CycleDetector}'s own javadoc, no application code anywhere writes that table (a pre-existing,
 * documented, out-of-scope gap this phase does not close); this is the same posture {@code
 * RebuildEquivalenceIT} takes for seeding source rows the projector reads but nothing in the app
 * writes through its own API.
 *
 * <p><b>Cannot actually run in this environment</b> — Testcontainers/Docker confirmed
 * non-functional in this sandbox (client/server API version mismatch, documented throughout this
 * delivery chain). Written to be logically sound against the real {@link Projector}/{@link
 * RebuildJob}/{@link CycleDetector}/{@code CycleController} code and the actual {@code
 * dependency}/{@code graph_edge}/ {@code in_app_notification} schemas, traced by hand rather than
 * asserted to pass.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CycleDetectionIT {

  @LocalServerPort int port;
  @Autowired TestRestTemplate rest;
  @Autowired DataSource dataSource;
  @Autowired JdbcTemplate jdbcTemplate;
  @Autowired Projector projector;
  @Autowired RebuildJob rebuildJob;
  @Autowired CycleDetector cycleDetector;

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
  void cycleSpanningTwoCasesIsDetectedIncrementallyAndListed() throws Exception {
    UUID workspaceId = UUID.randomUUID();
    UUID caseA = seedCase(workspaceId, "case-a");
    UUID caseB = seedCase(workspaceId, "case-b");

    // Only A -> B exists yet: one projected DEPENDS_ON edge, no cycle.
    insertDependency(workspaceId, "case_instance", caseA, "case_instance", caseB);
    projector.sweep();

    List<CycleFinding> beforeClose = cycles(workspaceId);
    assertTrue(beforeClose.isEmpty(), "a single DEPENDS_ON edge cannot close a cycle by itself");

    // B -> A closes the cycle; the re-sweep re-projects both dependency rows (full-rescan, same
    // convention as trace_link) and the incremental check fires for each, finding the closure.
    insertDependency(workspaceId, "case_instance", caseB, "case_instance", caseA);
    projector.sweep();

    List<CycleFinding> afterClose = cycles(workspaceId);
    assertTrue(afterClose.size() >= 1, "GET /graph/cycles lists the closed cycle");
    Set<String> memberKeys =
        afterClose.get(0).memberNodes().stream()
            .map(n -> n.naturalKey())
            .collect(Collectors.toSet());
    assertTrue(
        memberKeys.contains(caseA.toString()) && memberKeys.contains(caseB.toString()),
        "the finding names both cases as cycle members (cross-case, US3)");
  }

  @Test
  void acyclicGraphProducesZeroFalsePositives() throws Exception {
    UUID workspaceId = UUID.randomUUID();
    UUID caseA = seedCase(workspaceId, "case-a");
    UUID caseB = seedCase(workspaceId, "case-b");
    UUID caseC = seedCase(workspaceId, "case-c");

    insertDependency(workspaceId, "case_instance", caseA, "case_instance", caseB);
    insertDependency(workspaceId, "case_instance", caseB, "case_instance", caseC);
    projector.sweep();

    assertTrue(
        cycles(workspaceId).isEmpty(), "an acyclic chain A->B->C produces zero findings (FR-009)");
  }

  @Test
  void cycleIntroducedOnlyThroughRebuildIsCaughtByTheScheduledSweep() throws Exception {
    UUID workspaceId = UUID.randomUUID();
    UUID caseA = seedCase(workspaceId, "case-a");
    UUID caseB = seedCase(workspaceId, "case-b");

    projector.sweep(); // bootstraps generation 0, no dependency rows yet

    // Both directions land in `dependency` before any incremental check ever runs against them.
    insertDependency(workspaceId, "case_instance", caseA, "case_instance", caseB);
    insertDependency(workspaceId, "case_instance", caseB, "case_instance", caseA);

    assertTrue(rebuildJob.triggerAsync(workspaceId), "no rebuild already in progress");
    waitUntilNotInProgress(workspaceId);
    assertEquals(
        1,
        jdbcTemplate.queryForObject(
            "SELECT live_generation FROM projection_state WHERE workspace_id = ?",
            Integer.class,
            workspaceId),
        "the rebuild materializes DEPENDS_ON edges into generation 1 (RebuildJob wiring, T021)");

    assertTrue(
        cycles(workspaceId).isEmpty(),
        "RebuildJob never calls CycleDetector — the cycle is live in the graph but not yet alerted");

    cycleDetector.scheduledSweep();

    List<CycleFinding> found = cycles(workspaceId);
    assertTrue(
        found.size() >= 1,
        "the scheduled full-graph sweep catches the cycle the incremental path never saw");
  }

  // ---- seeding -------------------------------------------------------------------------------

  private UUID seedCase(UUID workspaceId, String label) throws Exception {
    UUID projectId = UUID.randomUUID();
    UUID projectVersionId = UUID.randomUUID();
    UUID featureId = UUID.randomUUID();
    UUID caseId = UUID.randomUUID();
    try (Connection conn = dataSource.getConnection()) {
      setWorkspace(conn, workspaceId);
      insert(
          conn,
          "INSERT INTO workspace (id, name, created_by) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
          workspaceId,
          "cycle-it-ws",
          "test");
      insert(
          conn,
          "INSERT INTO project (id, workspace_id, name, created_by) VALUES (?, ?, ?, ?)",
          projectId,
          workspaceId,
          "p-" + label,
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
          "f-" + label,
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
    }
    return caseId;
  }

  private void insertDependency(
      UUID workspaceId, String fromType, UUID fromId, String toType, UUID toId) throws Exception {
    try (Connection conn = dataSource.getConnection()) {
      setWorkspace(conn, workspaceId);
      insert(
          conn,
          "INSERT INTO dependency (id, workspace_id, from_type, from_id, to_type, to_id, dep_type) "
              + "VALUES (?, ?, ?, ?, ?, ?, 'BLOCKS')",
          UUID.randomUUID(),
          workspaceId,
          fromType,
          fromId,
          toType,
          toId);
    }
  }

  // ---- helpers -------------------------------------------------------------------------------

  private List<CycleFinding> cycles(UUID workspaceId) {
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Workspace-Id", workspaceId.toString());
    headers.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<CycleFinding[]> response =
        rest.exchange(
            "http://localhost:" + port + "/api/v1/graph/cycles",
            HttpMethod.GET,
            new HttpEntity<>(null, headers),
            CycleFinding[].class);
    assertEquals(200, response.getStatusCode().value());
    return List.of(response.getBody());
  }

  private void waitUntilNotInProgress(UUID workspaceId) throws InterruptedException {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
    while (Instant.now().isBefore(deadline)) {
      if (!rebuildJob.isInProgress(workspaceId)) return;
      Thread.sleep(100);
    }
    fail("rebuild did not finish for workspace " + workspaceId + " within 30s");
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
        else ps.setString(i + 1, p.toString());
      }
      ps.executeUpdate();
    }
  }
}
