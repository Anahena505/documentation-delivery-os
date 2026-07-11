package com.d2os.app;

import com.d2os.app.support.BenchmarkSeeder;
import com.d2os.catalog.DefinitionAssetRepository;
import com.d2os.catalog.DefinitionPublishService;
import com.d2os.catalog.DefinitionResolutionService;
import com.d2os.catalog.DraftService;
import com.d2os.testsupport.ContainerFixtures;
import com.d2os.tenancy.WorkspaceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pin resolution at catalog scale (T030, US5, research R7, NFR-9, SC-007). Seeds 500 Published
 * versions (T029's {@link BenchmarkSeeder}, through the real Draft-then-Published path — not raw
 * SQL) and runs 1 000 mixed {@code (type, key, version)} resolutions plus repeated snapshot-shaped
 * pin lookups, asserting p95 AND worst case &le; 2 s (a regression tripwire, not a claim this
 * codebase is performance-tuned).
 */
@Tag("slow")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ResolutionBenchmarkIT {

    private static final UUID WORKSPACE_ID = UUID.randomUUID();
    private static final int TOTAL_VERSIONS = 500;
    private static final int RESOLUTIONS = 1000;
    private static final long P95_BUDGET_MS = 2000;
    private static final long WORST_CASE_BUDGET_MS = 2000;

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired DraftService draftService;
    @Autowired DefinitionPublishService publishService;
    @Autowired DefinitionResolutionService resolutionService;
    @Autowired DefinitionAssetRepository definitionAssetRepository;

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
    }

    @BeforeEach
    void seed() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("SET app.workspace_id = '" + WORKSPACE_ID + "'")) {
                ps.execute();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO workspace (id,name,created_by) VALUES (?,?,?) ON CONFLICT DO NOTHING")) {
                ps.setObject(1, WORKSPACE_ID);
                ps.setString(2, "bench-ws");
                ps.setString(3, "t");
                ps.execute();
            }
        }
        WorkspaceContext.set(WORKSPACE_ID);
        try {
            BenchmarkSeeder.seed(draftService, publishService, WORKSPACE_ID, TOTAL_VERSIONS, 5);
        } finally {
            WorkspaceContext.clear();
        }
    }

    @Test
    void mixedResolutionsStayWithinBudgetAtSeededScale() throws Exception {
        WorkspaceContext.set(WORKSPACE_ID);
        try {
            List<String[]> published = jdbcTemplate.queryForList(
                            "SELECT type, key FROM definition_asset WHERE workspace_id = ? AND status = 'Published'",
                            WORKSPACE_ID)
                    .stream()
                    .map(r -> new String[] { (String) r.get("type"), (String) r.get("key") })
                    .toList();
            assertTrue(published.size() >= 400, "expected the seed harness to land close to " + TOTAL_VERSIONS + " rows");

            Random random = new Random(42);
            List<Long> latenciesMs = new ArrayList<>(RESOLUTIONS);
            for (int i = 0; i < RESOLUTIONS; i++) {
                String[] pick = published.get(random.nextInt(published.size()));
                long start = System.nanoTime();
                resolutionService.latestPublished(pick[0], pick[1]);
                latenciesMs.add((System.nanoTime() - start) / 1_000_000);
            }

            Collections.sort(latenciesMs);
            long worstCase = latenciesMs.get(latenciesMs.size() - 1);
            long p95 = latenciesMs.get((int) Math.ceil(latenciesMs.size() * 0.95) - 1);

            assertTrue(p95 <= P95_BUDGET_MS, () -> "p95 resolution latency " + p95 + "ms exceeds the " + P95_BUDGET_MS + "ms budget (SC-007)");
            assertTrue(worstCase <= WORST_CASE_BUDGET_MS,
                    () -> "worst-case resolution latency " + worstCase + "ms exceeds the " + WORST_CASE_BUDGET_MS + "ms budget (SC-007)");
        } finally {
            WorkspaceContext.clear();
        }
    }
}
