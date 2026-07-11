package com.d2os.tenancy.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 008 US5 (T044/T046): OIDC resource-server security chain. Active ONLY when
 * {@code d2os.security.oidc.enabled=true}; the default posture ({@link SecurityConfig}) is otherwise
 * in force, so existing deployments and every existing test are unaffected. Gating both chains on one
 * boolean guarantees exactly one {@link SecurityFilterChain} is active.
 *
 * <p>When enabled: every request except the tenant-agnostic health/metrics probes must carry a valid
 * OIDC access token (RS256/ES256, verified against the IdP's JWKS via
 * {@code spring.security.oauth2.resourceserver.jwt.*}); {@link RolesJwtConverter} maps the token's
 * roles into authorities and pins {@code sub} as the principal.
 *
 * <p><b>Deferred (not in this additive slice)</b>, because they change behavior the existing
 * integration suites depend on and cannot be verified without a live IdP + a Docker-capable IT run:
 * updating {@link WorkspaceContextFilter} to bind {@code workspace_id} from the OIDC token (T048),
 * populating {@code actor_user_id}/{@code actor_role} into the hash-chained audit records
 * (T050/T051), {@code @PreAuthorize} role gates on the restricted endpoints (T052), and migrating the
 * ~25 existing ITs to mint OIDC test tokens (T053/T054). This class provides the working, opt-in
 * authentication + role-mapping foundation those steps build on.
 */
@Configuration
@ConditionalOnProperty(name = "d2os.security.oidc.enabled", havingValue = "true")
public class OidcSecurityConfig {

  @Bean
  public SecurityFilterChain oidcSecurityFilterChain(HttpSecurity http, RolesJwtConverter rolesJwtConverter)
      throws Exception {
    http.csrf(csrf -> csrf.disable())
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
            .anyRequest().authenticated())
        .oauth2ResourceServer(oauth2 -> oauth2
            .jwt(jwt -> jwt.jwtAuthenticationConverter(rolesJwtConverter)));
    return http.build();
  }
}
