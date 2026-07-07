package com.d2os.app;

import com.d2os.app.support.StubAiGatewayClient;
import com.d2os.knowledge.EmbeddingIndexer;
import com.d2os.knowledge.KnowledgeScope;
import com.d2os.persona.gateway.KnowledgeScopeViolationException;
import com.d2os.persona.gateway.WorkspaceScopeGuard;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-workspace knowledge leakage IT (T036, Polish, SC-004, T2-b/T2-c). Two workspaces hold items
 * with IDENTICAL tags and near-identical content — the worst case for an ANN ranking — and two
 * independent layers must each block leakage on their own:
 * <ul>
 *   <li><b>Retrieval layer (T2-b)</b>: retrieval as A returns zero of B's items regardless of
 *       tag/similarity match (the mandatory workspace predicate prunes to A's partition), and a forged
 *       query asking for B's workspace under A's RLS connection context returns nothing at all
 *       (RLS blocks even when the SQL predicate is subverted);</li>
 *   <li><b>Injection seam (T2-c)</b>: handing the gateway-side guard a B item under A's scope is
 *       refused ({@link KnowledgeScopeViolationException}) AND durably audited — the
 *       {@code SCOPE_VIOLATION_BLOCKED} AuditEntry survives the refusal because it commits in its own
 *       transaction.</li>
 * </ul>
 */
@SpringBootTest
@Import(StubAiGatewayClient.class)
class KnowledgeLeakageIT {

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired WorkspaceProvisioningService workspaceProvisioningService;
    @Autowired EmbeddingIndexer embeddingIndexer;
    @Autowired KnowledgeProvider knowledgeProvider;
    @Autowired WorkspaceScopeGuard workspaceScopeGuard;

    private static final UUID WORKSPACE_A = UUID.randomUUID();
    private static final UUID WORKSPACE_B = UUID.randomUUID();

    private UUID itemAId;
    private UUID itemBId;
    private String itemBContent;
    private String itemBHash;

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
    void seedBothWorkspaces() {
        // Provision both workspaces (creates each knowledge_item partition) and publish items with the
        // SAME tag and near-identical content, so nothing but workspace isolation separates them.
        WorkspaceContext.set(WORKSPACE_A);
        workspaceProvisioningService.provisionWorkspace(WORKSPACE_A, "leak-ws-a", "test");
        itemAId = publishOnce(WORKSPACE_A, "compliance-note-a",
                "Compliance guidance: encrypt data at rest and rotate keys quarterly.");

        WorkspaceContext.set(WORKSPACE_B);
        workspaceProvisioningService.provisionWorkspace(WORKSPACE_B, "leak-ws-b", "test");
        itemBContent = "Compliance guidance: encrypt data at rest and rotate keys monthly.";
        itemBId = publishOnce(WORKSPACE_B, "compliance-note-b", itemBContent);
        WorkspaceContext.set(WORKSPACE_B);
        itemBHash = jdbcTemplate.queryForObject(
                "SELECT content_hash FROM knowledge_item WHERE id = ?", String.class, itemBId);
    }

    /**
     * Publish one PUBLISHED, workspace-scoped, 'compliance'-tagged item. Idempotent across the class's
     * tests (each @BeforeEach re-seeds the same static workspaces): if the {@code (key, version)} already
     * exists, return the existing id instead of violating {@code uq_knowledge_item_key_version}.
     */
    private UUID publishOnce(UUID workspaceId, String key, String content) {
        WorkspaceContext.set(workspaceId);
        List<UUID> existing = jdbcTemplate.queryForList(
                "SELECT id FROM knowledge_item WHERE workspace_id = ? AND key = ? AND version = 1",
                UUID.class, workspaceId, key);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        return embeddingIndexer.publish(
                workspaceId, UUID.randomUUID(), key, 1, KnowledgeScope.WORKSPACE, workspaceId,
                List.of("compliance"), "en", "Compliance note", content, null, null);
    }

    @AfterEach
    void clearContext() {
        WorkspaceContext.clear();
    }

    @Test
    void retrievalNeverReturnsForeignItemsDespiteIdenticalTagsAndSimilarContent() {
        // Layer 1a (T2-b): retrieval as A — B's item matches the tag and is nearly identical content,
        // yet only A's item comes back (workspace predicate + partition pruning).
        WorkspaceContext.set(WORKSPACE_A);
        List<KnowledgeProvider.InjectedItem> forA = knowledgeProvider.retrieve(
                new KnowledgeProvider.KnowledgeQuery(
                        WORKSPACE_A, null, List.of("compliance"), List.of("compliance"), 10));
        assertEquals(1, forA.size(), "A retrieves exactly its own item");
        assertEquals(itemAId, forA.get(0).itemId());
        assertTrue(forA.stream().noneMatch(i -> i.itemId().equals(itemBId)),
                "zero of B's items leak into A's retrieval (SC-004)");

        // ...and symmetrically for B.
        WorkspaceContext.set(WORKSPACE_B);
        List<KnowledgeProvider.InjectedItem> forB = knowledgeProvider.retrieve(
                new KnowledgeProvider.KnowledgeQuery(
                        WORKSPACE_B, null, List.of("compliance"), List.of("compliance"), 10));
        assertEquals(1, forB.size(), "B retrieves exactly its own item");
        assertEquals(itemBId, forB.get(0).itemId());

        // Layer 1b (RLS backstop): a FORGED query asking for B's workspace while the connection is
        // stamped with A's RLS context returns nothing — even with the SQL predicate subverted, RLS
        // hides B's rows from an A-context connection. Both layers block independently.
        WorkspaceContext.set(WORKSPACE_A);
        List<KnowledgeProvider.InjectedItem> forged = knowledgeProvider.retrieve(
                new KnowledgeProvider.KnowledgeQuery(
                        WORKSPACE_B, null, List.of("compliance"), List.of("compliance"), 10));
        assertTrue(forged.isEmpty(),
                "RLS returns zero rows for a forged cross-workspace query under A's connection context");
    }

    @Test
    void gatewaySeamRefusesAndAuditsForeignItemUnderLocalScope() {
        // Layer 2 (T2-c): simulate a retrieval bug by handing the envelope seam B's REAL item under A's
        // scope. The guard must refuse (throw) and durably audit the attempt.
        KnowledgeProvider.InjectedItem foreign = new KnowledgeProvider.InjectedItem(
                WORKSPACE_B, itemBId, "compliance-note-b", 1, itemBContent, itemBHash);

        WorkspaceContext.set(WORKSPACE_A);
        assertThrows(KnowledgeScopeViolationException.class,
                () -> workspaceScopeGuard.assertSameWorkspace(WORKSPACE_A, List.of(foreign)),
                "a B item under A's scope is refused at the injection seam");

        // The audit committed in its own transaction (REQUIRES_NEW), so it survives the refusal. It is
        // written under A's workspace (the caller whose scope was violated), so it is visible to A.
        Long audited = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_entry WHERE workspace_id = ? "
                        + "AND action = 'SCOPE_VIOLATION_BLOCKED' AND subject_id = ?",
                Long.class, WORKSPACE_A, itemBId);
        assertEquals(1L, audited, "the blocked cross-workspace attempt is durably audited (T2-c)");

        // Sanity: the guard passes items that DO belong to the caller's workspace.
        WorkspaceContext.set(WORKSPACE_A);
        List<KnowledgeProvider.InjectedItem> own = knowledgeProvider.retrieve(
                new KnowledgeProvider.KnowledgeQuery(
                        WORKSPACE_A, null, List.of("compliance"), List.of("compliance"), 10));
        workspaceScopeGuard.assertSameWorkspace(WORKSPACE_A, own);   // must not throw
    }
}
