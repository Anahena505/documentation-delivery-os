package com.d2os.app;

import com.d2os.app.support.StubAiGatewayClient;
import com.d2os.knowledge.DeprecationService;
import com.d2os.knowledge.EmbeddingIndexer;
import com.d2os.knowledge.KnowledgeAffectedExecutionRepository;
import com.d2os.knowledge.KnowledgeScope;
import com.d2os.persona.OperationExecutionRecorder;
import com.d2os.persona.PersonaEnvelope;
import com.d2os.persona.ValidationResult;
import com.d2os.persona.spi.KnowledgeProvider;
import com.d2os.replay.ReplayHarness;
import com.d2os.replay.ReplayReport;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deprecation IT (T031, US3, SC-007). Records two knowledge-injected operations — one injecting the item
 * to be deprecated, one injecting a DIFFERENT item as a control — then deprecates the first item and asserts:
 * <ul>
 *   <li>the deprecated item drops out of NEW retrievals (FR-014) while the control item stays retrievable;</li>
 *   <li>{@code knowledge_affected_execution} flags EXACTLY the referencing execution — one row, pointing at
 *       the injecting operation, and the service's returned count equals the persisted flag count (FR-015);</li>
 *   <li>the flagged execution's injection snapshot + output hash are byte-unchanged by the deprecation
 *       (history is never rewritten, FR-016);</li>
 *   <li>replaying the flagged execution's case still reproduces its output byte-identically (SC-007).</li>
 * </ul>
 */
@SpringBootTest
@Import(StubAiGatewayClient.class)
class DeprecationIT {

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired WorkspaceProvisioningService workspaceProvisioningService;
    @Autowired EmbeddingIndexer embeddingIndexer;
    @Autowired KnowledgeProvider knowledgeProvider;
    @Autowired OperationExecutionRecorder operationExecutionRecorder;
    @Autowired DeprecationService deprecationService;
    @Autowired KnowledgeAffectedExecutionRepository affectedRepository;
    @Autowired ReplayHarness replayHarness;

    private static final UUID WORKSPACE_ID = UUID.randomUUID();
    private UUID versionId;

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
        versionId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();

        WorkspaceContext.set(WORKSPACE_ID);
        workspaceProvisioningService.provisionWorkspace(WORKSPACE_ID, "knowledge-deprecation-ws", "test");

