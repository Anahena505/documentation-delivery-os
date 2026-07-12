package com.d2os.tenancy.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * 008 US5 (T046): maps an OIDC access token's {@code roles} (or {@code groups}) claim to Spring
 * Security {@code ROLE_*} authorities, and pins the token's {@code sub} as the principal name so
 * the authenticated user's identity is available to {@link AuthenticatedPrincipal} and to
 * audit-actor stamping.
 *
 * <p>Active only when OIDC is enabled ({@code d2os.security.oidc.enabled=true}); in the default
 * posture no OIDC chain exists and this converter is never instantiated.
 */
@Component
@ConditionalOnProperty(name = "d2os.security.oidc.enabled", havingValue = "true")
public class RolesJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {

  @Override
  public AbstractAuthenticationToken convert(Jwt jwt) {
    Collection<GrantedAuthority> authorities = new ArrayList<>();
    for (String role : rolesFrom(jwt)) {
      authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
    }
    // Principal name = sub (the authenticated individual), used as actor_user_id on decisions.
    return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
  }

  /** Reads the {@code roles} claim, falling back to {@code groups}; tolerates missing/empty. */
  @SuppressWarnings("unchecked")
  private static List<String> rolesFrom(Jwt jwt) {
    Object raw = jwt.getClaims().getOrDefault("roles", jwt.getClaims().get("groups"));
    if (raw instanceof List<?> list) {
      List<String> out = new ArrayList<>(list.size());
      for (Object o : list) {
        if (o != null) {
          out.add(o.toString());
        }
      }
      return out;
    }
    return List.of();
  }
}
