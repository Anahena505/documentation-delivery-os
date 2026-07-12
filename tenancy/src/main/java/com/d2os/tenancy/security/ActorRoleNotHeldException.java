package com.d2os.tenancy.security;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 008 US5 (T051): raised when an authenticated caller attempts a trust-sensitive decision under a
 * role they do not hold. Enforces the data-model.md validation rule that a recorded {@code
 * actor_role} MUST be one the principal actually holds — a caller cannot stamp a decision with a
 * role they were never granted.
 *
 * <p>Maps to <b>403 Forbidden</b> via {@link ResponseStatus}. Only ever thrown when a per-user
 * principal is present (OIDC mode); in the default workspace-scoping posture {@link
 * AuthenticatedPrincipal#resolveActor(String)} returns empty and this is never reached, so existing
 * default-mode behavior is unaffected.
 */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class ActorRoleNotHeldException extends RuntimeException {

  public ActorRoleNotHeldException(String userId, String requiredRole) {
    super(
        "authenticated user '"
            + userId
            + "' does not hold the required role '"
            + requiredRole
            + "' for this decision");
  }
}
