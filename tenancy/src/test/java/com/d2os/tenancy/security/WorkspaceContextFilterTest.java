package com.d2os.tenancy.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fast, plain-JUnit test for {@link WorkspaceContextFilter#shouldNotFilter} (hardening follow-up):
 * actuator health/liveness/readiness probes must bypass workspace resolution entirely so they are
 * never 401'd under the production no-fallback posture, while every business endpoint — and every
 * non-health actuator endpoint — stays gated.
 */
class WorkspaceContextFilterTest {

    private static final String SECRET = "test-only-signing-key-not-for-production-use-0123456789ABCDEF";

    private final WorkspaceContextFilter filter = new WorkspaceContextFilter(
            new JwtService(new JwtProperties(SECRET, null, 0, false)),
            new JwtProperties(SECRET, null, 0, false));

    private MockHttpServletRequest req(String uri) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRequestURI(uri);
        return r;
    }

    @Test
    void healthProbesBypassTheFilter() {
        assertTrue(filter.shouldNotFilter(req("/actuator/health")));
        assertTrue(filter.shouldNotFilter(req("/actuator/health/liveness")));
        assertTrue(filter.shouldNotFilter(req("/actuator/health/readiness")));
    }

    @Test
    void businessAndNonHealthActuatorEndpointsAreStillFiltered() {
        assertFalse(filter.shouldNotFilter(req("/api/v1/submissions")));
        assertFalse(filter.shouldNotFilter(req("/actuator/env")),
                "a non-health actuator endpoint must remain gated — only probes are exempt");
        assertFalse(filter.shouldNotFilter(req("/actuator/healthz")),
                "a path that merely starts with 'health' but is not the health endpoint must stay gated");
    }
}
