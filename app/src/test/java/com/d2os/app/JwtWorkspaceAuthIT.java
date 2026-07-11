package com.d2os.app;

import com.d2os.app.support.StubAiGatewayClient;
import com.d2os.testsupport.ContainerFixtures;
import com.d2os.tenancy.security.JwtService;
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
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Workspace-scoping auth (T067, Principle IV): a request with a valid, cryptographically verified
 * JWT resolves its workspace from the token's {@code workspace_id} claim; an invalid/tampered/expired
 * token — or none at all — is rejected with 401, WITHOUT silently falling back to a client-asserted
 * header. The header fallback itself is exercised implicitly by every other IT in this suite (it is
 * enabled only via {@code app/src/test/resources/application.properties}, T067's javadoc); this class
 * additionally proves the fallback is refused outright when disabled (the real-deployment posture),
 * via a class-local property override.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StubAiGatewayClient.class)
class JwtWorkspaceAuthIT {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired DataSource dataSource;
    @Autowired JwtService jwtService;

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
    }

    @BeforeEach
    void seed() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            exec(c, "SET app.workspace_id = '" + WORKSPACE_ID + "'");
            ins(c, "INSERT INTO workspace (id, name, created_by) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                    WORKSPACE_ID, "jwt-it-ws", "test");
        }
    }

    @Test
    void validJwtResolvesWorkspaceAndRequestSucceeds() {
        String token = jwtService.issue(WORKSPACE_ID, "test-user");
        ResponseEntity<Map> resp = submit(bearer(token));
        assertEquals(201, resp.getStatusCode().value(), "a valid JWT must resolve the workspace and let the request through");
    }

    @Test
    void tamperedJwtIsRejected() {
        String token = jwtService.issue(WORKSPACE_ID, "test-user");
        // Flip the last character of the signature segment — same claims, invalid signature.
        String tampered = token.substring(0, token.length() - 1)
                + (token.charAt(token.length() - 1) == 'a' ? 'b' : 'a');
        ResponseEntity<Map> resp = submit(bearer(tampered));
        assertEquals(401, resp.getStatusCode().value(), "a tampered signature must be rejected, not silently accepted");
    }

    @Test
    void expiredJwtIsRejected() throws InterruptedException {
        // A genuinely expired, correctly-signed token — proves expiry is enforced independently of
        // signature validity, not merely simulated by corrupting the token.
        String expired = jwtService.issue(WORKSPACE_ID, "test-user", java.time.Duration.ofMillis(1));
        Thread.sleep(50);
        ResponseEntity<Map> resp = submit(bearer(expired));
        assertEquals(401, resp.getStatusCode().value(), "an expired token must be rejected even though correctly signed");
    }

    @Test
    void missingAllCredentialsIsRejected() {
        // No Authorization header AND no X-Workspace-Id — even with the header fallback enabled (this
        // class's ambient test config), there is nothing for either path to resolve. The dedicated
        // "fallback explicitly disabled" posture is covered separately by FallbackDisabledIT below.
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> resp = submit(h);
        assertEquals(401, resp.getStatusCode().value(), "a request with no credentials at all must be rejected");
    }

    @Test
    void bearerHeaderPresentButInvalidNeverFallsBackToWorkspaceHeader() {
        // A request that attempts (and fails) JWT auth must not be rescued by also sending a
        // client-asserted X-Workspace-Id — that would let a forged workspace claim ride in behind a
        // bogus-but-present Authorization header.
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", "Bearer not-a-real-token");
        h.set("X-Workspace-Id", WORKSPACE_ID.toString());
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> resp = submit(h);
        assertEquals(401, resp.getStatusCode().value(),
                "a failed bearer-token attempt must not fall back to the unauthenticated header");
    }

    /** Class-local override: with the fallback explicitly disabled, the header path must be refused. */
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @Import(StubAiGatewayClient.class)
    @TestPropertySource(properties = "d2os.security.jwt.allow-header-workspace-fallback=false")
    static class FallbackDisabledIT {

        @LocalServerPort int port;
        @Autowired TestRestTemplate rest;
        @Autowired DataSource dataSource;

        private static final UUID WS = UUID.randomUUID();

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

        @Test
        void headerFallbackRefusedWhenDisabled() {
            HttpHeaders h = new HttpHeaders();
            h.set("X-Workspace-Id", WS.toString());
            h.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<Map> resp = rest.exchange(
                    "http://localhost:" + port + "/api/v1/submissions", HttpMethod.POST,
                    new HttpEntity<>(Map.of("formData", Map.of("category", "initiation")), h), Map.class);
            assertEquals(401, resp.getStatusCode().value(),
                    "with the fallback disabled (real-deployment posture), an unauthenticated "
                            + "X-Workspace-Id header must never resolve a workspace (Principle IV)");
        }
    }

    private ResponseEntity<Map> submit(HttpHeaders headers) {
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(url("/api/v1/submissions"), HttpMethod.POST,
                new HttpEntity<>(Map.of("formData", Map.of("category", "initiation")), headers), Map.class);
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", "Bearer " + token);
        return h;
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