        try (Connection c = dataSource.getConnection()) {
            exec(c, "SET app.workspace_id = '" + WORKSPACE_ID + "'");
            ins(c, "INSERT INTO project (id,workspace_id,name,created_by) VALUES (?,?,?,?)",
                    projectId, WORKSPACE_ID, "p", "t");
            ins(c, "INSERT INTO project_version (id,workspace_id,project_id,label,created_by) VALUES (?,?,?,?,?)",
                    versionId, WORKSPACE_ID, projectId, "v1", "t");
        }
    }

    @AfterEach
    void clearContext() {
        WorkspaceContext.clear();
    }

    @Test
    void deprecationFlagsExactlyReferencingExecutionsWithoutRewritingHistory() throws Exception {
        WorkspaceContext.set(WORKSPACE_ID);

        // The item to deprecate, and a control item injected into a DIFFERENT operation.
        UUID doomedId = embeddingIndexer.publish(
                WORKSPACE_ID, UUID.randomUUID(), "policy-doomed", 1, KnowledgeScope.WORKSPACE, WORKSPACE_ID,
                List.of("governance"), "en", "Doomed policy", "Guidance that will be retired.", null, null);
        embeddingIndexer.publish(
                WORKSPACE_ID, UUID.randomUUID(), "policy-control", 1, KnowledgeScope.WORKSPACE, WORKSPACE_ID,
                List.of("control"), "en", "Control policy", "Guidance that will survive.", null, null);

        List<KnowledgeProvider.InjectedItem> doomed = knowledgeProvider.retrieve(
                new KnowledgeProvider.KnowledgeQuery(WORKSPACE_ID, null, List.of("governance"), List.of("governance"), 5));
        assertEquals(1, doomed.size(), "the doomed item is retrievable before deprecation");
        List<KnowledgeProvider.InjectedItem> control = knowledgeProvider.retrieve(
                new KnowledgeProvider.KnowledgeQuery(WORKSPACE_ID, null, List.of("control"), List.of("control"), 5));
        assertEquals(1, control.size());

        // Referencing execution (injects the doomed item) — this is the one that must be flagged.
        UUID refCaseId = recordOperationInjecting("risk-governance-officer", doomed);
        UUID refExecutionId = jdbcTemplate.queryForObject(
                "SELECT oe.id FROM operation_execution oe JOIN persona_invocation pi ON pi.id = oe.persona_invocation_id "
                        + "WHERE pi.case_instance_id = ?", UUID.class, refCaseId);

        // Control execution (injects only the surviving item) — must NOT be flagged.
        recordOperationInjecting("solution-architect", control);

        // Capture the referencing execution's pre-deprecation snapshot hash + output hash to prove immutability.
        String snapHashBefore = jdbcTemplate.queryForObject(
                "SELECT content_hash FROM knowledge_injection_snapshot WHERE operation_execution_id = ?",
                String.class, refExecutionId);
        String outputHashBefore = jdbcTemplate.queryForObject(
                "SELECT output_hash FROM operation_execution WHERE id = ?", String.class, refExecutionId);

        // Deprecate the doomed item.
        WorkspaceContext.set(WORKSPACE_ID);
        DeprecationService.DeprecationResult result =
                deprecationService.deprecateItem(doomedId, "superseded by new policy", "curator");

        // 1) Item retired; excluded from NEW retrieval; control item still retrievable.
        assertEquals(List.of(doomedId), result.deprecatedItemIds());
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM knowledge_item WHERE id = ?", String.class, doomedId);
        assertEquals("DEPRECATED", status);
        assertTrue(knowledgeProvider.retrieve(new KnowledgeProvider.KnowledgeQuery(
                        WORKSPACE_ID, null, List.of("governance"), List.of("governance"), 5)).isEmpty(),
                "deprecated item drops out of new retrieval (FR-014)");
        assertFalse(knowledgeProvider.retrieve(new KnowledgeProvider.KnowledgeQuery(
                        WORKSPACE_ID, null, List.of("control"), List.of("control"), 5)).isEmpty(),
                "the surviving control item is unaffected");

        // 2) EXACTLY the referencing execution is flagged; the service count equals the persisted flag count.
        long persistedFlags = affectedRepository.countByKnowledgeItemKeyAndKnowledgeItemVersion("policy-doomed", 1);
        assertEquals(1, result.flaggedExecutionCount(), "exactly one execution referenced the deprecated item");
        assertEquals(persistedFlags, result.flaggedExecutionCount(),
                "the endpoint's flagged count matches the persisted knowledge_affected_execution rows");
        UUID flaggedExecutionId = jdbcTemplate.queryForObject(
                "SELECT operation_execution_id FROM knowledge_affected_execution WHERE knowledge_item_key = 'policy-doomed'",
                UUID.class);
        assertEquals(refExecutionId, flaggedExecutionId, "the flag points at the injecting execution, not the control");
        String reviewStatus = jdbcTemplate.queryForObject(
                "SELECT review_status FROM knowledge_affected_execution WHERE operation_execution_id = ?",
                String.class, refExecutionId);
        assertEquals("OPEN", reviewStatus, "a fresh flag starts OPEN for review");

        // 3) History is never rewritten: the snapshot + output hashes are byte-unchanged by the deprecation.
        String snapHashAfter = jdbcTemplate.queryForObject(
                "SELECT content_hash FROM knowledge_injection_snapshot WHERE operation_execution_id = ?",
                String.class, refExecutionId);
        String outputHashAfter = jdbcTemplate.queryForObject(
                "SELECT output_hash FROM operation_execution WHERE id = ?", String.class, refExecutionId);
        assertEquals(snapHashBefore, snapHashAfter, "deprecation must not touch the injection snapshot");
        assertEquals(outputHashBefore, outputHashAfter, "deprecation must not touch the recorded output");

        // 4) Replay of the flagged execution's case still reproduces the output byte-identically (SC-007).
        WorkspaceContext.set(WORKSPACE_ID);
        ReplayReport report = replayHarness.replay(refCaseId);
        assertEquals(1, report.totalOperations());
        assertEquals(0, report.mismatched(), "no operation may mismatch on replay after deprecation");
        assertEquals(1, report.matched(), "the flagged, snapshotted operation still replays byte-identically");
        ReplayReport.OperationResult op = report.results().get(0);
        assertEquals(refExecutionId, op.operationExecutionId());
        assertTrue(op.byteIdentical(), "recorded output reconstructs byte-identically despite the deprecation");
        assertTrue(op.knowledgeContextReproduced(),
                "injected context reconstructs from the snapshot even though the item is now DEPRECATED (SC-007)");

        // 5) Reviewing a flag flips OPEN→REVIEWED and is audited — flag-only, execution untouched (T043).
        UUID flagId = jdbcTemplate.queryForObject(
                "SELECT id FROM knowledge_affected_execution WHERE operation_execution_id = ?",
                UUID.class, refExecutionId);
        WorkspaceContext.set(WORKSPACE_ID);
        deprecationService.reviewAffectedExecution(flagId, "governance-reviewer");
        assertEquals("REVIEWED", jdbcTemplate.queryForObject(
                        "SELECT review_status FROM knowledge_affected_execution WHERE id = ?", String.class, flagId),
                "review flips the flag OPEN→REVIEWED");
        assertEquals(1L, (long) jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM audit_entry WHERE workspace_id = ? AND subject_id = ? "
                                + "AND action = 'AFFECTED_EXECUTION_REVIEWED'", Long.class, WORKSPACE_ID, flagId),
                "the acknowledgement is durably audited (T043)");
        // The flagged execution's output is STILL byte-unchanged after the review acknowledgement.
        assertEquals(outputHashBefore, jdbcTemplate.queryForObject(
                        "SELECT output_hash FROM operation_execution WHERE id = ?", String.class, refExecutionId),
                "review must not touch the flagged execution's output");
    }

    /**
     * Records one operation that injects the given items into a fresh case, returning the case id.
     * Each call seeds its OWN feature: {@code uq_active_mutating_case_per_feature} (V4) allows only one
     * active mutating case per feature, and this test records two operations (referencing + control).
     */
    private UUID recordOperationInjecting(String personaKey, List<KnowledgeProvider.InjectedItem> injected)
            throws Exception {
        UUID featureId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        try (Connection c = dataSource.getConnection()) {
            exec(c, "SET app.workspace_id = '" + WORKSPACE_ID + "'");
            ins(c, "INSERT INTO feature (id,workspace_id,project_version_id,name,created_by) VALUES (?,?,?,?,?)",
                    featureId, WORKSPACE_ID, versionId, "f-" + personaKey, "t");
            ins(c, "INSERT INTO case_instance (id,workspace_id,feature_id,submission_id,case_type_key,"
                    + "case_type_version,token_budget,created_by) VALUES (?,?,?,?,?,?,?,?)",
                    caseId, WORKSPACE_ID, featureId, UUID.randomUUID(), "initiation", "1.0.0", 1_000_000L, "t");
            ins(c, "INSERT INTO persona_invocation (id,workspace_id,case_instance_id,persona_definition_id,"
                    + "persona_definition_version,sequence_no,persona_key,status) VALUES (?,?,?,?,?,?,?,?)",
                    invocationId, WORKSPACE_ID, caseId, UUID.randomUUID(), "1.0.0", 1, personaKey, "running");
        }

        PersonaEnvelope envelope = new PersonaEnvelope(
                caseId, personaKey, UUID.randomUUID(), "1.0.0",
                UUID.randomUUID(), "1.0.0", "template", UUID.randomUUID(), "1.0.0", "{}",
                "{\"description\":\"x\"}", injected, 10, List.of());

        WorkspaceContext.set(WORKSPACE_ID);
        operationExecutionRecorder.record(
                WORKSPACE_ID, invocationId, envelope, "rendered prompt", "stub-provider", "stub-model-1.0",
                "deterministic output for " + personaKey, 128L, 1, new ValidationResult(0.95, List.of(), true));
        return caseId;
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
