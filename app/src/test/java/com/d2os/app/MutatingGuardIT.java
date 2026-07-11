package com.d2os.app;

import com.d2os.app.support.StubAiGatewayClient;
import com.d2os.testsupport.ContainerFixtures;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The Q2 single-active-mutating-case guard, end to end (T029, US4, FR-012/013, SC-005). Two
 * genuinely concurrent {@code POST /cases} on the SAME Feature — no client-side serialization, no
 * server-side queue or row lock held across the case lifecycle, just the guarded UPDATE's own
 * atomicity — must yield exactly one {@code 201} and one {@code 409 MutatingConflict}. Assessment
 * (mutating=false) is exempt and always admitted, even while the slot is held. The slot releases in
 * the same transaction as the terminal transition, so a subsequent mutating create succeeds once the
 * first case is Delivered.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StubAiGatewayClient.class)
class MutatingGuardIT {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired DataSource dataSource;

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
    }

    @BeforeEach
    void seed() throws Exception {
        UUID projectId = UUID.randomUUID(), versionId = UUID.randomUUID();
        featureId = UUID.randomUUID();
        try (Connection c = dataSource.getConnection()) {
            exec(c, "SET app.workspace_id = '" + WORKSPACE_ID + "'");
            ins(c, "INSERT INTO workspace (id,name,created_by) VALUES (?,?,?) ON CONFLICT DO NOTHING", WORKSPACE_ID, "ws", "t");
            ins(c, "INSERT INTO project (id,workspace_id,name,created_by) VALUES (?,?,?,?)", projectId, WORKSPACE_ID, "p", "t");
            ins(c, "INSERT INTO project_version (id,workspace_id,project_id,label,created_by) VALUES (?,?,?,?,?)", versionId, WORKSPACE_ID, projectId, "v1", "t");
            ins(c, "INSERT INTO feature (id,workspace_id,project_version_id,name,created_by) VALUES (?,?,?,?,?)", featureId, WORKSPACE_ID, versionId, "f", "t");
        }
    }

    @Test
    void concurrentMutatingCreatesOnSameFeatureYieldExactlyOneWinner() throws Exception {
        String submissionA = confirmedInitiationSubmission();
        String submissionB = confirmedInitiationSubmission();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startLine = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<ResponseEntity<Map>> f1 = pool.submit(() -> createCase(submissionA, startLine, go));
            Future<ResponseEntity<Map>> f2 = pool.submit(() -> createCase(submissionB, startLine, go));
            startLine.await(10, TimeUnit.SECONDS);
            go.countDown();   // release both threads at (as near as the JVM allows) the same instant

            ResponseEntity<Map> r1 = f1.get(30, TimeUnit.SECONDS);
            ResponseEntity<Map> r2 = f2.get(30, TimeUnit.SECONDS);

            List<ResponseEntity<Map>> results = List.of(r1, r2);
            long created = results.stream().filter(r -> r.getStatusCode().value() == 201).count();
            long conflicted = results.stream().filter(r -> r.getStatusCode().value() == 409).count();
            assertEquals(1, created, "exactly one concurrent create must win (SC-005)");
            assertEquals(1, conflicted, "exactly one concurrent create must be rejected 409, never queued");

            ResponseEntity<Map> conflict = results.stream()
                    .filter(r -> r.getStatusCode().value() == 409).findFirst().orElseThrow();
            assertEquals(featureId.toString(), conflict.getBody().get("featureId"),
                    "the MutatingConflict body must carry the Feature id");
            assertNotNull(conflict.getBody().get("activeCaseId"),
                    "the MutatingConflict body must carry the winning case's id");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void assessmentIsAdmittedWhileAMutatingSlotIsHeld() throws Exception {
        HttpHeaders h = headers();
        // Initiation (mutating) acquires the slot.
        String initiationSubmission = confirmedInitiationSubmission();
        ResponseEntity<Map> initiationCase = post("/api/v1/cases",
                Map.of("submissionId", initiationSubmission, "featureId", featureId.toString()), h);
        assertEquals(201, initiationCase.getStatusCode().value());

        // Assessment (mutating=false, T018 exemption) is admitted on the SAME Feature regardless.
        String assessmentSubmission = confirmedSubmission("assessment");
        ResponseEntity<Map> assessmentCase = post("/api/v1/cases",
                Map.of("submissionId", assessmentSubmission, "featureId", featureId.toString()), h);
        assertEquals(201, assessmentCase.getStatusCode().value(),
                "Assessment must bypass the Q2 guard entirely, even while a mutating case holds the slot");
    }

    @Test
    void terminalTransitionReleasesTheSlotForTheNextMutatingCreate() throws Exception {
        HttpHeaders h = headers();
        String firstSubmission = confirmedInitiationSubmission();
        String firstCaseId = (String) post("/api/v1/cases",
                Map.of("submissionId", firstSubmission, "featureId", featureId.toString()), h).getBody().get("id");
        rest.exchange(url("/api/v1/cases/" + firstCaseId + "/start"), HttpMethod.POST, new HttpEntity<>(null, h), Void.class);
        approveOpenGate(firstCaseId, h, Duration.ofSeconds(120));
        assertEquals("Delivered", poll(firstCaseId, h, Duration.ofSeconds(200)),
                "the first case must reach Delivered, releasing the slot in the same transaction");

        String secondSubmission = confirmedInitiationSubmission();
        ResponseEntity<Map> secondCase = post("/api/v1/cases",
                Map.of("submissionId", secondSubmission, "featureId", featureId.toString()), h);
        assertEquals(201, secondCase.getStatusCode().value(),
                "a second mutating create on the same Feature must succeed once the first case delivered");
    }

    private ResponseEntity<Map> createCase(String submissionId, CountDownLatch startLine, CountDownLatch go) {
        startLine.countDown();
        try {
            go.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return post("/api/v1/cases", Map.of("submissionId", submissionId, "featureId", featureId.toString()), headers());
    }

    private String confirmedInitiationSubmission() {
        return confirmedSubmission("initiation");
    }

    private String confirmedSubmission(String caseType) {
        HttpHeaders h = headers();
        String submissionId = (String) post("/api/v1/submissions",
                Map.of("formData", Map.of("category", caseType)), h).getBody().get("id");
        post("/api/v1/submissions/" + submissionId + "/confirm-classification",
                Map.of("confirmedCaseType", caseType), h);
        return submissionId;
    }

    private void approveOpenGate(String caseId, HttpHeaders h, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            List<Map<String, Object>> openGates = listOpenGates(caseId, h);
            if (!openGates.isEmpty()) {
                String gateId = (String) openGates.get(0).get("id");
                decide(gateId, "reviewer-1", "APPROVE");
                return;
            }
            Thread.sleep(500);
        }
        fail("review-gate did not open for case " + caseId + " within " + timeout);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOpenGates(String caseId, HttpHeaders h) {
        ResponseEntity<List> resp = rest.exchange(
                url("/api/v1/gates?caseId=" + caseId), HttpMethod.GET, new HttpEntity<>(h), List.class);
        assertEquals(200, resp.getStatusCode().value());
        List<Map<String, Object>> gates = (List<Map<String, Object>>) (List<?>) resp.getBody();
        List<Map<String, Object>> open = new ArrayList<>();
        for (Map<String, Object> gate : gates) {
            if ("OPEN".equals(gate.get("status"))) {
                open.add(gate);
            }
        }
        return open;
    }

    private void decide(String gateId, String actor, String verb) {
        HttpHeaders actorHeaders = headers();
        actorHeaders.set("X-Actor", actor);
        ResponseEntity<Map> resp = rest.exchange(
                url("/api/v1/gates/" + gateId + "/decision"), HttpMethod.POST,
                new HttpEntity<>(Map.of("verb", verb), actorHeaders), Map.class);
        assertEquals(200, resp.getStatusCode().value(), () -> "gate decision failed: " + resp.getBody());
    }

    private String poll(String caseId, HttpHeaders h, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        String status = null;
        while (Instant.now().isBefore(deadline)) {
            ResponseEntity<Map> r = rest.exchange(url("/api/v1/cases/" + caseId), HttpMethod.GET, new HttpEntity<>(h), Map.class);
            status = (String) r.getBody().get("status");
            if ("Delivered".equals(status) || "Escalated".equals(status) || "Suspended".equals(status)) return status;
            Thread.sleep(500);
        }
        return status;
    }

    private ResponseEntity<Map> post(String path, Object body, HttpHeaders headers) {
        return rest.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    private void exec(Connection c, String sql) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) { ps.execute(); }
    }

    private void ins(Connection c, String sql, Object... params) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                if (params[i] instanceof UUID u) ps.setObject(i + 1, u);
                else ps.setString(i + 1, params[i].toString());
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
