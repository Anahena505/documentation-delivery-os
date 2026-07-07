package com.d2os.app;

import com.d2os.app.support.StubAiGatewayClient;
import com.d2os.testsupport.ContainerFixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
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

import javax.sql.DataSource;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US4 load-posture verification (T039, SC-005/006, NFR-1/2/3). Tagged {@code load}, so it is excluded
 * from the default test task and run explicitly with {@code gradle :app:loadTest}.
 *
 * <p>Drives 50 concurrent active Cases in workspace A plus 10 in workspace B (isolation under load)
 * through the full 14-step suite, against real Postgres + MinIO with the stub gateway on a modest
 * latency profile. Asserts: zero stalled/dropped Cases, per-operation p95 within bound, ≤5 s progress
 * cadence per Case, workspace-B isolation, and throughput ≥ 200 cases/month. Writes {@code build/load-report.md}.
 */
@Tag("load")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.context.annotation.Import(StubAiGatewayClient.class)
class LoadPostureIT {

    private static final int CASES_A = 50;
    private static final int CASES_B = 10;
    private static final UUID WORKSPACE_A = UUID.randomUUID();
    private static final UUID WORKSPACE_B = UUID.randomUUID();

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired DataSource dataSource;
    @Autowired StubAiGatewayClient.LatencyControllableGateway gateway;

