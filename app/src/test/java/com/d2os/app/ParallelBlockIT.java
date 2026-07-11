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
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Persona statelessness invariant, runtime half (T051, FR-018). {@code ArchitectureRulesTest} proves
 * the static half (no mutable instance state, no persona-to-persona recursion); this asserts the
 * corresponding runtime invariant on a real parallel case: no persona ever reviews or produces its own
 * output, and the consistency-reviewer in particular — the one persona whose whole job is to pass
 * judgement on other personas' outputs — never itself shows up as an artifact's producer (reviewer !=
 * producer on the same artifact). Workflow routing (fork/join/escalation-resume) is a separate
 * structural guarantee: it is wired exclusively through {@code orchestration} (BPMN delegates,
 * {@code EscalationBridge}/{@code EscalationController}), a module the {@code persona} module has no
 * dependency path into (enforced by Gradle module direction + {@code ArchitectureRules}), so no persona
 * class can reach a routing API at all.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StubAiGatewayClient.class)
class ParallelBlockIT {

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
    void consistencyReviewerNeverProducesTheArtifactItReviews() throws Exception {
        HttpHeaders h = headers();
        String submissionId = (String) post("/api/v1/submissions",
                Map.of("formData", Map.of("category", "initiation")), h).getBody().get("id");
        post("/api/v1/submissions/" + submissionId + "/confirm-classification", Map.of("confirmedCaseType", "initiation"), h);
        String caseId = (String) post("/api/v1/cases",
                Map.of("submissionId", submissionId, "featureId", featureId.toString()), h).getBody().get("id");
        rest.exchange(url("/api/v1/cases/" + caseId + "/start"), HttpMethod.POST, new HttpEntity<>(null, h), Void.class);

        approveOpenGate(caseId, h, Duration.ofSeconds(120));
        assertEquals("Delivered", poll(caseId, h, Duration.ofSeconds(200)), "the parallel case should deliver");

        // The consistency-reviewer ran (a recorded, replayable operation — see ParallelReplayIT)...
        long reviewerInvocations = scalarLong(
                "SELECT count(*) FROM persona_invocation WHERE case_instance_id = '" + caseId
                        + "' AND persona_key = 'consistency-reviewer'");
        assertTrue(reviewerInvocations >= 1, "expected the consistency-reviewer to have run on a delivered case");

        // ...but never as the producer of any artifact_revision: reviewer != producer, always (FR-018).
        long reviewerProducedArtifacts = scalarLong(
                "SELECT count(*) FROM artifact_revision ar "
                        + "JOIN operation_execution oe ON oe.id = ar.produced_by_operation_execution_id "
                        + "JOIN persona_invocation pi ON pi.id = oe.persona_invocation_id "
                        + "WHERE pi.case_instance_id = '" + caseId + "' AND pi.persona_key = 'consistency-reviewer'");
        assertEquals(0L, reviewerProducedArtifacts,
                "the consistency-reviewer must never appear as an artifact's producer — it only judges, never authors (FR-018)");
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

    private long scalarLong(String sql) throws Exception {
        try (Connection c = dataSource.getConnection()) {
            exec(c, "SET app.workspace_id = '" + WORKSPACE_ID + "'");
            try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
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
