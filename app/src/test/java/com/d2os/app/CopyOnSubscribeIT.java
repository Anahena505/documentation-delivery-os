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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Copy-on-subscribe insulation (T028, US4, research R6, FR-013/014/015, T4-d, SC-006). Workspace A
 * subscribes to a Global (system-workspace) definition; the copy is checksum-verified, insulated
 * from any later change to the source, and invisible to workspace B.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CopyOnSubscribeIT {

    private static final UUID SYSTEM_WORKSPACE = UUID.fromString("00000000-0000-0000-0000-000000000000");

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbcTemplate;

    private static final UUID WORKSPACE_A = UUID.randomUUID();
    private static final UUID WORKSPACE_B = UUID.randomUUID();
    private UUID sourceDefinitionId;

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
        sourceDefinitionId = UUID.randomUUID();
        try (Connection c = dataSource.getConnection()) {
            exec(c, "SET app.workspace_id = '" + SYSTEM_WORKSPACE + "'");
            ins(c, "INSERT INTO workspace (id,name,created_by) VALUES (?,?,?) ON CONFLICT DO NOTHING", SYSTEM_WORKSPACE, "system", "t");
            ins(c, "INSERT INTO workspace (id,name,created_by) VALUES (?,?,?) ON CONFLICT DO NOTHING", WORKSPACE_A, "ws-a", "t");
            ins(c, "INSERT INTO workspace (id,name,created_by) VALUES (?,?,?) ON CONFLICT DO NOTHING", WORKSPACE_B, "ws-b", "t");
            String body = "{\"name\":\"global content\"}";
            String checksum = sha256Hex(body);
            ins(c, "INSERT INTO definition_asset (id,workspace_id,key,version,type,status,locale,body,checksum,created_by) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?)", sourceDefinitionId, SYSTEM_WORKSPACE,
                    "copy-on-subscribe-target", "1.0.0", "template", "Published", "en", body, checksum, "system-seed");
        }
    }

    @Test
    void subscribeCopiesInsulatesFromSourceChangesAndIsWorkspaceScoped() throws Exception {
        HttpHeaders a = headers(WORKSPACE_A);

        ResponseEntity<Map> subscribed = rest.exchange(
                url("/api/v1/library/definitions/" + sourceDefinitionId + "/subscribe"),
                HttpMethod.POST, new HttpEntity<>(null, a), Map.class);
        assertEquals(201, subscribed.getStatusCode().value(), () -> "subscribe failed: " + subscribed.getBody());
        assertEquals(true, subscribed.getBody().get("checksumVerified"), "checksum equality is the copy-integrity proof (T4-d)");
        String copiedId = (String) subscribed.getBody().get("definitionId");

        Map<String, Object> copyRow = jdbcTemplate.queryForMap(
                "SELECT workspace_id, copied_from_id, checksum, status FROM definition_asset WHERE id = ?",
                UUID.fromString(copiedId));
        assertEquals(WORKSPACE_A.toString(), copyRow.get("workspace_id").toString());
        assertEquals(sourceDefinitionId.toString(), copyRow.get("copied_from_id").toString());
        assertEquals("Published", copyRow.get("status"));

        long subscriptionRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM library_subscription WHERE workspace_id = ? AND source_definition_id = ?",
                Long.class, WORKSPACE_A, sourceDefinitionId);
        assertEquals(1, subscriptionRows);

        // Deprecate the Global source — A's own copy is untouched (insulation by construction).
        try (Connection c = dataSource.getConnection()) {
            exec(c, "SET app.workspace_id = '" + SYSTEM_WORKSPACE + "'");
            exec(c, "UPDATE definition_asset SET status = 'Deprecated' WHERE id = '" + sourceDefinitionId + "'");
        }
        String copyStatusAfter = jdbcTemplate.queryForObject(
                "SELECT status FROM definition_asset WHERE id = ?", String.class, UUID.fromString(copiedId));
        assertEquals("Published", copyStatusAfter, "A's copy must keep resolving unchanged after the source is deprecated");

        // Re-subscribing to the same source → 409.
        ResponseEntity<Map> again = rest.exchange(
                url("/api/v1/library/definitions/" + sourceDefinitionId + "/subscribe"),
                HttpMethod.POST, new HttpEntity<>(null, a), Map.class);
        assertEquals(409, again.getStatusCode().value(), "a workspace may not subscribe to the same source twice");

        // Workspace B cannot see A's copy at all (RLS + workspace-scoped browse).
        HttpHeaders b = headers(WORKSPACE_B);
        ResponseEntity<List> bLibrary = rest.exchange(url("/api/v1/library/definitions"),
                HttpMethod.GET, new HttpEntity<>(b), List.class);
        assertEquals(200, bLibrary.getStatusCode().value());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bEntries = (List<Map<String, Object>>) (List<?>) bLibrary.getBody();
        boolean bSeesSubscribed = bEntries.stream().anyMatch(e -> e.get("copied_definition_id") != null);
        assertTrue(!bSeesSubscribed, "workspace B must see the Global source as not-yet-subscribed (A's copy is invisible to it)");
    }

    private String sha256Hex(String content) throws Exception {
        byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
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

    private HttpHeaders headers(UUID workspaceId) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Workspace-Id", workspaceId.toString());
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
