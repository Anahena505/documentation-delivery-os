package com.d2os.app;

import com.d2os.app.support.StubAiGatewayClient;
import com.d2os.testsupport.ContainerFixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * US2 verification (T026, SC-003): the four analysis specialists run in parallel, the join waits for
 * all four, and a single-branch escalation retains its siblings and is releasable by a human.
 *
 * <p>Two scenarios share the setup: (1) latency-instrumented run proves the specialist operations
 * overlap in wall-clock time and that no post-join persona starts until every branch completes;
 * (2) one specialist is forced to fail, and we assert the branch parks (Case Escalated) while its
 * siblings finish, then {@code /branches/{id}/resolve} releases the join and the Case delivers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.context.annotation.Import(StubAiGatewayClient.class)
class ParallelExecutionIT {

    private static final List<String> SPECIALISTS =
            List.of("security-architect", "ux-architect", "data-architect", "infrastructure-engineer");

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired DataSource dataSource;
    @Autowired StubAiGatewayClient.LatencyControllableGateway gateway;

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
        // Fast reconciliation so a transient parallel-join conflict (Flowable optimistic locking under
        // exclusive=false) is repaired in seconds during the test rather than after the 60s default.
        registry.add("d2os.orchestration.reconciliation-interval-ms", () -> "3000");
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