    // One feature per case: the one-active-mutating-Case-per-Feature invariant (FR-016) forbids
    // multiple concurrent cases on the same feature.
    private final Map<UUID, List<UUID>> featuresByWorkspace = new ConcurrentHashMap<>();

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
        // A larger Hikari pool so 50 concurrent cases + parallel specialist transactions don't starve.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "40");
        // Heartbeat below the 5 s bound with margin.
        registry.add("d2os.casecore.progress.heartbeat-interval-ms", () -> "3000");
    }

    @BeforeAll
    static void bucket() {
        ContainerFixtures.startAll();
        S3Client s3 = S3Client.builder()
                .endpointOverride(URI.create(ContainerFixtures.MINIO.getS3URL()))
                .region(Region.US_EAST_1)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(ContainerFixtures.MINIO.getUserName(), ContainerFixtures.MINIO.getPassword())))
                .build();
        try {
            s3.createBucket(CreateBucketRequest.builder().bucket("d2os-artifacts").build());
        } catch (Exception ignored) {
        }
    }

    @Test
    void fiftyConcurrentCasesHoldTheLoadPosture() throws Exception {
        gateway.reset();
        gateway.withLatency(20, 80);   // modest, realistic pressure without a multi-hour run
        seedTenancy(WORKSPACE_A, CASES_A);
        seedTenancy(WORKSPACE_B, CASES_B);

        Instant runStart = Instant.now();

        // Kick off all cases as fast as possible so they are genuinely concurrently active — one per
        // feature (FR-016), so distinct features per case.
        List<String> casesA = new ArrayList<>();
        List<String> casesB = new ArrayList<>();
        for (int i = 0; i < CASES_A; i++) casesA.add(startCase(WORKSPACE_A, featuresByWorkspace.get(WORKSPACE_A).get(i)));
        for (int i = 0; i < CASES_B; i++) casesB.add(startCase(WORKSPACE_B, featuresByWorkspace.get(WORKSPACE_B).get(i)));

        // Wait for every case to reach Delivered — zero stalls/drops is the core load invariant.
        int total = casesA.size() + casesB.size();
        boolean allDelivered = awaitAllDelivered(casesA, casesB, Duration.ofSeconds(600));

        Duration wall = Duration.between(runStart, Instant.now());

        long deliveredA = countDeliveredIn(WORKSPACE_A, casesA);
        long deliveredB = countDeliveredIn(WORKSPACE_B, casesB);
        double[] opP95 = operationLatencyP95(WORKSPACE_A);
        long maxGapMillis = maxProgressGapMillis(WORKSPACE_A);
        long heartbeats = heartbeatCount(WORKSPACE_A);
        double perMonth = total / (wall.toMillis() / 1000.0) * 60 * 60 * 24 * 30;

        writeReport(total, deliveredA, deliveredB, wall, opP95, maxGapMillis, heartbeats, perMonth);

        // --- assertions (SC-005/006, NFR-1/2/3) ---
        assertTrue(allDelivered,
                "all cases should deliver with no stall/drop; deliveredA=" + deliveredA + "/" + CASES_A
                        + " deliveredB=" + deliveredB + "/" + CASES_B);
        assertTrue(deliveredB == CASES_B, "workspace B must be unaffected by workspace A load (isolation)");
        // Per-operation p95 (seconds) well within the ≤10 min (600 s) NFR-3 bound.
        assertTrue(opP95[1] <= 600.0, "per-operation p95 " + opP95[1] + "s exceeds the 600s bound");
        // Progress cadence: no Running case goes >5 s without an event (heartbeat guarantees liveness).
        assertTrue(maxGapMillis <= 5_000, "max progress gap " + maxGapMillis + "ms exceeds the 5s cadence bound");
        assertTrue(heartbeats > 0, "expected heartbeat events under load");
        assertTrue(perMonth >= 200, "extrapolated throughput " + Math.round(perMonth) + "/month below 200");
    }

    // ---- driving --------------------------------------------------------------------------------

    private String startCase(UUID workspace, UUID featureId) {
        HttpHeaders h = headers(workspace);
        ResponseEntity<Map> sub = rest.exchange(url("/api/v1/submissions"), HttpMethod.POST,
                new HttpEntity<>(Map.of("formData", Map.of("category", "initiation", "description", "load")), h), Map.class);
        String submissionId = (String) sub.getBody().get("id");
        rest.exchange(url("/api/v1/submissions/" + submissionId + "/confirm-classification"), HttpMethod.POST,
                new HttpEntity<>(Map.of("confirmedCaseType", "initiation"), h), Map.class);
        ResponseEntity<Map> caseResp = rest.exchange(url("/api/v1/cases"), HttpMethod.POST,
                new HttpEntity<>(Map.of("submissionId", submissionId, "featureId", featureId.toString()), h), Map.class);
        if (caseResp.getStatusCode().value() != 201 || caseResp.getBody().get("id") == null) {
            throw new IllegalStateException("case create failed: " + caseResp.getStatusCode() + " " + caseResp.getBody());
        }
        String caseId = (String) caseResp.getBody().get("id");
        rest.exchange(url("/api/v1/cases/" + caseId + "/start"), HttpMethod.POST, new HttpEntity<>(null, h), Void.class);
        return caseId;
    }

    private boolean awaitAllDelivered(List<String> casesA, List<String> casesB, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (countDeliveredIn(WORKSPACE_A, casesA) == casesA.size()
                    && countDeliveredIn(WORKSPACE_B, casesB) == casesB.size()) {
                return true;
            }
            Thread.sleep(2000);
        }
        return false;
    }

    // ---- measurement ----------------------------------------------------------------------------

    /** Delivered count for a workspace's cases, queried under that workspace's RLS context. */
    private long countDeliveredIn(UUID workspace, List<String> caseIds) throws Exception {
        if (caseIds.isEmpty()) return 0;
        try (Connection conn = conn(workspace);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM case_instance WHERE id IN (" + inClause(caseIds) + ") AND status = 'Delivered'");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    /** [p50, p95] of per-operation durations (STEP_STARTED→STEP_COMPLETED) in seconds. */
    private double[] operationLatencyP95(UUID workspace) throws Exception {
        String sql = """
                WITH steps AS (
                  SELECT case_id, activity_id,
                         max(created_at) FILTER (WHERE kind='STEP_COMPLETED')
                           - min(created_at) FILTER (WHERE kind='STEP_STARTED') AS dur
                  FROM progress_event
                  WHERE kind IN ('STEP_STARTED','STEP_COMPLETED') AND activity_id IS NOT NULL
                  GROUP BY case_id, activity_id)
                SELECT coalesce(percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM dur)),0),
                       coalesce(percentile_cont(0.95) WITHIN GROUP (ORDER BY extract(epoch FROM dur)),0)
                FROM steps WHERE dur IS NOT NULL""";
        try (Connection conn = conn(workspace); PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? new double[]{rs.getDouble(1), rs.getDouble(2)} : new double[]{0, 0};
        }
    }

    /** Largest gap (ms) between consecutive progress events for any single case. */
    private long maxProgressGapMillis(UUID workspace) throws Exception {
        String sql = """
                WITH g AS (
                  SELECT case_id, created_at,
                         lag(created_at) OVER (PARTITION BY case_id ORDER BY id) AS prev
                  FROM progress_event)
                SELECT coalesce(max(extract(epoch FROM (created_at - prev)) * 1000), 0)
                FROM g WHERE prev IS NOT NULL""";
        try (Connection conn = conn(workspace); PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? (long) rs.getDouble(1) : 0L;
        }
    }

    private long heartbeatCount(UUID workspace) throws Exception {
        return scalarLong("SELECT count(*) FROM progress_event WHERE kind = 'HEARTBEAT'");
    }

    private void writeReport(int total, long deliveredA, long deliveredB, Duration wall,
                             double[] opP95, long maxGapMillis, long heartbeats, double perMonth) throws Exception {
        String md = """
                # Load Posture Report (T039)

                | Metric | Value |
                |---|---|
                | Cases (A/B) | %d / %d |
                | Delivered (A) | %d / %d |
                | Delivered (B) | %d / %d |
                | Wall time | %.1f s |
                | Per-op latency p50 / p95 | %.2f s / %.2f s |
                | Max progress gap | %d ms |
                | Heartbeat events | %d |
                | Extrapolated throughput | %d cases/month |
                """.formatted(CASES_A, CASES_B, deliveredA, (long) CASES_A, deliveredB, (long) CASES_B,
                wall.toMillis() / 1000.0, opP95[0], opP95[1], maxGapMillis, heartbeats, Math.round(perMonth));
        Path out = Path.of("build", "load-report.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, md);
    }

    // ---- db + http helpers ----------------------------------------------------------------------

    private void seedTenancy(UUID workspace, int featureCount) throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        List<UUID> features = new ArrayList<>();
        try (Connection conn = conn(workspace)) {
            insert(conn, "INSERT INTO workspace (id, name, created_by) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                    workspace, "load-ws", "test");
            insert(conn, "INSERT INTO project (id, workspace_id, name, created_by) VALUES (?, ?, ?, ?)",
                    projectId, workspace, "load-proj", "test");
            insert(conn, "INSERT INTO project_version (id, workspace_id, project_id, label, created_by) VALUES (?, ?, ?, ?, ?)",
                    versionId, workspace, projectId, "v1", "test");
            for (int i = 0; i < featureCount; i++) {
                UUID featureId = UUID.randomUUID();
                insert(conn, "INSERT INTO feature (id, workspace_id, project_version_id, name, created_by) VALUES (?, ?, ?, ?, ?)",
                        featureId, workspace, versionId, "load-feat-" + i, "test");
                features.add(featureId);
            }
        }
        featuresByWorkspace.put(workspace, features);
    }

    private Connection conn(UUID workspace) throws Exception {
        Connection conn = dataSource.getConnection();
        try (PreparedStatement ps = conn.prepareStatement("SET app.workspace_id = '" + workspace + "'")) {
            ps.execute();
        }
        return conn;
    }

    private long scalarLong(String sql) throws Exception {
        try (Connection conn = conn(WORKSPACE_A); PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private String inClause(List<String> ids) {
        return String.join(",", ids.stream().map(id -> "'" + id + "'").toList());
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

    private HttpHeaders headers(UUID workspace) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Workspace-Id", workspace.toString());
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
