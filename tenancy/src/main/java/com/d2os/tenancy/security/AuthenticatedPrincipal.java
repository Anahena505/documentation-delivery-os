package com.d2os.tenancy.security;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 008 US5 (T047): read-only accessor for the authenticated individual and their roles, resolved from
 * the current {@link SecurityContextHolder}. Used to stamp {@code actor_user_id}/{@code actor_role}
 * on trust-sensitive decisions (data-model.md §2) so the audit trail names WHO decided, not just the
 * workspace.
 *
 * <p>In the default (workspace-scoping) posture there is no per-user principal, so {@link #userId()}
 * returns empty — callers stamping an actor must require OIDC to be enabled. This is a pure helper
 * with no Spring wiring.
 */
public final class AuthenticatedPrincipal {

  private AuthenticatedPrincipal() {}

  /** The authenticated user's identity (JWT {@code sub}), or empty when unauthenticated/anonymous. */
  public static Optional<String> userId() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
      return Optional.empty();
    }
    // The anonymous filter uses the principal name "anonymousUser" — never a real identity.
    if ("anonymousUser".equals(auth.getName())) {
      return Optional.empty();
    }
    return Optional.of(auth.getName());
  }

  /** The roles the current principal holds (authority names with the {@code ROLE_} prefix stripped). */
  public static Set<String> roles() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !auth.isAuthenticated()) {
      return Set.of();
    }
    return auth.getAuthorities().stream()
        .map(GrantedAuthority::getAuthority)
        .map(a -> a.startsWith("ROLE_") ? a.substring("ROLE_".length()) : a)
        .collect(Collectors.toSet());
  }

  /** True when the principal holds {@code role} (compared without the {@code ROLE_} prefix). */
  public static boolean hasRole(String role) {
    return roles().contains(role);
  }
}
