package com.d2os.tenancy.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fast, plain-JUnit tests for {@link JwtService} — in particular the up-front secret-length guard
 * (hardening follow-up): a too-short {@code D2OS_JWT_SECRET} must fail at construction with an
 * actionable message rather than crashing deep inside jjwt with a cryptic {@code WeakKeyException}.
 */
class JwtServiceTest {

    // >= 32 bytes (256 bits) — the HS256 minimum; same throwaway value used in app test resources.
    private static final String VALID_SECRET = "test-only-signing-key-not-for-production-use-0123456789ABCDEF";

    private JwtProperties props(String secret) {
        return new JwtProperties(secret, null, 0, false);
    }

    @Test
    void shortSecretFailsFastWithActionableMessage() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new JwtService(props("too-short-key")));
        assertTrue(ex.getMessage().contains("D2OS_JWT_SECRET"),
                "message must name the property an operator has to fix: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("32"),
                "message must state the required length: " + ex.getMessage());
    }

    @Test
    void blankSecretDefersFailureToAuthPathNotStartup() {
        // An unset/blank secret must NOT crash construction — non-auth suites and app boot stay green;
        // only the actual auth path (issue) fails, and validation simply treats everything as unauthenticated.
        JwtService svc = assertDoesNotThrow(() -> new JwtService(props("")));
        assertTrue(svc.validateAndExtractWorkspace("anything").isEmpty());
        assertThrows(IllegalStateException.class, () -> svc.issue(UUID.randomUUID(), "user"));
    }

    @Test
    void validSecretIssuesAndVerifiesRoundTrip() {
        JwtService svc = new JwtService(props(VALID_SECRET));
        UUID workspace = UUID.randomUUID();
        String token = svc.issue(workspace, "user");
        assertEquals(Optional.of(workspace), svc.validateAndExtractWorkspace(token));
    }

    @Test
    void expiredTokenIsRejected() throws InterruptedException {
        JwtService svc = new JwtService(props(VALID_SECRET));
        String token = svc.issue(UUID.randomUUID(), "user", Duration.ofMillis(1));
        Thread.sleep(50);
        assertTrue(svc.validateAndExtractWorkspace(token).isEmpty(),
                "a correctly-signed but expired token must not resolve a workspace");
    }
}
