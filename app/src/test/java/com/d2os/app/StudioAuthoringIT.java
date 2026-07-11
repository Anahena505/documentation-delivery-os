package com.d2os.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.d2os.app.support.StubAiGatewayClient;
import com.d2os.catalog.DefinitionResolutionService;
import com.d2os.testsupport.ContainerFixtures;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
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
import org.springframework.context.annotation.Import;
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
 * US1 acceptance IT (tasks.md T012, SC-001). Creates a draft of each of the eight definition types
 * through {@code DraftController} (T008) — a Rule authored via the DMN-XML bridge (T010) and a
 * Prompt/Rubric authored via the typed-slot editor models (T009, slot validation rejects malformed
 * slots) — confirms reload restores the full content, and confirms drafts are never resolvable by
 * any running case ({@link DefinitionResolutionService#latestPublished} filters on {@code
 * status='Published'}; Draft/InReview rows are invisible to it by construction — Principle I). Also
 * smoke-tests the Thymeleaf studio pages (T011, {@code StudioPageController}) over the same {@code
 * TestRestTemplate}/{@code RANDOM_PORT} harness this repo's other Phase 5/6 ITs use ({@code
 * GateFlowIT}, {@code DeprecationIT}) rather than a separate MockMvc/HtmlUnit fixture, since the
 * whole app context (including the {@code studio} module's ViewResolver) is already up — same
 * substance as "MockMvc/HtmlUnit smoke on the Thymeleaf editor routes", different client.
 *
 * <p><b>Cannot actually run in this environment</b> (no Docker, confirmed in every prior phase's
 * IT) — written to be logically sound against the real {@code DraftController}/{@code
 * DraftService}/{@code DefinitionResolutionService}/{@code StudioPageController}/{@code
 * RubricEditorModel}/{@code PromptEditorModel}/{@code DmnEditorBridge} code, traced by hand rather
 * than asserted to pass. Follows the {@code GateFlowIT}/{@code DeprecationIT} IT skeleton:
 * Testcontainers via {@link ContainerFixtures#startAll()}, a {@link StubAiGatewayClient} import,
 * direct-JDBC tenancy seeding, {@code X-Workspace-Id} header fallback (enabled only in {@code
 * app/src/test/resources/application.properties}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StubAiGatewayClient.class)
class StudioAuthoringIT {

  @LocalServerPort int port;

  @Autowired TestRestTemplate rest;

  @Autowired JdbcTemplate jdbcTemplate;

  @Autowired DataSource dataSource;

  @Autowired DefinitionResolutionService resolutionService;

  private static final UUID WORKSPACE_ID = UUID.randomUUID();

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
    try (Connection conn = dataSource.getConnection()) {
      try (PreparedStatement setCtx =
          conn.prepareStatement("SET app.workspace_id = '" + WORKSPACE_ID + "'")) {
        setCtx.execute();
      }
      insert(
          conn,
          "INSERT INTO workspace (id, name, created_by) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
          WORKSPACE_ID,
          "studio-authoring-ws",
          "test");
    }
  }

  // ---- 1. All eight types create as Draft, persist, and reload with full content ---------------

  @Test
  void allEightTypesAreDraftableAndReloadRestoresContent() {
    HttpHeaders headers = headers();
    String suffix = UUID.randomUUID().toString().substring(0, 8);

    // Five plain-JSON-body types — no typed-slot/DMN editor of their own in this phase.
    for (String type : List.of("case_type", "workflow", "persona", "playbook", "template")) {
      Map<String, Object> body = Map.of("name", type + "-body-" + suffix);
      Map<String, Object> created =
          createDraft(headers, type, type + "-key-" + suffix, "1.0.0", body);
      assertEquals("Draft", created.get("status"), type + " must be created as Draft");
      String id = (String) created.get("id");

      Map<String, Object> reloaded = getDraft(headers, id);
      assertEquals("Draft", reloaded.get("status"));
      @SuppressWarnings("unchecked")
      Map<String, Object> reloadedBody = (Map<String, Object>) reloaded.get("body");
      assertEquals(
          type + "-body-" + suffix, reloadedBody.get("name"), "reload restores full content");
    }

    // Prompt via the typed-slot editor (T009): personaKey + template; slots are computed from
    // the {{...}} placeholders the template declares, not separately stored.
    Map<String, Object> promptBody =
        Map.of(
            "personaKey",
            "studio-test-persona",
            "template",
            "You are the tester. <untrusted-submission-data>{{submissionData}}"
                + "</untrusted-submission-data>");
    Map<String, Object> promptDraft =
        createDraft(headers, "prompt", "studio-test-prompt-" + suffix, "1.0.0", promptBody);
    assertEquals("Draft", promptDraft.get("status"));
    Map<String, Object> reloadedPrompt = getDraft(headers, (String) promptDraft.get("id"));
    @SuppressWarnings("unchecked")
    Map<String, Object> reloadedPromptBody = (Map<String, Object>) reloadedPrompt.get("body");
    assertEquals(
        "studio-test-persona",
        reloadedPromptBody.get("personaKey"),
        "reload restores the prompt's typed fields");

    // Rubric via the typed-slot editor (T009): criteria weights summing to 1.0
    // (RubricEditorModel#validate).
    Map<String, Object> rubricBody =
        Map.of(
            "personaKey",
            "studio-test-persona",
            "criteria",
            List.of(
                Map.of("name", "structural_completeness", "weight", 0.5, "critical", true),
                Map.of("name", "content_quality", "weight", 0.5, "critical", false)));
    Map<String, Object> rubricDraft =
        createDraft(headers, "rubric", "studio-test-rubric-" + suffix, "1.0.0", rubricBody);
    assertEquals("Draft", rubricDraft.get("status"));
    Map<String, Object> reloadedRubric = getDraft(headers, (String) rubricDraft.get("id"));
    @SuppressWarnings("unchecked")
    Map<String, Object> reloadedRubricBody = (Map<String, Object>) reloadedRubric.get("body");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> reloadedCriteria =
        (List<Map<String, Object>>) reloadedRubricBody.get("criteria");
    assertEquals(2, reloadedCriteria.size(), "reload restores every criterion");

    // Rule via the DMN-XML bridge (T010): raw XML round-trips through the dedicated dmn-xml
    // endpoint, not the generic JSON body path (the generic body just holds a placeholder here).
    Map<String, Object> ruleDraft =
        createDraft(
            headers, "rule", "studio-test-rule-" + suffix, "1.0.0", Map.of("placeholder", true));
    String ruleId = (String) ruleDraft.get("id");
    String dmnXml =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><definitions id=\"d\" name=\"studio-test-rule\" "
            + "xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\"><decision id=\"studioTestRule\" "
            + "name=\"Studio Test Rule\"/></definitions>";
    ResponseEntity<Map> putXml =
        rest.exchange(
            url("/api/v1/catalog/drafts/" + ruleId + "/dmn-xml"),
            HttpMethod.PUT,
            new HttpEntity<>(dmnXml, xmlHeaders()),
            Map.class);
    assertEquals(
        200, putXml.getStatusCode().value(), () -> "dmn-xml PUT failed: " + putXml.getBody());

    ResponseEntity<String> getXml =
        rest.exchange(
            url("/api/v1/catalog/drafts/" + ruleId + "/dmn-xml"),
            HttpMethod.GET,
            new HttpEntity<>(headers),
            String.class);
    assertEquals(200, getXml.getStatusCode().value());
    assertEquals(dmnXml, getXml.getBody(), "the DMN bridge round-trips raw XML byte-for-byte");
  }

  // ---- 2. Malformed typed slots are rejected before save (FR-003)
  // --------------------------------

  @Test
  void malformedRubricSlotsAreRejectedBeforeSave() {
    HttpHeaders headers = headers();
    Map<String, Object> badRubric =
        Map.of(
            "personaKey",
            "x",
            "criteria",
            List.of(Map.of("name", "only_one", "weight", 0.4, "critical", true)));
    ResponseEntity<Map> resp =
        rest.exchange(
            url("/api/v1/catalog/drafts"),
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of(
                    "type",
                    "rubric",
                    "key",
                    "bad-rubric-" + UUID.randomUUID(),
                    "version",
                    "1.0.0",
                    "body",
                    badRubric),
                headers),
            Map.class);
    assertEquals(
        400,
        resp.getStatusCode().value(),
        "weights not summing to 1.0 must be rejected before save");
  }

  @Test
  void malformedPromptSlotsAreRejectedBeforeSave() {
    HttpHeaders headers = headers();
    Map<String, Object> badPrompt = Map.of("personaKey", "x", "template", "no placeholders here");
    ResponseEntity<Map> resp =
        rest.exchange(
            url("/api/v1/catalog/drafts"),
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of(
                    "type",
                    "prompt",
                    "key",
                    "bad-prompt-" + UUID.randomUUID(),
                    "version",
                    "1.0.0",
                    "body",
                    badPrompt),
                headers),
            Map.class);
    assertEquals(
        400,
        resp.getStatusCode().value(),
        "a template with no {{slot}} placeholder must be rejected before save");
  }

  // ---- 3. Drafts are never resolvable by a running case (Principle I) ---------------------------

  @Test
  void draftsAreNotResolvableUntilPublished() {
    HttpHeaders headers = headers();
    String key = "studio-unresolvable-" + UUID.randomUUID();
    createDraft(headers, "persona", key, "1.0.0", Map.of("key", key));

    assertTrue(
        resolutionService.latestPublished("persona", key).isEmpty(),
        "a Draft-status definition must not be resolvable by DefinitionResolutionService "
            + "(resolution filters status='Published' — CatalogController/DefinitionResolutionService "
            + "never see this row until a later phase's PublishController publishes it)");
  }

  // ---- 4. Update succeeds while Draft status (the in-scope half of the InReview freeze) ---------

  @Test
  void updateSucceedsWhileDraftStatus() {
    HttpHeaders headers = headers();
    String key = "studio-update-" + UUID.randomUUID();
    Map<String, Object> created = createDraft(headers, "template", key, "1.0.0", Map.of("v", 1));
    String id = (String) created.get("id");

    ResponseEntity<Map> updateResp =
        rest.exchange(
            url("/api/v1/catalog/drafts/" + id),
            HttpMethod.PUT,
            new HttpEntity<>(Map.of("body", Map.of("v", 2)), headers),
            Map.class);
    assertEquals(200, updateResp.getStatusCode().value());
    @SuppressWarnings("unchecked")
    Map<String, Object> updatedBody = (Map<String, Object>) updateResp.getBody().get("body");
    assertEquals(
        2,
        ((Number) updatedBody.get("v")).intValue(),
        "update while Draft must persist the new body");

    // NOTE (T012 scope, tasks.md): the InReview-rejection half of this scenario — update a
    // draft after DefinitionAsset#markInReview() flips its status, expect 409 — is NOT
    // exercised here. Nothing in this phase calls markInReview() yet; that wiring is T013's
    // submit-for-review endpoint (US2, a later phase, not yet implemented). DraftService's
    // guard (DefinitionAsset#updateBody throwing IllegalStateException for any non-Draft
    // status, mapped to 409 by DraftExceptionHandler) is already implemented and exercised by
    // this same code path once a row leaves Draft; the 409 scenario is left for
    // PublishGovernanceIT (T019) to cover once T013 actually flips a row to InReview, rather
    // than fabricated here against machinery this phase doesn't build.
  }

  // ---- 5. Thymeleaf studio pages smoke test (T011)
  // -----------------------------------------------

  @Test
  void studioPagesRenderOverHttp() {
    HttpHeaders headers = headers();
    String key = "studio-page-smoke-" + UUID.randomUUID();
    Map<String, Object> created = createDraft(headers, "playbook", key, "1.0.0", Map.of("k", "v"));
    String id = (String) created.get("id");

    ResponseEntity<String> listPage =
        rest.exchange(
            url("/studio/drafts"), HttpMethod.GET, new HttpEntity<>(headers), String.class);
    assertEquals(200, listPage.getStatusCode().value());
    assertTrue(
        listPage.getBody().contains(key),
        "the drafts list page renders the newly created draft's key");

    ResponseEntity<String> editPage =
        rest.exchange(
            url("/studio/drafts/" + id), HttpMethod.GET, new HttpEntity<>(headers), String.class);
    assertEquals(200, editPage.getStatusCode().value());
    assertTrue(
        editPage.getBody().contains(key), "the draft-edit page renders the draft it was asked for");
    assertTrue(
        editPage.getBody().contains("/studio/vendor/htmx/htmx.min.js"),
        "the editor page references the vendored htmx asset path (T002/T011)");
  }

  // ---- helpers
  // ------------------------------------------------------------------------------------

  private Map<String, Object> createDraft(
      HttpHeaders headers, String type, String key, String version, Object body) {
    ResponseEntity<Map> resp =
        rest.exchange(
            url("/api/v1/catalog/drafts"),
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of("type", type, "key", key, "version", version, "body", body), headers),
            Map.class);
    assertEquals(
        201, resp.getStatusCode().value(), () -> "create " + type + " failed: " + resp.getBody());
    return resp.getBody();
  }

  private Map<String, Object> getDraft(HttpHeaders headers, String id) {
    ResponseEntity<Map> resp =
        rest.exchange(
            url("/api/v1/catalog/drafts/" + id),
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);
    assertEquals(200, resp.getStatusCode().value());
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

  private HttpHeaders xmlHeaders() {
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Workspace-Id", WORKSPACE_ID.toString());
    headers.setContentType(MediaType.APPLICATION_XML);
    return headers;
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }
}
