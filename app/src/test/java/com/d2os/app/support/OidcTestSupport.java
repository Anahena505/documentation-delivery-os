package com.d2os.app.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * 008 US5 (T053): reusable test support for the OIDC resource-server path. Mints RS256 access tokens
 * signed by an in-test RSA keypair, and exposes a matching {@link JwtDecoder} (built from that
 * keypair's public key) so a {@code @SpringBootTest} booted in OIDC mode
 * ({@code d2os.security.oidc.enabled=true}) verifies those tokens without a live IdP or any network.
 *
 * <p>Import {@link OidcTestConfig} into an OIDC-mode IT to supply the test {@link JwtDecoder} bean;
 * because {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}/{@code jwk-set-uri} default
 * empty, this bean is the only decoder on the context (an alternative to pointing {@code jwk-set-uri}
 * at a served JWKS). Existing default-mode ITs never touch this class, so they are unaffected.
 */
public final class OidcTestSupport {

  /** One RSA keypair per test JVM — deterministic within a run, never persisted, not a real secret. */
  private static final RSAKey RSA_KEY = generate();

  private OidcTestSupport() {}

  private static RSAKey generate() {
    try {
      return new RSAKeyGenerator(2048).keyID("d2os-oidc-test").generate();
    } catch (JOSEException e) {
      throw new IllegalStateException("failed to generate test RSA key", e);
    }
  }

  /** A decoder that verifies (RS256 signature + expiry) tokens minted by {@link #mintToken}. */
  public static JwtDecoder jwtDecoder() {
    try {
      return NimbusJwtDecoder.withPublicKey(RSA_KEY.toRSAPublicKey()).build();
    } catch (JOSEException e) {
      throw new IllegalStateException("failed to build test JwtDecoder", e);
    }
  }

  /** Mint a signed OIDC access token with the required {@code sub}, {@code workspace_id}, {@code roles} claims. */
  public static String mintToken(String subject, UUID workspaceId, List<String> roles) {
    try {
      JWTClaimsSet claims = new JWTClaimsSet.Builder()
          .subject(subject)
          .issuer("https://test-idp.d2os.local")
          .claim("workspace_id", workspaceId.toString())
          .claim("roles", roles)
          .issueTime(Date.from(Instant.now()))
          .expirationTime(Date.from(Instant.now().plus(Duration.ofHours(1))))
          .build();
      SignedJWT jwt = new SignedJWT(
          new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(RSA_KEY.getKeyID()).build(), claims);
      jwt.sign(new RSASSASigner(RSA_KEY));
      return jwt.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException("failed to mint test OIDC token", e);
    }
  }

  /** Import into an OIDC-mode IT to supply the test {@link JwtDecoder} bean the resource server uses. */
  @TestConfiguration
  public static class OidcTestConfig {
    @Bean
    public JwtDecoder jwtDecoder() {
      return OidcTestSupport.jwtDecoder();
    }
  }
}
