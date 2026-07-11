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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Role-scoped package access + workspace retention (T043, US5, research R6, T3-d, NFR-5, FR-014/015,
 * SC-005/SC-006). Drives a real case to Delivered to get a genuine {@code execution_package} with the
 * auto-seeded {@code reviewer} participant grant (T040) — default-deny for every other role, no
 * workspace-wide grant.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StubAiGatewayClient.class)
class PackageAccessIT {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbcTemplate;

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
    void deliveredPackageIsReadableOnlyByAGrantedRole() throws Exception {
        HttpHeaders h = headers();
        String caseId = submitOpenAndStart(h);
        approveOpenGate(caseId, h, Duration.ofSeconds(120));
        assertEquals("Delivered", poll(caseId, h, Duration.ofSeconds(200)));

        String packageId = scalarString(
                "SELECT id::text FROM execution_package WHERE case_instance_id = '" + caseId + "'");

        // The auto-seeded participant grant: reviewer only, no workspace-wide default.
        List<Map<String, Object>> grants = listGrants(packageId, h);
        assertEquals(1, grants.size());
        assertEquals("reviewer", grants.get(0).get("role"));
        assertEquals("system:delivery", grants.get(0).get("grantedBy"));

        HttpHeaders reviewerHeaders = headers();
        reviewerHeaders.set("X-Roles", "reviewer");
        ResponseEntity<Map> readAsReviewer = rest.exchange(url("/api/v1/packages/" + packageId),
                HttpMethod.GET, new HttpEntity<>(reviewerHeaders), Map.class);
        assertEquals(200, readAsReviewer.getStatusCode().value(), "the granted role must read the package");

        HttpHeaders otherHeaders = headers();
        otherHeaders.set("X-Roles", "auditor");
        ResponseEntity<Map> readAsOther = rest.exchange(url("/api/v1/packages/" + packageId),
                HttpMethod.GET, new HttpEntity<>(otherHeaders), Map.class);
        assertEquals(403, readAsOther.getStatusCode().value(), "an ungranted role must be refused (default-deny, FR-015)");

        // Explicit grant extends access.
        ResponseEntity<Map> granted = rest.exchange(url("/api/v1/packages/" + packageId + "/grants"),
                HttpMethod.POST, new HttpEntity<>(Map.of("role", "auditor"), h), Map.class);
        assertEquals(201, granted.getStatusCode().value());

        ResponseEntity<Map> readAfterGrant = rest.exchange(url("/api/v1/packages/" + packageId),
                HttpMethod.GET, new HttpEntity<>(otherHeaders), Map.class);
        assertEquals(200, readAfterGrant.getStatusCode().value(), "the newly granted role must now read the package");
    }

    @Test
    void retentionDefaultsToSevenYearsAndRejectsBelowTheFloor() {
        HttpHeaders h = headers();
        ResponseEntity<Map> current = rest.exchange(url("/api/v1/workspace/retention"), HttpMethod.GET, new HttpEntity<>(h), Map.class);
        assertEquals(200, current.getStatusCode().value());
        assertEquals(7, current.getBody().get("retentionYears"));

        ResponseEntity<Map> tooShort = rest.exchange(url("/api/v1/workspace/retention"), HttpMethod.PUT,
                new HttpEntity<>(Map.of("retentionYears", 3), h), Map.class);
        assertEquals(422, tooShort.getStatusCode().value(), "below the regulatory minimum must 422");

        ResponseEntity<Map> updated = rest.exchange(url("/api/v1/workspace/retention"), HttpMethod.PUT,
                new HttpEntity<>(Map.of("retentionYears", 10, "notes", "extended by policy"), h), Map.class);
        assertEquals(200, updated.getStatusCode().value());

        ResponseEntity<Map> reread = rest.exchange(url("/api/v1/workspace/retention"), HttpMethod.GET, new HttpEntity<>(h), Map.class);
        assertEquals(10, reread.getBody().get("retentionYears"));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listGrants(String packageId, HttpHeaders h) {
        ResponseEntity<List> resp = rest.exchange(url("/api/v1/packages/" + packageId + "/grants"),
                HttpMethod.GET, new HttpEntity<>(h), List.class);
        assertEquals(200, resp.getStatusCode().value());
        return (List<Map<String, Object>>) (List<?>) resp.getBody();
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

    private String scalarString(String sql) throws Exception {
        try (Connection c = dataSource.getConnection()) {
            exec(c, "SET app.workspace_id = '" + WORKSPACE_ID + "'");
            try (PreparedStatement ps = c.prepareStatement(sql); java.sql.ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "expected a row for: " + sql);
                return rs.getString(1);
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
