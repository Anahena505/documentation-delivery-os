package com.d2os.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.d2os.app.support.StubAiGatewayClient;
import com.d2os.projection.Projector;
import com.d2os.testsupport.ContainerFixtures;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
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
 * T019 &mdash; US2 acceptance IT (SC-003, FR-005/006/007/013): seeds a known multi-hop lineage
 * (submission &rarr; case &rarr; requirement &rarr; artifacts &rarr; package) through {@link
 * Projector#sweep()}'s normal projection path, then drives {@code GET /graph/traceability} for both
 * TRACES_TO and DEPENDS_ON, both directions, and confirms every hop is navigable via {@code GET
 * /graph/nodes/{id}}. A UI smoke test hits the panel and separately confirms {@code studio} is not
 * on {@code projection}'s classpath.
 *
 * <h2>Seeded topology</h2>
 *
 * <pre>
 *   CASE          --DERIVES_FROM--&gt; SUBMISSION      (trace_link; case derives from the intake submission)
 *   REQUIREMENT   --SATISFIES-----&gt; CASE             (trace_link; requirement satisfies the case)
 *   DESIGN (artifact_revision) --DERIVES_FROM--&gt; REQUIREMENT (trace_link)
 *   PACKAGE       --DEPENDS_ON----&gt; DESIGN            (test-only direct graph_edge/graph_node seed, see below)
 * </pre>
 *
 * REQUIREMENT/ARTIFACT_REVISION/CASE/SUBMISSION nodes and the DERIVES_FROM/SATISFIES edges are all
 * produced by {@link Projector#sweep()}'s real {@code case_instance}/{@code artifact_revision}/
 * {@code trace_link} table scans &mdash; the SAME path production traffic uses, nothing test-only
 * about that half.
 *
 * <h2>Deliberate test-only seeding shortcut (PACKAGE + DEPENDS_ON)</h2>
 *
 * Two independent, already-documented Phase 3 gaps compound here: (1) {@code dependency} (the
 * source table {@code DEPENDS_ON} edges are meant to be projected from) has NO writer anywhere in
 * this repo today (confirmed by {@code NodeEdgeMapper}/{@code Projector}'s own javadoc) &mdash;
 * there is no service call that would ever produce one to seed through; (2) {@link Projector} does
 * not materialize {@code PACKAGE} nodes at all in this phase (its own javadoc: "Deliberately
 * DEFERRED... PACKAGE/execution_package"). Since T019 explicitly asks for a lineage that reaches a
 * PACKAGE hop via a DEPENDS_ON edge, and neither half of that exists anywhere in production code to
 * drive, this test inserts the {@code PACKAGE} {@code graph_node} row and the {@code DEPENDS_ON}
 * {@code graph_edge} row DIRECTLY via test SQL against the {@code d2os_projector} datasource (the
 * same escape hatch {@code RebuildEquivalenceIT}'s divergence subtest already uses for a different
 * reason) &mdash; this is a TEST-ONLY shortcut to exercise the query surface meaningfully, never
 * something production code does. A real {@code execution_package} row is still seeded first so the
 * PACKAGE node's {@code source_ref} stays a real, dereferenceable id (FR-003 spirit preserved even
 * in the shortcut).
 *
 * <p><b>Cannot actually run in this environment</b> &mdash; Testcontainers/Docker confirmed
 * non-functional in this sandbox since Phase 1+2's own report. Written to compile and be logically
 * sound against the real {@link Projector}/{@code TraceabilityQueryService}/{@code
 * TraceabilityController}/{@code TraceabilityPanelController} code and the actual V28/V4/V6/V7
 * schemas, traced by hand rather than asserted to pass &mdash; same posture as {@code
 * RebuildEquivalenceIT}/{@code PayloadSufficiencyIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StubAiGatewayClient.class)
class TraceabilityQueryIT {

  @LocalServerPort int port;
  @Autowired TestRestTemplate rest;
  @Autowired DataSource dataSource;

  @Autowired
  @Qualifier("projectorDataSource")
  DataSource projectorDataSource;

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
  @SuppressWarnings("unchecked")
  void tracesToAndDependsOnReturnSeededLineageBothDirectionsWithNavigableHops() throws Exception {
    UUID workspaceId = UUID.randomUUID();
    Seed seed = seedLineage(workspaceId);

    projector.sweep(); // builds generation 0 from case_instance/artifact_revision/trace_link scans

    UUID designNodeId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM graph_node WHERE workspace_id = ? AND generation = 0 "
                + "AND node_type = 'ARTIFACT_REVISION' AND natural_key = ?",
            UUID.class,
            workspaceId,
            seed.designRevisionId().toString());
    assertNotNull(designNodeId, "Projector must have projected the DESIGN artifact_revision node");

    UUID packageNodeId = seedPackageDependsOnEdge(workspaceId, seed.packageId(), designNodeId);

    HttpHeaders headers = headers(workspaceId);

    // --- TRACES_TO, BOTH directions from CASE: reaches SUBMISSION (downstream) and
    // REQUIREMENT/DESIGN transitively upstream. ---
    Map<String, Object> tracesToResult =
        getTraceability("CASE", seed.caseId().toString(), "TRACES_TO", "BOTH", 5, headers);
    Map<String, Object> tracesToRoot = (Map<String, Object>) tracesToResult.get("root");
    assertEquals("CASE", tracesToRoot.get("nodeType"));
    assertEquals(seed.caseId().toString(), tracesToRoot.get("naturalKey"));

    List<List<Map<String, Object>>> tracesToPaths =
        (List<List<Map<String, Object>>>) tracesToResult.get("paths");
    assertTrue(
        hopExists(tracesToPaths, "SUBMISSION", seed.submissionId().toString(), 1),
        "downstream hop: CASE -DERIVES_FROM-> SUBMISSION at depth 1");
    assertTrue(
        hopExists(tracesToPaths, "REQUIREMENT", seed.requirementRevisionId().toString(), 1)
            || hopExists(
                tracesToPaths, "ARTIFACT_REVISION", seed.requirementRevisionId().toString(), 1),
        "upstream hop: REQUIREMENT -SATISFIES-> CASE surfaces REQUIREMENT at depth 1");
    assertTrue(
        hopExists(tracesToPaths, "ARTIFACT_REVISION", seed.designRevisionId().toString(), 2),
        "transitive upstream hop: DESIGN -DERIVES_FROM-> REQUIREMENT surfaces DESIGN at depth 2");

    // --- REQUIREMENT node hop (task text's explicit ask) ---
    // Honest characteristic of the current mapper, not a test bug: NodeEdgeMapper's REQUIREMENT
    // node is a SIBLING projection of the same artifact_revision natural key (mapArtifactRevision's
    // "requirement:"-prefix special case) — it is never itself a trace_link edge endpoint, since
    // mapTraceLink's nodeTypeForSourceTable("artifact_revision") always resolves to
    // ARTIFACT_REVISION, never REQUIREMENT. So the REQUIREMENT hop is verified directly here: it
    // exists as its own addressable live-graph node (found, not 404), sharing the
    // SATISFIES-reachable revision's natural key, and is independently navigable.
    Map<String, Object> requirementResult =
        getTraceability(
            "REQUIREMENT",
            seed.requirementRevisionId().toString(),
            "TRACES_TO",
            "BOTH",
            5,
            headers);
    Map<String, Object> requirementRoot = (Map<String, Object>) requirementResult.get("root");
    assertEquals("REQUIREMENT", requirementRoot.get("nodeType"));
    assertEquals(seed.requirementRevisionId().toString(), requirementRoot.get("naturalKey"));
    assertNodeNavigable((String) requirementRoot.get("id"), "REQUIREMENT", headers);

    // --- DEPENDS_ON, DOWNSTREAM from PACKAGE: reaches DESIGN artifact_revision. ---
    Map<String, Object> dependsOnResult =
        getTraceability(
            "PACKAGE", seed.packageId().toString(), "DEPENDS_ON", "DOWNSTREAM", 5, headers);
    List<List<Map<String, Object>>> dependsOnPaths =
        (List<List<Map<String, Object>>>) dependsOnResult.get("paths");
    assertTrue(
        hopExists(dependsOnPaths, "ARTIFACT_REVISION", seed.designRevisionId().toString(), 1),
        "PACKAGE -DEPENDS_ON-> DESIGN at depth 1");

    // A TRACES_TO query from PACKAGE finds nothing — PACKAGE only carries a DEPENDS_ON edge,
    // proving relation filtering actually excludes the other edge-type family.
    Map<String, Object> packageTracesTo =
        getTraceability("PACKAGE", seed.packageId().toString(), "TRACES_TO", "BOTH", 5, headers);
    assertTrue(
        ((List<?>) packageTracesTo.get("paths")).isEmpty(),
        "PACKAGE has no TRACES_TO-family edges — relation filter must exclude the DEPENDS_ON edge");

    // --- Every hop navigable via GET /graph/nodes/{id} ---
    assertNodeNavigable((String) tracesToRoot.get("id"), "CASE", headers);
    for (List<Map<String, Object>> path : tracesToPaths) {
      for (Map<String, Object> hop : path) {
        Map<String, Object> node = (Map<String, Object>) hop.get("node");
        assertNodeNavigable((String) node.get("id"), (String) node.get("nodeType"), headers);
      }
    }
    assertNodeNavigable(packageNodeId.toString(), "PACKAGE", headers);

    // --- 404 for a starting node absent from this workspace's live graph ---
    ResponseEntity<Map> notFound =
        rest.exchange(
            url(
                "/api/v1/graph/traceability?nodeType=CASE&naturalKey="
                    + UUID.randomUUID()
                    + "&relation=TRACES_TO&direction=BOTH"),
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);
    assertEquals(404, notFound.getStatusCode().value());
  }

  @Test
  void panelRendersAndStudioIsNotOnProjectionsClasspath() {
    UUID workspaceId = UUID.randomUUID();
    HttpHeaders headers = headers(workspaceId);

    // Bare search-form render (no query submitted yet).
    ResponseEntity<String> blank =
        rest.exchange(
            url("/projection/traceability"),
            HttpMethod.GET,
            new HttpEntity<>(headers),
            String.class);
    assertEquals(200, blank.getStatusCode().value());
    assertTrue(
        blank.getBody().contains("Graph Traceability"),
        "panel must render its own page, not a 404/500");

    // A search for an absent node still renders the page (with a not-found message), not an error.
    ResponseEntity<String> searched =
        rest.exchange(
            url(
                "/projection/traceability?nodeType=CASE&naturalKey="
                    + UUID.randomUUID()
                    + "&relation=TRACES_TO"),
            HttpMethod.GET,
            new HttpEntity<>(headers),
            String.class);
    assertEquals(200, searched.getStatusCode().value());
    assertTrue(
        searched.getBody().contains("Not found"),
        "an absent node must render a clean not-found message");

    // SC-003 / plan.md "NOT studio-dependent": verified by inspecting projection/build.gradle
    // directly (a build-file assertion, not a JVM classpath-loader probe) — a real per-class
    // "is com.d2os.studio.* loadable from this classloader" check is closer to the ArchUnit-style
    // Polish-phase tooling (tasks.md T030) than something this IT should hand-roll; the
    // build.gradle dependency list IS the actual enforcement mechanism (Gradle would refuse to
    // compile/resolve `projection` against `studio` types without a declared dependency edge),
    // so asserting its absence here is a faithful, if indirect, proxy. Matches
    // `project(':studio')` specifically (a Gradle project-dependency declaration), not a bare
    // "studio" substring — T001's own build.gradle javadoc-style comment mentions "studio" in
    // prose (explaining why spring-boot-starter-web is present, by analogy to studio/build.gradle),
    // which is not a dependency edge and must not fail this check.
    String buildGradle = readProjectionBuildGradle();
    assertFalse(
        buildGradle.contains("project(':studio')") || buildGradle.contains("project(\":studio\")"),
        "projection/build.gradle must never declare a project(':studio') dependency (plan.md, research R7)");
  }

  // ---- traceability helpers -------------------------------------------------------------------

  @SuppressWarnings("unchecked")
  private Map<String, Object> getTraceability(
      String nodeType,
      String naturalKey,
      String relation,
      String direction,
      int maxDepth,
      HttpHeaders headers) {
    ResponseEntity<Map> resp =
        rest.exchange(
            url(
                "/api/v1/graph/traceability?nodeType="
                    + nodeType
                    + "&naturalKey="
                    + naturalKey
                    + "&relation="
                    + relation
                    + "&direction="
                    + direction
                    + "&maxDepth="
                    + maxDepth),
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);
    assertEquals(
        200, resp.getStatusCode().value(), () -> "traceability query failed: " + resp.getBody());
    return resp.getBody();
  }

  private boolean hopExists(
      List<List<Map<String, Object>>> paths, String nodeType, String naturalKey, int depth) {
    for (List<Map<String, Object>> path : paths) {
      for (Map<String, Object> hop : path) {
        @SuppressWarnings("unchecked")
        Map<String, Object> node = (Map<String, Object>) hop.get("node");
        if (nodeType.equals(node.get("nodeType"))
            && naturalKey.equals(node.get("naturalKey"))
            && Integer.valueOf(depth).equals(hop.get("depth"))) {
          return true;
        }
      }
    }
    return false;
  }

  @SuppressWarnings("unchecked")
  private void assertNodeNavigable(String nodeId, String expectedNodeType, HttpHeaders headers) {
    ResponseEntity<Map> resp =
        rest.exchange(
            url("/api/v1/graph/nodes/" + nodeId),
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);
    assertEquals(200, resp.getStatusCode().value(), () -> "node " + nodeId + " must be navigable");
    assertEquals(expectedNodeType, resp.getBody().get("nodeType"));
  }

  private String readProjectionBuildGradle() {
    for (String candidate : List.of("projection/build.gradle", "../projection/build.gradle")) {
      Path p = Path.of(candidate);
      if (Files.exists(p)) {
        try {
          return Files.readString(p);
        } catch (IOException e) {
          fail("could not read " + p + ": " + e.getMessage());
        }
      }
    }
    fail(
        "could not locate projection/build.gradle from working dir "
            + Path.of("").toAbsolutePath());
    return "";
  }

  // ---- seeding -------------------------------------------------------------------------------

  private record Seed(
      UUID workspaceId,
      UUID submissionId,
      UUID caseId,
      UUID requirementRevisionId,
      UUID designRevisionId,
      UUID packageId) {}

  private Seed seedLineage(UUID workspaceId) throws Exception {
    UUID projectId = UUID.randomUUID();
    UUID projectVersionId = UUID.randomUUID();
    UUID featureId = UUID.randomUUID();
    UUID submissionId = UUID.randomUUID();
    UUID caseId = UUID.randomUUID();
    UUID requirementArtifactId = UUID.randomUUID();
    UUID requirementRevisionId = UUID.randomUUID();
    UUID designArtifactId = UUID.randomUUID();
    UUID designRevisionId = UUID.randomUUID();
    UUID packageId = UUID.randomUUID();

    try (Connection c = dataSource.getConnection()) {
      setWorkspace(c, workspaceId);
      insert(
          c,
          "INSERT INTO workspace (id, name, created_by) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
          workspaceId,
          "trace-ws",
          "test");
      insert(
          c,
          "INSERT INTO project (id, workspace_id, name, created_by) VALUES (?, ?, ?, ?)",
          projectId,
          workspaceId,
          "p",
          "test");
      insert(
          c,
          "INSERT INTO project_version (id, workspace_id, project_id, label, created_by) "
              + "VALUES (?, ?, ?, ?, ?)",
          projectVersionId,
          workspaceId,
          projectId,
          "v1",
          "test");
      insert(
          c,
          "INSERT INTO feature (id, workspace_id, project_version_id, name, created_by) "
              + "VALUES (?, ?, ?, ?, ?)",
          featureId,
          workspaceId,
          projectVersionId,
          "f",
          "test");
      insert(
          c,
          "INSERT INTO problem_submission (id, workspace_id, form_data, created_by) "
              + "VALUES (?, ?, '{}'::jsonb, ?)",
          submissionId,
          workspaceId,
          "test");
      insert(
          c,
          "INSERT INTO case_instance (id, workspace_id, feature_id, submission_id, case_type_key, "
              + "case_type_version, mode, status, token_budget, created_by) "
              + "VALUES (?, ?, ?, ?, 'initiation', '1.0.0', 'mutating', 'Delivered', 1000, 'test')",
          caseId,
          workspaceId,
          featureId,
          submissionId);

      insert(
          c,
          "INSERT INTO artifact (id, workspace_id, case_instance_id, template_definition_id, "
              + "template_definition_version, artifact_type) VALUES (?, ?, ?, ?, '1.0.0', 'requirement:brd')",
          requirementArtifactId,
          workspaceId,
          caseId,
          UUID.randomUUID());
      insert(
          c,
          "INSERT INTO artifact_revision (id, workspace_id, artifact_id, revision_no, storage_ref, "
              + "content_hash) VALUES (?, ?, ?, 1, 's3://req', 'req-hash')",
          requirementRevisionId,
          workspaceId,
          requirementArtifactId);

      insert(
          c,
          "INSERT INTO artifact (id, workspace_id, case_instance_id, template_definition_id, "
              + "template_definition_version, artifact_type) VALUES (?, ?, ?, ?, '1.0.0', 'design-doc')",
          designArtifactId,
          workspaceId,
          caseId,
          UUID.randomUUID());
      insert(
          c,
          "INSERT INTO artifact_revision (id, workspace_id, artifact_id, revision_no, storage_ref, "
              + "content_hash) VALUES (?, ?, ?, 1, 's3://design', 'design-hash')",
          designRevisionId,
          workspaceId,
          designArtifactId);

      // Case lifecycle event so Projector materializes the CASE node itself (not merely as a
      // trace_link endpoint, which would leave it without its own BELONGS_TO/status attributes).
      insert(
          c,
          "INSERT INTO event_outbox (id, workspace_id, aggregate_type, aggregate_id, event_type, "
              + "payload) VALUES (?, ?, 'case_instance', ?, 'Delivered', '{}'::jsonb)",
          UUID.randomUUID(),
          workspaceId,
          caseId);

      // The trace_link chain — Projector's trace_link scan is generic over from_type/to_type
      // (see Projector#scanTraceLinks), so this alone produces SUBMISSION/CASE/REQUIREMENT
      // nodes (REQUIREMENT/ARTIFACT_REVISION nodes also come from the artifact_revision scan
      // above) and the DERIVES_FROM/SATISFIES edges — no test-only shortcut needed here.
      insert(
          c,
          "INSERT INTO trace_link (id, workspace_id, from_type, from_id, to_type, to_id, link_type) "
              + "VALUES (?, ?, 'case_instance', ?, 'problem_submission', ?, 'DERIVES_FROM')",
          UUID.randomUUID(),
          workspaceId,
          caseId,
          submissionId);
      insert(
          c,
          "INSERT INTO trace_link (id, workspace_id, from_type, from_id, to_type, to_id, link_type) "
              + "VALUES (?, ?, 'artifact_revision', ?, 'case_instance', ?, 'SATISFIES')",
          UUID.randomUUID(),
          workspaceId,
          requirementRevisionId,
          caseId);
      insert(
          c,
          "INSERT INTO trace_link (id, workspace_id, from_type, from_id, to_type, to_id, link_type) "
              + "VALUES (?, ?, 'artifact_revision', ?, 'artifact_revision', ?, 'DERIVES_FROM')",
          UUID.randomUUID(),
          workspaceId,
          designRevisionId,
          requirementRevisionId);

      // A real execution_package row — not itself projected by the Projector in this phase (see
      // class javadoc's "deliberate test-only seeding shortcut"), but a real, dereferenceable
      // row so the directly-seeded PACKAGE graph_node's source_ref isn't fabricated out of thin
      // air.
      insert(
          c,
          "INSERT INTO execution_package (id, workspace_id, case_instance_id, manifest, manifest_hash) "
              + "VALUES (?, ?, ?, '[]'::jsonb, 'pkg-hash')",
          packageId,
          workspaceId,
          caseId);
    }
    return new Seed(
        workspaceId, submissionId, caseId, requirementRevisionId, designRevisionId, packageId);
  }

  /**
   * Deliberate test-only shortcut (see class javadoc): directly inserts the {@code PACKAGE} {@code
   * graph_node} and its {@code DEPENDS_ON} {@code graph_edge} to {@code designNodeId} into
   * generation 0, through the {@code d2os_projector}-bound datasource (the only role with write
   * grants on these tables) &mdash; NOT through {@code NodeEdgeMapper}/{@code Projector}, since
   * neither materializes PACKAGE/DEPENDS_ON in this phase.
   */
  private UUID seedPackageDependsOnEdge(UUID workspaceId, UUID packageId, UUID designNodeId)
      throws Exception {
    UUID packageNodeId = UUID.randomUUID();
    try (Connection conn = projectorDataSource.getConnection()) {
      setWorkspace(conn, workspaceId);
      insert(
          conn,
          "INSERT INTO graph_node (id, workspace_id, generation, node_type, natural_key, label, "
              + "attributes, source_kind, source_ref, projected_at) "
              + "VALUES (?, ?, 0, 'PACKAGE', ?, 'Package', '{}'::jsonb, 'DEPENDENCY', ?, now())",
          packageNodeId,
          workspaceId,
          packageId.toString(),
          packageId.toString());
      insert(
          conn,
          "INSERT INTO graph_edge (id, workspace_id, generation, edge_type, from_node, to_node, "
              + "attributes, source_kind, source_ref, projected_at) "
              + "VALUES (gen_random_uuid(), ?, 0, 'DEPENDS_ON', ?, ?, '{}'::jsonb, 'DEPENDENCY', ?, now())",
          workspaceId,
          packageNodeId,
          designNodeId,
          UUID.randomUUID().toString());
    }
    return packageNodeId;
  }

  // ---- shared JDBC helpers (RebuildEquivalenceIT's own convention) ----------------------------

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

  private HttpHeaders headers(UUID ws) {
    HttpHeaders h = new HttpHeaders();
    h.set("X-Workspace-Id", ws.toString());
    h.setContentType(MediaType.APPLICATION_JSON);
    return h;
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }
}
