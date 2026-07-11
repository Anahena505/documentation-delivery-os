package com.d2os.app;

import com.d2os.app.support.StubAiGatewayClient;
import com.d2os.knowledge.EmbeddingIndexer;
import com.d2os.knowledge.KnowledgeScope;
import com.d2os.persona.OperationExecutionRecorder;
import com.d2os.persona.PersonaEnvelope;
import com.d2os.persona.ValidationResult;
import com.d2os.persona.spi.KnowledgeProvider;
import com.d2os.testsupport.ContainerFixtures;
import com.d2os.tenancy.WorkspaceContext;
import com.d2os.tenancy.WorkspaceProvisioningService;
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

import javax.sql.DataSource;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Knowledge retrieval + injection-snapshot IT (T017, US1, SC-001/SC-002). Seeds governed KnowledgeItems
 * through {@link EmbeddingIndexer} against real Postgres 16 + pgvector, then asserts:
 * <ul>
 *   <li>{@link KnowledgeProvider#retrieve} returns only PUBLISHED, tag-matching, in-scope items, capped
 *       at maxItems (SC-001);</li>
 *   <li>after {@link OperationExecutionRecorder#record} runs with an envelope carrying injected items,
 *       {@code knowledge_injection_snapshot} rows exist for exactly those items with the exact
 *       {@code (key,version)} + content hash + contiguous position (SC-002).</li>
 * </ul>
 *
 * <p>Direct-bean calls run on the {@code WorkspaceAwareDataSource}, which stamps {@code app.workspace_id}
 * from {@link WorkspaceContext} at connection checkout — so every seed/retrieve here sets the context first.
 */
@SpringBootTest
@Import(StubAiGatewayClient.class)
class KnowledgeRetrievalIT {

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired WorkspaceProvisioningService workspaceProvisioningService;
    @Autowired EmbeddingIndexer embeddingIndexer;
    @Autowired KnowledgeProvider knowledgeProvider;
    @Autowired OperationExecutionRecorder operationExecutionRecorder;

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
        S3Client s3 = S3Client.builder()
                .endpointOverride(URI.create(ContainerFixtures.MINIO.getS3URL()))
                .region(Region.US_EAST_1)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        ContainerFixtures.MINIO.getUserName(), ContainerFixtures.MINIO.getPassword())))
                .build();
        try {
            s3.createBucket(CreateBucketRequest.builder().bucket("d2os-artifacts").build());
        } catch (Exception ignored) { /* bucket may already exist across suites */ }
    }

    @BeforeEach
    void seedTenancy() throws Exception {
        featureId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();

        // Provision the workspace + its knowledge_item partition atomically (T008). Runs under the
        // system/admin context because the workspace table is admin-write; use a raw connection for the
        // remaining tenancy rows so app.workspace_id is set before the RLS-guarded inserts.
        WorkspaceContext.set(WORKSPACE_ID);
        workspaceProvisioningService.provisionWorkspace(WORKSPACE_ID, "knowledge-it-ws", "test");

        try (Connection c = dataSource.getConnection()) {
            exec(c, "SET app.workspace_id = '" + WORKSPACE_ID + "'");
            ins(c, "INSERT INTO project (id,workspace_id,name,created_by) VALUES (?,?,?,?)",
                    projectId, WORKSPACE_ID, "p", "t");
            ins(c, "INSERT INTO project_version (id,workspace_id,project_id,label,created_by) VALUES (?,?,?,?,?)",
                    versionId, WORKSPACE_ID, projectId, "v1", "t");
            ins(c, "INSERT INTO feature (id,workspace_id,project_version_id,name,created_by) VALUES (?,?,?,?,?)",
                    featureId, WORKSPACE_ID, versionId, "f", "t");
        }
    }

    @AfterEach
    void clearContext() {
        WorkspaceContext.clear();
    }

    @Test
    void retrievalReturnsOnlyPublishedInScopeTagMatchingItems() {
        WorkspaceContext.set(WORKSPACE_ID);

        // A WORKSPACE-scoped PUBLISHED item with the matching tag → should be retrieved.
        UUID matchId = embeddingIndexer.publish(
                WORKSPACE_ID, UUID.randomUUID(), "sec-baseline", 1, KnowledgeScope.WORKSPACE, WORKSPACE_ID,
                List.of("security"), "en", "Security baseline",
                "Encrypt data at rest and in transit; rotate keys quarterly.", null, null);

        // A WORKSPACE-scoped PUBLISHED item with a NON-matching tag → excluded by the tag predicate.
        embeddingIndexer.publish(
                WORKSPACE_ID, UUID.randomUUID(), "ux-guideline", 1, KnowledgeScope.WORKSPACE, WORKSPACE_ID,
                List.of("ux"), "en", "UX guideline",
                "Prefer progressive disclosure for complex forms.", null, null);

        // A DEPRECATED item that DOES carry the matching tag → excluded by the status predicate.
        UUID deprecatedId = embeddingIndexer.publish(
                WORKSPACE_ID, UUID.randomUUID(), "sec-legacy", 1, KnowledgeScope.WORKSPACE, WORKSPACE_ID,
                List.of("security"), "en", "Legacy security note",
                "Superseded guidance retained for audit.", null, null);
        jdbcTemplate.update("UPDATE knowledge_item SET status = 'DEPRECATED' WHERE id = ?", deprecatedId);

        List<KnowledgeProvider.InjectedItem> items = knowledgeProvider.retrieve(
                new KnowledgeProvider.KnowledgeQuery(WORKSPACE_ID, null, List.of("security"), List.of("security"), 5));

        assertEquals(1, items.size(), "only the PUBLISHED, tag-matching, in-scope item is retrievable");
        KnowledgeProvider.InjectedItem only = items.get(0);
        assertEquals(matchId, only.itemId());
        assertEquals("sec-baseline", only.key());
        assertEquals(WORKSPACE_ID, only.workspaceId());
        assertTrue(items.size() <= 5, "retrieval never exceeds the maxItems cap");
    }

    @Test
    void retrievalHonoursMaxItemsCap() {
        WorkspaceContext.set(WORKSPACE_ID);
        for (int i = 0; i < 4; i++) {
            embeddingIndexer.publish(
                    WORKSPACE_ID, UUID.randomUUID(), "risk-note-" + i, 1, KnowledgeScope.WORKSPACE, WORKSPACE_ID,
                    List.of("risk"), "en", "Risk note " + i, "Risk guidance body number " + i, null, null);
        }

        List<KnowledgeProvider.InjectedItem> capped = knowledgeProvider.retrieve(
                new KnowledgeProvider.KnowledgeQuery(WORKSPACE_ID, null, List.of("risk"), List.of("risk"), 2));

        assertEquals(2, capped.size(), "retrieval is capped at maxItems=2 even though 4 items match");
    }

    @Test
    void emptyProfileRetrievesNothing() {
        WorkspaceContext.set(WORKSPACE_ID);
        // Tag deliberately unique to this test: all four tests share the class workspace with no row
        // cleanup, so reusing "security" here would leak this PUBLISHED item into the scoping test's
        // tag-matched retrieval (observed as expected:<1> but was:<2> under JUnit's method ordering).
        embeddingIndexer.publish(
                WORKSPACE_ID, UUID.randomUUID(), "any-item", 1, KnowledgeScope.WORKSPACE, WORKSPACE_ID,
                List.of("empty-profile-fixture"), "en", "Any", "Body", null, null);

        List<KnowledgeProvider.InjectedItem> none = knowledgeProvider.retrieve(
                new KnowledgeProvider.KnowledgeQuery(WORKSPACE_ID, null, List.of(), List.of(), 5));

        assertTrue(none.isEmpty(), "a persona with no knowledge profile is entitled to nothing (no injection)");
    }

    @Test
    void recorderWritesInjectionSnapshotsForExactlyTheInjectedItems() throws Exception {
        WorkspaceContext.set(WORKSPACE_ID);

        // Two items to inject, with known key/version/hash captured from retrieval.
        embeddingIndexer.publish(
                WORKSPACE_ID, UUID.randomUUID(), "arch-principle", 1, KnowledgeScope.WORKSPACE, WORKSPACE_ID,
                List.of("architecture"), "en", "Arch principle", "Favor evolutionary architecture.", null, null);
        embeddingIndexer.publish(
                WORKSPACE_ID, UUID.randomUUID(), "arch-pattern", 1, KnowledgeScope.WORKSPACE, WORKSPACE_ID,
                List.of("architecture"), "en", "Arch pattern", "Use the strangler-fig pattern for migrations.",
                null, null);

        List<KnowledgeProvider.InjectedItem> injected = knowledgeProvider.retrieve(
                new KnowledgeProvider.KnowledgeQuery(
                        WORKSPACE_ID, null, List.of("architecture"), List.of("architecture"), 5));
        assertEquals(2, injected.size());

        // Build the minimal case → persona_invocation chain so the operation_execution FK resolves.
        UUID caseId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        UUID promptDefId = UUID.randomUUID();
        try (Connection c = dataSource.getConnection()) {
            exec(c, "SET app.workspace_id = '" + WORKSPACE_ID + "'");
            ins(c, "INSERT INTO case_instance (id,workspace_id,feature_id,submission_id,case_type_key,"
                    + "case_type_version,token_budget,created_by) VALUES (?,?,?,?,?,?,?,?)",
                    caseId, WORKSPACE_ID, featureId, UUID.randomUUID(), "initiation", "1.0.0", 1_000_000L, "t");
            ins(c, "INSERT INTO persona_invocation (id,workspace_id,case_instance_id,persona_definition_id,"
                    + "persona_definition_version,sequence_no,persona_key,status) VALUES (?,?,?,?,?,?,?,?)",
                    invocationId, WORKSPACE_ID, caseId, UUID.randomUUID(), "1.0.0", 1, "solution-architect", "running");
        }

        PersonaEnvelope envelope = new PersonaEnvelope(
                caseId, "solution-architect", UUID.randomUUID(), "1.0.0",
                promptDefId, "1.0.0", "template body", UUID.randomUUID(), "1.0.0", "{}",
                "{\"description\":\"x\"}", injected, 10, List.of(), null, List.of());

        WorkspaceContext.set(WORKSPACE_ID);   // recorder writes on the workspace-aware datasource
        var execution = operationExecutionRecorder.record(
                WORKSPACE_ID, invocationId, envelope, "rendered prompt", "stub-provider", "stub-model-1.0",
                "deterministic output text", 128L, 1, new ValidationResult(0.95, List.of(), true));

        // Snapshot rows: exactly one per injected item, exact (key,version)+hash, contiguous positions.
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT knowledge_item_id, knowledge_item_key, knowledge_item_version, content_hash, position "
                        + "FROM knowledge_injection_snapshot WHERE operation_execution_id = ? ORDER BY position",
                execution.getId());

        assertEquals(injected.size(), rows.size(), "one snapshot row per injected item");
        for (int i = 0; i < injected.size(); i++) {
            KnowledgeProvider.InjectedItem expected = injected.get(i);
            Map<String, Object> row = rows.get(i);
            assertEquals(expected.itemId(), row.get("knowledge_item_id"));
            assertEquals(expected.key(), row.get("knowledge_item_key"));
            assertEquals(expected.version(), ((Number) row.get("knowledge_item_version")).intValue());
            assertEquals(expected.contentHash(), row.get("content_hash"));
            assertEquals(i, ((Number) row.get("position")).intValue());
        }

        // The operation row's injected_knowledge column is the matching non-empty manifest (completeness).
        String injectedKnowledge = jdbcTemplate.queryForObject(
                "SELECT injected_knowledge::text FROM operation_execution WHERE id = ?",
                String.class, execution.getId());
        assertFalse(injectedKnowledge == null || injectedKnowledge.isBlank() || "[]".equals(injectedKnowledge),
                "injected_knowledge JSON must reflect the injected items, not stay empty");
    }

    private void exec(Connection c, String sql) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) { ps.execute(); }
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
