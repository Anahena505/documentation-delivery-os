package com.d2os.tenancy.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Issues and verifies HMAC-signed workspace-scoping JWTs (T067, Principle IV). This is the sole
 * choke point for JWT signing/verification — {@link WorkspaceContextFilter} calls {@link
 * #validateAndExtractWorkspace(String)} on every request's {@code Authorization} header and never
 * touches signing material itself.
 *
 * <p>The {@code workspace_id} claim is the sole source of truth for tenant scoping: a token is
 * accepted only if it verifies against {@link JwtProperties#secret()}, has not expired, was issued
 * by the configured issuer, and carries a syntactically valid UUID {@code workspace_id} claim. Any
 * other failure (bad signature, malformed token, missing claim) yields an empty result — callers
 * must treat that as "unauthenticated", never fall back to trusting a partially-parsed token.
 */
@Component
@EnableConfigurationProperties(JwtProperties.class)
public class JwtService {

  private static final String WORKSPACE_CLAIM = "workspace_id";

  /**
   * HS256 requires a >= 256-bit (32-byte) key; jjwt otherwise throws a cryptic WeakKeyException.
   */
  private static final int MIN_SECRET_BYTES = 32;

  private final JwtProperties properties;
  private final SecretKey key;

  public JwtService(JwtProperties properties) {
    this.properties = properties;
    // Deferred key material resolution: the app can boot (and non-auth suites can run) even if
    // D2OS_JWT_SECRET is unset; only issue()/validateAndExtractWorkspace() — the actual auth
    // path — fail, with a clear message rather than a startup crash unrelated to what's being run.
    String secret = properties.secret();
    if (secret == null || secret.isBlank()) {
      this.key = null;
    } else {
      byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
      // Validate length up front with an actionable message. Without this, a too-short secret
      // fails deep inside Keys.hmacShaKeyFor with a WeakKeyException that names neither the
      // property nor the fix — a confusing startup crash for an operator who set a short key.
      if (secretBytes.length < MIN_SECRET_BYTES) {
        throw new IllegalStateException(
            "D2OS_JWT_SECRET must be at least "
                + MIN_SECRET_BYTES
                + " bytes (256 bits) for "
                + "HS256 signing, but was "
                + secretBytes.length
                + " bytes. Generate a strong "
                + "secret with `openssl rand -base64 32`.");
      }
      this.key = Keys.hmacShaKeyFor(secretBytes);
    }
  }

  /**
   * Issue a signed token binding {@code subject} to {@code workspaceId} for the configured
   * lifetime.
   */
  public String issue(UUID workspaceId, String subject) {
    return issue(workspaceId, subject, Duration.ofMinutes(properties.expirationMinutes()));
  }

  /** Issue a signed token with an explicit lifetime, overriding the configured default. */
  public String issue(UUID workspaceId, String subject, Duration ttl) {
    requireKey();
    Instant now = Instant.now();
    return Jwts.builder()
        .subject(subject)
        .issuer(properties.issuer())
        .claim(WORKSPACE_CLAIM, workspaceId.toString())
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plus(ttl)))
        .signWith(key)
        .compact();
  }

  /**
   * Verify {@code token}'s signature, issuer, and expiry, then extract its {@code workspace_id}
   * claim. Returns empty on ANY failure — expired, malformed, wrong signature, wrong issuer, or a
   * missing/non-UUID workspace claim — never a partially-trusted result.
   */
  public Optional<UUID> validateAndExtractWorkspace(String token) {
    if (key == null || token == null || token.isBlank()) {
      return Optional.empty();
    }
    try {
      Claims claims =
          Jwts.parser()
              .verifyWith(key)
              .requireIssuer(properties.issuer())
              .build()
              .parseSignedClaims(token)
              .getPayload();
      String workspaceClaim = claims.get(WORKSPACE_CLAIM, String.class);
      if (workspaceClaim == null) {
        return Optional.empty();
      }
      return Optional.of(UUID.fromString(workspaceClaim));
    } catch (JwtException | IllegalArgumentException e) {
      // JwtException covers ExpiredJwtException and every other verification failure;
      // IllegalArgumentException covers UUID.fromString on a malformed claim value.
      return Optional.empty();
    }
  }

  private void requireKey() {
    if (key == null) {
      throw new IllegalStateException(
          "D2OS_JWT_SECRET is not configured — cannot issue workspace-scoping tokens");
    }
  }
}
