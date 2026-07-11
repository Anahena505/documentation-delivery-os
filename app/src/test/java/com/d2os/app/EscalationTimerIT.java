package com.d2os.app;

import com.d2os.app.support.StubAiGatewayClient;
import com.d2os.testsupport.ContainerFixtures;
import org.flowable.engine.ManagementService;
import org.flowable.job.api.Job;
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

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Advisory SLA escalation, real engine timer (T037, US4, research R4, FR-010/011/012, SC-004). Drives
 * a real case to an OPEN gate, then force-executes that gate's boundary timer job the standard
 * Flowable-testing way ({@code moveTimerToExecutionTime} + {@code executeJob}) instead of waiting the
 * real P3D — the timer's own trigger mechanics aren't under test, {@code TimerFiredHandler}'s behavior
 * is. Asserts the escalation is visible (activation row + notification + outbox event) while the gate
 * stays OPEN and the task unmoved — proving zero auto-route — then confirms the case still reaches
 * Delivered normally afterward, the concrete proof that the timer never touched anything.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StubAiGatewayClient.class)
class EscalationTimerIT {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ManagementService managementService;

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
    void timerFiringRecordsAnAdvisoryEscalationWithoutTouchingTheGateOrTask() throws Exception {
        HttpHeaders h = headers();
        String caseId = submitOpenAndStart(h);
        String gateId = waitForOpenGateId(caseId, h, Duration.ofSeconds(120));

        Job timerJob = findBoundaryTimerJobForCase(caseId);
        Job executableJob = managementService.moveTimerToExecutableJob(timerJob.getId());
        managementService.executeJob(executableJob.getId());

        List<Map<String, Object>> activations = waitForActivations(gateId, Duration.ofSeconds(60));
        assertEquals(1, activations.size(), "exactly one escalation activation should be recorded");
        Map<String, Object> activation = activations.get(0);
        assertEquals(0, ((Number) activation.get("stepIndex")).intValue());
        assertFalse((Boolean) activation.get("assigneeResolved"),
                "no role/user-assignment model exists yet — an unassigned step is still recorded, not skipped");
        assertEquals("ACTIVE", activation.get("status"));

        // The outbox event landed (via AuditWriter, same mechanism as every other gate event).
        long escalationEvents = scalarLong(
                "SELECT count(*) FROM event_outbox WHERE aggregate_id = '" + gateId + "' AND event_type = 'GATE_ESCALATION_FIRED'");
        assertTrue(escalationEvents >= 1, "GATE_ESCALATION_FIRED must be emitted to the outbox");

        // A notification was persisted for the resolved (fallback) role.
        long notifications = scalarLong(
                "SELECT count(*) FROM in_app_notification WHERE type = 'SLA_ESCALATION' "
                        + "AND subject_ref->>'gateInstanceId' = '" + gateId + "'");
        assertTrue(notifications >= 1, "an in-app notification must be persisted for the escalation");

        // Zero auto-route: the gate is still OPEN and the reviewer task is still there, completable normally.
        ResponseEntity<Map> gate = rest.exchange(url("/api/v1/gates/" + gateId), HttpMethod.GET, new HttpEntity<>(h), Map.class);
        assertEquals(200, gate.getStatusCode().value());
        assertEquals("OPEN", gate.getBody().get("status"), "the timer must never touch the gate's own status (Principle V)");

        decide(gateId, h, "reviewer-1", "APPROVE");
        assertEquals("Delivered", poll(caseId, h, Duration.ofSeconds(200)),
                "the case must still reach Delivered normally — the timer never blocked or diverted anything");
    }

    private Job findBoundaryTimerJobForCase(String caseId) throws Exception {
        // The gate userTask's boundary timer runs on the review-gate/approval-gate SUBPROCESS instance,
        // a child of the case's own process instance. This test creates exactly one case, so exactly
        // one timer job should exist across the whole engine at this point.
        List<Job> candidates = managementService.createTimerJobQuery().list();
        if (candidates.isEmpty()) {
            throw new IllegalStateException("no boundary timer job found for case " + caseId);
        }
        return candidates.get(0);
    }

    private String submitOpenAndStart(HttpHeaders h) {
        String submissionId = (String) post("/api/v1/submissions",
                Map.of("formData", Map.of("category", "initiation")), h).getBody().get("id");
        post("/api/v1/submissions/" + submissionId + "/confirm-classification",
                Map.of("confirmedCaseType", "initiation"), h);
        String caseId = (String) post("/api/v1/cases",
                Map.of("submissionId", submissionId, "featureId", featureId.toString()), h).getBody().get("id");
        rest.exchange(url("/api/v1/cases/" + caseId + "/start"), HttpMethod.POST, new HttpEntity<>(null, h), Void.class);
        return caseId;
    }

    private String waitForOpenGateId(String caseId, HttpHeaders h, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            List<Map<String, Object>> openGates = listOpenGates(caseId, h);
            if (!openGates.isEmpty()) {
                return (String) openGates.get(0).get("id");
            }
            Thread.sleep(500);
        }
        fail("review-gate did not open for case " + caseId + " within " + timeout);
        return null;
    }

    private List<Map<String, Object>> waitForActivations(String gateId, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            ResponseEntity<List> resp = rest.exchange(url("/api/v1/gates/" + gateId + "/escalations"),
                    HttpMethod.GET, new HttpEntity<>(headers()), List.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> activations = (List<Map<String, Object>>) (List<?>) resp.getBody();
            if (!activations.isEmpty()) {
                return activations;
            }
            Thread.sleep(500);
        }
        return List.of();
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

    private void decide(String gateId, HttpHeaders base, String actor, String verb) {
        HttpHeaders h = headers();
        h.set("X-Actor", actor);
        ResponseEntity<Map> resp = rest.exchange(
                url("/api/v1/gates/" + gateId + "/decision"), HttpMethod.POST,
                new HttpEntity<>(Map.of("verb", verb), h), Map.class);
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

    private long scalarLong(String sql) throws Exception {
        try (Connection c = dataSource.getConnection()) {
            exec(c, "SET app.workspace_id = '" + WORKSPACE_ID + "'");
            try (PreparedStatement ps = c.prepareStatement(sql); java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
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
