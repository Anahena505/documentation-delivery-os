package com.d2os.app;

import com.d2os.app.support.OidcTestSupport;
import com.d2os.app.support.StubAiGatewayClient;
import com.d2os.casecore.audit.AuditChainSealer;
import com.d2os.casecore.audit.AuditChainVerifier;
import com.d2os.testsupport.ContainerFixtures;
import com.d2os.tenancy.WorkspaceContext;
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
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 008 US5 (T054) — the auth-cutover acceptance IT, booted in OIDC mode
 * ({@code d2os.security.oidc.enabled=true}) with a test JWKS/JwtDecoder ({@link OidcTestSupport}).
 * Proves the three US5 guarantees end to end against the real controllers/services:
 * <ol>
 *   <li><b>(a)</b> a role-restricted gate decision by an authenticated caller WITHOUT the
 *       governance-approver role is refused with 403 ({@code @PreAuthorize}, T052);</li>
 *   <li><b>(b)</b> the same decision by a caller WITH {@code ROLE_approver} succeeds (200) and the
 *       persisted {@code audit_entry} carries {@code actor_user_id} (the token {@code sub}) and
 *       {@code actor_role} (T048/T051);</li>
 *   <li><b>(c)</b> tampering that stamped actor after sealing breaks {@link AuditChainVerifier}
 *       (T050 — the actor is folded into the hash chain).</li>
 * </ol>
 *
 * <p><b>Runs in CI only</b> (Testcontainers Postgres/MinIO; no Docker in the dev sandbox). Gates are
 * seeded directly via JDBC as case-less {@code DEFINITION_VERSION}-subject gates (V26/V27) so the
 * decision path exercises RBAC + actor-stamping without standing up a whole workflow — the
 * self-review guard is bypassed for a case-less gate, exactly as a studio publish-review gate is.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({StubAiGatewayClient.class, OidcTestSupport.OidcTestConfig.class})
class RbacAndActorIT {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired AuditChainSealer auditChainSealer;
    @Autowired AuditChainVerifier auditChainVerifier;

