package com.d2os.app;

import com.d2os.testsupport.ContainerFixtures;
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Cross-tenant leakage suite (T047/T048, US3, SC-003). Creates a Case in workspace ALPHA and proves
 * workspace BETA cannot read it through the API — RLS returns not-found, indistinguishable from a
 * genuinely absent record (contracts/api.yaml). This is the API-level counterpart to the DB-level
 * RLS proof done directly against Postgres earlier in the build.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LeakageSuiteIT {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired DataSource dataSource;

    private static final UUID ALPHA = UUID.randomUUID();
    private static final UUID BETA = UUID.randomUUID();
    private static UUID alphaFeatureId;

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
        seedWorkspace(ALPHA);
        alphaFeatureId = seedWorkspaceFeature(ALPHA);
        seedWorkspace(BETA);
    }

    @Test
    void betaCannotReadAlphasCase() {
        // Create a Case in ALPHA.
        HttpHeaders alpha = headers(ALPHA);
        String submissionId = (String) post("/api/v1/submissions",
                Map.of("formData", Map.of("category", "initiation")), alpha).getBody().get("id");
        post("/api/v1/submissions/" + submissionId + "/confirm-classification",
                Map.of("confirmedCaseType", "initiation"), alpha);
        ResponseEntity<Map> caseResp = post("/api/v1/cases",
                Map.of("submissionId", submissionId, "featureId", alphaFeatureId.toString()), alpha);
        assertEquals(201, caseResp.getStatusCode().value());
        String alphaCaseId = (String) caseResp.getBody().get("id");

        // ALPHA can read its own case.
        ResponseEntity<Map> ownRead = rest.exchange(url("/api/v1/cases/" + alphaCaseId),
                HttpMethod.GET, new HttpEntity<>(headers(ALPHA)), Map.class);
        assertEquals(200, ownRead.getStatusCode().value(), "ALPHA must see its own case");

        // BETA cannot — RLS hides it, surfaced as 404 (never 200 with data).
        ResponseEntity<Map> crossRead = rest.exchange(url("/api/v1/cases/" + alphaCaseId),
                HttpMethod.GET, new HttpEntity<>(headers(BETA)), Map.class);
        assertNotEquals(200, crossRead.getStatusCode().value(),
                "BETA must NOT be able to read ALPHA's case (SC-003)");
        assertEquals(404, crossRead.getStatusCode().value());
    }

    private ResponseEntity<Map> post(String path, Object body, HttpHeaders headers) {
        return rest.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    private HttpHeaders headers(UUID ws) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Workspace-Id", ws.toString());
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private void seedWorkspace(UUID ws) throws Exception {
        try (Connection c = dataSource.getConnection()) {
            exec(c, "SET app.workspace_id = '" + ws + "'");
            ins(c, "INSERT INTO workspace (id, name, created_by) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                    ws, "ws-" + ws, "test");
        }
    }

    private UUID seedWorkspaceFeature(UUID ws) throws Exception {
        UUID projectId = UUID.randomUUID(), versionId = UUID.randomUUID(), featureId = UUID.randomUUID();
        try (Connection c = dataSource.getConnection()) {
            exec(c, "SET app.workspace_id = '" + ws + "'");
            ins(c, "INSERT INTO project (id, workspace_id, name, created_by) VALUES (?, ?, ?, ?)",
                    projectId, ws, "p", "test");
            ins(c, "INSERT INTO project_version (id, workspace_id, project_id, label, created_by) VALUES (?, ?, ?, ?, ?)",
                    versionId, ws, projectId, "v1", "test");
            ins(c, "INSERT INTO feature (id, workspace_id, project_version_id, name, created_by) VALUES (?, ?, ?, ?, ?)",
                    featureId, ws, versionId, "f", "test");
        }
        return featureId;
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

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
