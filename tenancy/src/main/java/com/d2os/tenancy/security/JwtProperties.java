package com.d2os.tenancy.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code d2os.security.jwt.*} (T067). No default for {@code secret} — like the DB/storage
 * passwords in application.yml, a signing key MUST be supplied via environment variable ({@code
 * D2OS_JWT_SECRET}) so it is never hardcoded in a committed file.
 *
 * <p>{@code allowHeaderWorkspaceFallback} governs whether an unauthenticated {@code X-Workspace-Id}
 * header may still resolve the workspace when no valid JWT is presented. It defaults to {@code
 * false} (production posture: JWT required, Principle IV) and is flipped on only in {@code
 * app/src/test/resources/application.properties}, so every existing integration test — which
 * predates JWT support and authenticates purely via the header — keeps passing unchanged.
 */
@ConfigurationProperties(prefix = "d2os.security.jwt")
public record JwtProperties(
    String secret, String issuer, long expirationMinutes, boolean allowHeaderWorkspaceFallback) {
  public JwtProperties {
    if (issuer == null || issuer.isBlank()) issuer = "d2os";
    if (expirationMinutes <= 0) expirationMinutes = 60;
  }
}