    private static final UUID WORKSPACE_ID = UUID.randomUUID();

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
        // The cutover under test: OIDC resource-server posture (per-user identity + roles), verified
        // against the in-test JwtDecoder bean (OidcTestConfig), not the default workspace-scoping path.
        registry.add("d2os.security.oidc.enabled", () -> "true");
    }

    @BeforeEach
    void seedWorkspace() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            exec(c, "SET app.workspace_id = '" + WORKSPACE_ID + "'");
            ins(c, "INSERT INTO workspace (id,name,created_by) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                    WORKSPACE_ID, "rbac-actor-ws", "test");
        }
    }

    // ---- (a) authenticated but missing the required role -> 403 ----------------------------------

    @Test
    void gateDecisionWithoutApproverRoleIsForbidden() throws Exception {
        UUID gateId = seedOpenGate();
        String token = OidcTestSupport.mintToken("user:not-an-approver", WORKSPACE_ID, List.of("reviewer"));

        ResponseEntity<Map> resp = decide(gateId, token, "APPROVE");
        assertEquals(403, resp.getStatusCode().value(),
                "an authenticated caller lacking the approver role must be refused (FR-012, SC-008)");

        // The refused attempt left no actor-stamped audit row for this gate.
        Long stamped = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_entry WHERE subject_id = ? AND actor_user_id IS NOT NULL",
                Long.class, gateId);
        assertEquals(0L, stamped, "a refused decision must not write an actor-stamped audit row");
    }

    // ---- (b) authenticated WITH the role -> 200 and the audit row names the user + role ----------

    @Test
    void gateDecisionWithApproverRoleSucceedsAndStampsActor() throws Exception {
        UUID gateId = seedOpenGate();
        String sub = "user:alice-approver";
        String token = OidcTestSupport.mintToken(sub, WORKSPACE_ID, List.of("approver"));

        ResponseEntity<Map> resp = decide(gateId, token, "APPROVE");
        assertEquals(200, resp.getStatusCode().value(), () -> "approver decision failed: " + resp.getBody());
        assertEquals("APPROVED", resp.getBody().get("status"));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT actor_user_id, actor_role FROM audit_entry WHERE subject_id = ? AND action = 'GATE_APPROVE'",
                gateId);
        assertEquals(sub, row.get("actor_user_id"), "the audit row must name the authenticated decision-maker (FR-013)");
        assertEquals("approver", row.get("actor_role"), "the audit row must record the role acted under (FR-013)");
    }

    // ---- (c) tampering the stamped actor after sealing breaks the hash chain ----------------------

    @Test
    void tamperingTheStampedActorBreaksTheAuditChain() throws Exception {
        UUID gateId = seedOpenGate();
        String sub = "user:bob-approver";
        String token = OidcTestSupport.mintToken(sub, WORKSPACE_ID, List.of("approver"));
        assertEquals(200, decide(gateId, token, "APPROVE").getStatusCode().value());

        // Seal the workspace's audit tail (includes the actor-stamped GATE_APPROVE entry), then verify
        // the untampered chain is intact.
        WorkspaceContext.set(WORKSPACE_ID);
        try {
            auditChainSealer.sealWorkspace(WORKSPACE_ID);
            assertEquals(true, auditChainVerifier.verifyWorkspace(WORKSPACE_ID).intact(),
                    "a freshly sealed, untampered chain must verify intact");
        } finally {
            WorkspaceContext.clear();
        }

        // Out-of-band tamper as the schema owner (d2os_app has no UPDATE grant on audit_entry): rewrite
        // the recorded decision-maker on a sealed entry — the exact "who decided" alteration US5 exists
        // to detect.
        try (Connection owner = DriverManager.getConnection(ContainerFixtures.POSTGRES.getJdbcUrl(),
                ContainerFixtures.POSTGRES.getUsername(), ContainerFixtures.POSTGRES.getPassword())) {
            try (PreparedStatement ps = owner.prepareStatement(
                    "UPDATE audit_entry SET actor_user_id = 'user:mallory' WHERE subject_id = ? AND action = 'GATE_APPROVE'")) {
                ps.setObject(1, gateId);
                assertEquals(1, ps.executeUpdate());
            }
        }

        WorkspaceContext.set(WORKSPACE_ID);
        try {
            assertFalse(auditChainVerifier.verifyWorkspace(WORKSPACE_ID).intact(),
                    "altering the sealed actor identity must break the chain (T050, FR-013, SC-008)");
        } finally {
            WorkspaceContext.clear();
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /** Seed a fresh OPEN, case-less DEFINITION_VERSION-subject gate; returns its id. */
    private UUID seedOpenGate() throws Exception {
        UUID gateId = UUID.randomUUID();
        try (Connection c = dataSource.getConnection()) {
            exec(c, "SET app.workspace_id = '" + WORKSPACE_ID + "'");
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO gate_instance (id, workspace_id, case_instance_id, gate_type, gate_definition_key, "
                            + "gate_definition_version, subject_type, subject_id, inputs_ref, status) "
                            + "VALUES (?, ?, NULL, 'APPROVAL', 'review-gate', 1, 'DEFINITION_VERSION', ?, '{}'::jsonb, 'OPEN')")) {
                ps.setObject(1, gateId);
                ps.setObject(2, WORKSPACE_ID);
                ps.setObject(3, UUID.randomUUID());
                ps.executeUpdate();
            }
        }
        return gateId;
    }

    private ResponseEntity<Map> decide(UUID gateId, String bearerToken, String verb) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Authorization", "Bearer " + bearerToken);
        return rest.exchange("http://localhost:" + port + "/api/v1/gates/" + gateId + "/decision",
                HttpMethod.POST, new HttpEntity<>(Map.of("verb", verb), h), Map.class);
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
}
