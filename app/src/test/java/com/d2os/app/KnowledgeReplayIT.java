package com.d2os.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.d2os.app.support.StubAiGatewayClient;
import com.d2os.knowledge.EmbeddingIndexer;
import com.d2os.knowledge.KnowledgeScope;
import com.d2os.persona.OperationExecutionRecorder;
import com.d2os.persona.PersonaEnvelope;
import com.d2os.persona.ValidationResult;
import com.d2os.persona.spi.KnowledgeProvider;
import com.d2os.replay.ReplayHarness;
import com.d2os.replay.ReplayReport;
import com.d2os.tenancy.WorkspaceContext;
import com.d2os.tenancy.WorkspaceProvisioningService;
import com.d2os.testsupport.ContainerFixtures;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
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
 * Knowledge replay IT (T018, US1, SC-003). A knowledge-injected operation is recorded; THEN one
 * injected item is superseded by a new version and another is deprecated. Replay must still
 * reconstruct the operation's injected context from the SNAPSHOT (the pinned versions/hashes, not
 * the items' current state) and verify the output byte-identically — proving history is immune to
 * later item lifecycle changes.
 */
@SpringBootTest
@Import(StubAiGatewayClient.class)
class KnowledgeReplayIT {

  @Autowired DataSource dataSource;
  @Autowired JdbcTemplate jdbcTemplate;
  @Autowired WorkspaceProvisioningService workspaceProvisioningService;
  @Autowired EmbeddingIndexer embeddingIndexer;
  @Autowired KnowledgeProvider knowledgeProvider;
  @Autowired OperationExecutionRecorder operationExecutionRecorder;
  @Autowired ReplayHarness replayHarness;

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
      /* bucket may already exist across suites */
    }
  }

  @BeforeEach
  void seedTenancy() throws Exception {
    featureId = UUID.randomUUID();
    UUID projectId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();

    WorkspaceContext.set(WORKSPACE_ID);
    workspaceProvisioningService.provisionWorkspace(WORKSPACE_ID, "knowledge-replay-ws", "test");

    try (Connection c = dataSource.getConnection()) {
      exec(c, "SET app.workspace_id = '" + WORKSPACE_ID + "'");
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

  @AfterEach
  void clearContext() {
    WorkspaceContext.clear();
  }

  @Test
  void replayReconstructsInjectedContextFromSnapshotAfterItemLifecycleChanges() throws Exception {
    WorkspaceContext.set(WORKSPACE_ID);

    // Seed two items and inject both into one operation.
    UUID supersededId =
        embeddingIndexer.publish(
            WORKSPACE_ID,
            UUID.randomUUID(),
            "policy-a",
            1,
            KnowledgeScope.WORKSPACE,
            WORKSPACE_ID,
            List.of("policy"),
            "en",
            "Policy A v1",
            "Original policy A guidance.",
            null,
            null);
    UUID toDeprecateId =
        embeddingIndexer.publish(
            WORKSPACE_ID,
            UUID.randomUUID(),
            "policy-b",
            1,
            KnowledgeScope.WORKSPACE,
            WORKSPACE_ID,
            List.of("policy"),
            "en",
            "Policy B v1",
            "Original policy B guidance.",
            null,
            null);

    List<KnowledgeProvider.InjectedItem> injected =
        knowledgeProvider.retrieve(
            new KnowledgeProvider.KnowledgeQuery(
                WORKSPACE_ID, null, List.of("policy"), List.of("policy"), 5));
    assertEquals(2, injected.size());
    String supersededHashAtInjection =
        injected.stream()
            .filter(i -> i.itemId().equals(supersededId))
            .findFirst()
            .orElseThrow()
            .contentHash();

    // Minimal case → persona_invocation chain so the operation_execution FK resolves.
    UUID caseId = UUID.randomUUID();
    UUID invocationId = UUID.randomUUID();
    try (Connection c = dataSource.getConnection()) {
      exec(c, "SET app.workspace_id = '" + WORKSPACE_ID + "'");
      ins(
          c,
          "INSERT INTO case_instance (id,workspace_id,feature_id,submission_id,case_type_key,"
              + "case_type_version,token_budget,created_by) VALUES (?,?,?,?,?,?,?,?)",
          caseId,
          WORKSPACE_ID,
          featureId,
          UUID.randomUUID(),
          "initiation",
          "1.0.0",
          1_000_000L,
          "t");
      ins(
          c,
          "INSERT INTO persona_invocation (id,workspace_id,case_instance_id,persona_definition_id,"
              + "persona_definition_version,sequence_no,persona_key,status) VALUES (?,?,?,?,?,?,?,?)",
          invocationId,
          WORKSPACE_ID,
          caseId,
          UUID.randomUUID(),
          "1.0.0",
          1,
          "risk-governance-officer",
          "running");
    }

    PersonaEnvelope envelope =
        new PersonaEnvelope(
            caseId,
            "risk-governance-officer",
            UUID.randomUUID(),
            "1.0.0",
            UUID.randomUUID(),
            "1.0.0",
            "template",
            UUID.randomUUID(),
            "1.0.0",
            "{}",
            "{\"description\":\"x\"}",
            injected,
            10,
            List.of(),
            null,
            List.of());

    WorkspaceContext.set(WORKSPACE_ID);
    var execution =
        operationExecutionRecorder.record(
            WORKSPACE_ID,
            invocationId,
            envelope,
            "rendered prompt",
            "stub-provider",
            "stub-model-1.0",
            "byte-identical output body",
            128L,
            1,
            new ValidationResult(0.95, List.of(), true));

    // Now mutate the underlying items AFTER the operation was recorded:
    //   - supersede policy-a with a v2 (new content),
    //   - deprecate policy-b.
    WorkspaceContext.set(WORKSPACE_ID);
    embeddingIndexer.publish(
        WORKSPACE_ID,
        UUID.randomUUID(),
        "policy-a",
        2,
        KnowledgeScope.WORKSPACE,
        WORKSPACE_ID,
        List.of("policy"),
        "en",
        "Policy A v2",
        "REVISED policy A guidance.",
        null,
        1);
    jdbcTemplate.update(
        "UPDATE knowledge_item SET status = 'DEPRECATED' WHERE id = ?", toDeprecateId);

    // Replay: the injected context must reconstruct from the snapshot (v1 hashes), and output must
    // match.
    ReplayReport report = replayHarness.replay(caseId);

    assertEquals(1, report.totalOperations());
    assertEquals(0, report.mismatched(), "no operation may mismatch on replay");
    assertEquals(
        1, report.matched(), "the injected, snapshotted operation replays byte-identically");

    ReplayReport.OperationResult result = report.results().get(0);
    assertEquals(execution.getId(), result.operationExecutionId());
    assertTrue(result.byteIdentical(), "recorded output must reconstruct byte-identically");
    assertTrue(result.snapshotComplete(), "snapshot must be reproducibility-complete");
    assertTrue(
        result.knowledgeContextReproduced(),
        "injected context reconstructs from the snapshot despite the later supersede+deprecate (SC-003)");

    // The snapshot pinned policy-a v1 (its hash at injection), NOT the newer v2 content.
    String snapshottedHash =
        jdbcTemplate.queryForObject(
            "SELECT content_hash FROM knowledge_injection_snapshot "
                + "WHERE operation_execution_id = ? AND knowledge_item_key = 'policy-a'",
            String.class,
            execution.getId());
    assertEquals(
        supersededHashAtInjection,
        snapshottedHash,
        "the snapshot preserves the v1 hash, not the superseding v2 content");
    int snapshottedVersion =
        jdbcTemplate.queryForObject(
            "SELECT knowledge_item_version FROM knowledge_injection_snapshot "
                + "WHERE operation_execution_id = ? AND knowledge_item_key = 'policy-a'",
            Integer.class,
            execution.getId());
    assertEquals(1, snapshottedVersion, "the snapshot pins version 1, not the later version 2");
  }

  private void exec(Connection c, String sql) throws Exception {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.execute();
    }
  }

  private void ins(Connection c, String sql, Object... params) throws Exception {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        Object p = params[i];
        if (p instanceof UUID u) ps.setObject(i + 1, u);
        else if (p instanceof Long l) ps.setLong(i + 1, l);
        else if (p instanceof Integer n) ps.setInt(i + 1, n);
        else ps.setString(i + 1, p.toString());
      }
      ps.executeUpdate();
    }
  }
}
