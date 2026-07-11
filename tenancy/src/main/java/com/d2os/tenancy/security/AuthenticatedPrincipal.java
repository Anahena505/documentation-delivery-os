package com.d2os.tenancy.security;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 008 US5 (T047): read-only accessor for the authenticated individual and their roles, resolved
 * from the current {@link SecurityContextHolder}. Used to stamp {@code actor_user_id}/{@code
 * actor_role} on trust-sensitive decisions (data-model.md §2) so the audit trail names WHO decided,
 * not just the workspace.
 *
 * <p>In the default (workspace-scoping) posture there is no per-user principal, so {@link
 * #userId()} returns empty — callers stamping an actor must require OIDC to be enabled. This is a
 * pure helper with no Spring wiring.
 */
public final class AuthenticatedPrincipal {

  private AuthenticatedPrincipal() {}

  /**
   * The authenticated user's identity (JWT {@code sub}), or empty when unauthenticated/anonymous.
   */
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

  /**
   * The roles the current principal holds (authority names with the {@code ROLE_} prefix stripped).
   */
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

  /**
   * 008 US5 (T051): resolve the actor stamp for a trust-sensitive decision authorized under {@code
   * requiredRole}, for persistence into {@code audit_entry.actor_user_id}/{@code actor_role}.
   *
   * <p>Returns {@link Optional#empty()} in the default (workspace-scoping) posture, where there is
   * no per-user principal — the caller then leaves both actor columns NULL, exactly as before OIDC,
   * so default-mode behavior and the audit hash chain are unchanged (see {@code
   * AuditChainCanonicalizer}).
   *
   * <p>When a per-user principal IS present (OIDC mode) it MUST hold {@code requiredRole},
   * otherwise {@link ActorRoleNotHeldException} (→ 403) — a recorded {@code actor_role} can never
   * be one the actor does not hold (data-model.md validation rule). For endpoints already gated by
   * {@code @PreAuthorize} this is a belt-and-suspenders backstop; for any ungated trust-sensitive
   * writer it is the enforcement point itself.
   */
  public static Optional<ActorStamp> resolveActor(String requiredRole) {
    Optional<String> uid = userId();
    if (uid.isEmpty()) {
      return Optional.empty(); // default (non-OIDC) posture: no per-user identity to stamp
    }
    if (!hasRole(requiredRole)) {
      throw new ActorRoleNotHeldException(uid.get(), requiredRole);
    }
    return Optional.of(new ActorStamp(uid.get(), requiredRole));
  }

  /**
   * The authenticated individual + the role they were authorized under, for an audit actor stamp.
   */
  public record ActorStamp(String userId, String role) {}
}