    @BeforeEach
    void setup() throws Exception {
        gateway.reset();
        UUID projectId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        featureId = UUID.randomUUID();
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "SET app.workspace_id = '" + WORKSPACE_ID + "'");
            insert(conn, "INSERT INTO workspace (id, name, created_by) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                    WORKSPACE_ID, "it-ws", "test");
            insert(conn, "INSERT INTO project (id, workspace_id, name, created_by) VALUES (?, ?, ?, ?)",
                    projectId, WORKSPACE_ID, "it-proj", "test");
            insert(conn, "INSERT INTO project_version (id, workspace_id, project_id, label, created_by) VALUES (?, ?, ?, ?, ?)",
                    versionId, WORKSPACE_ID, projectId, "v1", "test");
            insert(conn, "INSERT INTO feature (id, workspace_id, project_version_id, name, created_by) VALUES (?, ?, ?, ?, ?)",
                    featureId, WORKSPACE_ID, versionId, "it-feat", "test");
        }
    }

    @Test
    void specialistsOverlapAndJoinWaitsForAll() throws Exception {
        gateway.withLatency(1200, 2000);   // make the four specialists take measurable, overlapping time
        HttpHeaders h = headers();

        String caseId = submitOpenAndStart(h);
        String st = pollStatus(caseId, h, Duration.ofSeconds(180), "Delivered");
        assertEquals("Delivered", st,
                () -> "case should deliver through the parallel block\n" + dumpDiagnostics(caseId));

        // SC-003a: the four specialist operations overlap in wall-clock time (not strictly sequential).
        Map<String, long[]> windows = new HashMap<>();
        for (String s : SPECIALISTS) {
            long start = progressTs(caseId, "STEP_STARTED", s);
            long end = progressTs(caseId, "STEP_COMPLETED", s);
            assertTrue(start > 0 && end > 0, () -> "missing progress window for " + s);
            windows.put(s, new long[]{start, end});
        }
        int overlappingPairs = 0;
        for (int i = 0; i < SPECIALISTS.size(); i++) {
            for (int j = i + 1; j < SPECIALISTS.size(); j++) {
                long[] a = windows.get(SPECIALISTS.get(i));
                long[] b = windows.get(SPECIALISTS.get(j));
                if (Math.max(a[0], b[0]) < Math.min(a[1], b[1])) overlappingPairs++;
            }
        }
        assertTrue(overlappingPairs >= 1,
                "expected the specialists to overlap in time (parallel), but none did — windows=" + describe(windows));

        // SC-003b: the join waited — no post-join persona started before every specialist finished.
        long latestSpecialistEnd = windows.values().stream().mapToLong(w -> w[1]).max().orElse(0);
        long qaStart = progressTs(caseId, "STEP_STARTED", "qa-test-strategist");
        assertTrue(qaStart >= latestSpecialistEnd,
                "qa-test-strategist started before all specialists completed — join did not wait");

        // Fork/join instrumentation is present.
        assertTrue(progressTs(caseId, "BRANCH_FORKED", "analysis-specialists") > 0, "expected BRANCH_FORKED");
        assertTrue(progressTs(caseId, "BRANCH_JOINED", "analysis-specialists") > 0, "expected BRANCH_JOINED");
    }

    @Test
    void failedBranchEscalatesRetainsSiblingsAndResumes() throws Exception {
        gateway.failFor("Security Architect");   // only the security specialist fails validation
        HttpHeaders h = headers();

        String caseId = submitOpenAndStart(h);

        // The failed branch escalates the Case while siblings keep running.
        assertEquals("Escalated", pollStatus(caseId, h, Duration.ofSeconds(150), "Escalated"),
                () -> "a failed specialist branch should escalate the case\n" + dumpDiagnostics(caseId));

        // Wait until the specialist block has joined and parked at its escalation wait state — only
        // then can a human resolution release it. BRANCH_JOINED commits with the token reaching the
        // wait receiveTask, so its visibility guarantees the branch is parked (avoids a resolve race).
        assertTrue(waitForProgress(caseId, "BRANCH_JOINED", "analysis-specialists", Duration.ofSeconds(60)),
                () -> "specialist block should join and park\n" + dumpDiagnostics(caseId));

        // Siblings retained: their outputs completed even though security is parked.
        for (String sibling : List.of("ux-architect", "data-architect", "infrastructure-engineer")) {
            assertTrue(waitForProgress(caseId, "STEP_COMPLETED", sibling, Duration.ofSeconds(60)),
                    () -> "sibling " + sibling + " should complete while security is escalated\n" + dumpDiagnostics(caseId));
        }

        // Resolve the parked security branch → join releases → case delivers.
        UUID invocationId = escalatedInvocation(caseId, "security-architect");
        assertNotNull(invocationId, "expected an escalated security-architect invocation");
        ResponseEntity<Void> resolve = rest.exchange(
                url("/api/v1/cases/" + caseId + "/branches/" + invocationId + "/resolve"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("action", "ACCEPT_LAST_OUTPUT", "rationale", "accepted in IT"), h),
                Void.class);
        assertEquals(200, resolve.getStatusCode().value(),
                () -> "branch resolve should succeed\ninvocation=" + invocationId + "\n" + dumpDiagnostics(caseId));

        assertEquals("Delivered", pollStatus(caseId, h, Duration.ofSeconds(150), "Delivered"),
                () -> "case should deliver once the escalated branch is resolved\n" + dumpDiagnostics(caseId));
    }

    @Test
    void consistencyReviewerNeverReviewsItsOwnOutputAsProducer() throws Exception {
        // Phase 2 Polish (T051, AD-8, FR-018): reviewer != producer, and routing is orchestration-owned.
        // The routing half is a structural, compile-time-adjacent guarantee rather than a runtime one:
        // persona/build.gradle carries no Flowable dependency at all, so persona-execution code cannot
        // reach RuntimeService/TaskService even if it tried; ArchitectureRulesTest additionally proves no
        // class in the persona execution machinery calls back into PersonaExecutionService. This test
        // covers the runtime half: the consistency-reviewer never certifies its own output.
        HttpHeaders h = headers();
        String caseId = submitOpenAndStart(h);
        assertEquals("Delivered", pollStatus(caseId, h, Duration.ofSeconds(180), "Delivered"),
                () -> "case should deliver\n" + dumpDiagnostics(caseId));

        // Reviewer identity: the consistency-reviewer's persona_invocation is a genuinely separate actor
        // from every specialist it may cross-check — never the same invocation self-certifying its output.
        UUID reviewerInvocationId = invocationIdFor(caseId, "consistency-reviewer");
        assertNotNull(reviewerInvocationId, "expected a recorded consistency-reviewer invocation");
        for (String specialist : SPECIALISTS) {
            UUID specialistInvocationId = invocationIdFor(caseId, specialist);
            assertNotNull(specialistInvocationId, () -> "expected a recorded " + specialist + " invocation");
            assertNotEquals(reviewerInvocationId, specialistInvocationId,
                    () -> specialist + "'s invocation must differ from the reviewer's own (reviewer != producer)");
        }

        // Deterministic tier: DeterministicCrossChecks explicitly skips persona_key='consistency-reviewer'
        // when it builds the output set to cross-check — so by construction the reviewer's own operation
        // can never appear as a finding's source or target. This asserts that construction held at runtime.
        long selfParticipation = scalarLong(
                "SELECT count(*) FROM consistency_finding cf "
                        + "JOIN operation_execution oe ON oe.id = cf.source_operation_id OR oe.id = cf.target_operation_id "
                        + "JOIN persona_invocation pi ON pi.id = oe.persona_invocation_id "
                        + "WHERE cf.case_id = '" + caseId + "' AND pi.persona_key = 'consistency-reviewer'");
        assertEquals(0L, selfParticipation,
                "the consistency reviewer's own operation must never appear as a deterministic finding participant");
    }

    /** The recorded persona_invocation id for a case + persona key (latest by created_at; null if none). */
    private UUID invocationIdFor(String caseId, String personaKey) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "SET app.workspace_id = '" + WORKSPACE_ID + "'");
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id FROM persona_invocation WHERE case_instance_id = ? AND persona_key = ? "
                            + "ORDER BY created_at DESC LIMIT 1")) {
                ps.setObject(1, UUID.fromString(caseId));
                ps.setString(2, personaKey);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return (UUID) rs.getObject(1);
                }
            }
        }
        return null;
    }

    /** Poll until a progress event of kind+activity exists for the case, or the timeout elapses. */
    private boolean waitForProgress(String caseId, String kind, String activityId, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (progressTs(caseId, kind, activityId) > 0) return true;
            Thread.sleep(500);
        }
        return progressTs(caseId, kind, activityId) > 0;
    }

    // ---- flow helpers -----------------------------------------------------------------------------

    private String submitOpenAndStart(HttpHeaders h) {
        Map<String, Object> submissionBody = Map.of(
                "formData", Map.of("category", "initiation", "description", "parallel IT submission"));
        ResponseEntity<Map> sub = rest.exchange(url("/api/v1/submissions"), HttpMethod.POST,
                new HttpEntity<>(submissionBody, h), Map.class);
        assertEquals(201, sub.getStatusCode().value());
        String submissionId = (String) sub.getBody().get("id");

        rest.exchange(url("/api/v1/submissions/" + submissionId + "/confirm-classification"), HttpMethod.POST,
                new HttpEntity<>(Map.of("confirmedCaseType", "initiation"), h), Map.class);

        ResponseEntity<Map> caseResp = rest.exchange(url("/api/v1/cases"), HttpMethod.POST,
                new HttpEntity<>(Map.of("submissionId", submissionId, "featureId", featureId.toString()), h), Map.class);
        assertEquals(201, caseResp.getStatusCode().value(), () -> "case body: " + caseResp.getBody());
        String caseId = (String) caseResp.getBody().get("id");

        ResponseEntity<Void> start = rest.exchange(url("/api/v1/cases/" + caseId + "/start"), HttpMethod.POST,
                new HttpEntity<>(null, h), Void.class);
        assertEquals(202, start.getStatusCode().value());
        return caseId;
    }

    private String pollStatus(String caseId, HttpHeaders h, Duration timeout, String target) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        String status = null;
        while (Instant.now().isBefore(deadline)) {
            ResponseEntity<Map> resp = rest.exchange(url("/api/v1/cases/" + caseId), HttpMethod.GET,
                    new HttpEntity<>(h), Map.class);
            status = (String) resp.getBody().get("status");
            if (target.equals(status)) return status;
            // Cancelled is always terminal; Delivered/Escalated may be the wrong target for a given poll.
            if ("Cancelled".equals(status)) break;
            Thread.sleep(500);
        }
        return status;
    }

    // ---- db helpers -------------------------------------------------------------------------------

    /** Epoch-millis of the earliest progress event of a given kind+activity for a case (0 if none). */
    private long progressTs(String caseId, String kind, String activityId) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "SET app.workspace_id = '" + WORKSPACE_ID + "'");
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT min(created_at) FROM progress_event WHERE case_id = ? AND kind = ? "
                            + "AND (activity_id = ? OR ? IS NULL)")) {
                ps.setObject(1, UUID.fromString(caseId));
                ps.setString(2, kind);
                ps.setString(3, activityId);
                ps.setString(4, activityId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getTimestamp(1) != null) return rs.getTimestamp(1).getTime();
                }
            }
        }
        return 0L;
    }

    private UUID escalatedInvocation(String caseId, String personaKey) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "SET app.workspace_id = '" + WORKSPACE_ID + "'");
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id FROM persona_invocation WHERE case_instance_id = ? AND persona_key = ? "
                            + "AND status = 'escalated' ORDER BY created_at DESC LIMIT 1")) {
                ps.setObject(1, UUID.fromString(caseId));
                ps.setString(2, personaKey);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return (UUID) rs.getObject(1);
                }
            }
        }
        return null;
    }

    private String describe(Map<String, long[]> windows) {
        StringBuilder sb = new StringBuilder();
        windows.forEach((k, v) -> sb.append(k).append("=[").append(v[0]).append(',').append(v[1]).append("] "));
        return sb.toString();
    }

    private String dumpDiagnostics(String caseId) {
        StringBuilder sb = new StringBuilder("=== PARALLEL DIAGNOSTICS ===\n");
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "SET app.workspace_id = '" + WORKSPACE_ID + "'");
            sb.append("case status: ").append(scalar(conn,
                    "SELECT status FROM case_instance WHERE id = '" + caseId + "'")).append('\n');
            sb.append("invocations: ").append(scalar(conn,
                    "SELECT string_agg(persona_key || ':' || status, ', ' ORDER BY persona_key) FROM persona_invocation WHERE case_instance_id = '" + caseId + "'")).append('\n');
            sb.append("progress kinds: ").append(scalar(conn,
                    "SELECT string_agg(kind || '@' || coalesce(activity_id,'-'), ', ' ORDER BY id) FROM progress_event WHERE case_id = '" + caseId + "'")).append('\n');
            sb.append("ACT_RU_JOB exc: ").append(scalar(conn, "SELECT string_agg(EXCEPTION_MSG_, ' | ') FROM ACT_RU_JOB")).append('\n');
            sb.append("ACT_RU_DEADLETTER exc: ").append(scalar(conn, "SELECT string_agg(EXCEPTION_MSG_, ' | ') FROM ACT_RU_DEADLETTER_JOB")).append('\n');
            sb.append("reconciliation_run: ").append(scalar(conn,
                    "SELECT string_agg(divergence || ':' || action, ', ') FROM reconciliation_run WHERE case_id = '" + caseId + "'")).append('\n');
            sb.append("engine executions (act_id): ").append(scalar(conn,
                    "SELECT string_agg(coalesce(ACT_ID_,'<root>'), ', ') FROM ACT_RU_EXECUTION "
                    + "WHERE PROC_INST_ID_ = (SELECT ID_ FROM ACT_RU_EXECUTION WHERE BUSINESS_KEY_ = '" + caseId + "')")).append('\n');
        } catch (Exception e) {
            sb.append("diag failed: ").append(e.getMessage());
        }
        return sb.toString();
    }

    private long scalarLong(String sql) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "SET app.workspace_id = '" + WORKSPACE_ID + "'");
            try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    private String scalar(Connection conn, String sql) {
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? String.valueOf(rs.getObject(1)) : "(none)";
        } catch (Exception e) {
            return "(err: " + e.getMessage() + ")";
        }
    }

    private void exec(Connection conn, String sql) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) { ps.execute(); }
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
        HttpHeaders h = new HttpHeaders();
        h.set("X-Workspace-Id", WORKSPACE_ID.toString());
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
