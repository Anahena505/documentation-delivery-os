package com.d2os.tenancy.security;

import com.d2os.tenancy.WorkspaceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the authenticated principal's workspace and binds it to {@link WorkspaceContext} for the
 * duration of the request (T010/T067, Principle IV). {@link WorkspaceAwareDataSource} reads this
 * binding and stamps it onto every JDBC connection at checkout — this filter does NOT itself touch
 * the database (an earlier version issued {@code SET app.workspace_id} via a one-off JdbcTemplate
 * call, which landed on whatever connection the pool happened to lend it, not necessarily the one
 * the request's actual transaction later used — a real RLS-bypass bug, found via the integration
 * test).
 *
 * <p><b>OIDC path (008 US5, T048):</b> when {@code d2os.security.oidc.enabled=true}, Spring
 * Security's resource-server chain ({@link OidcSecurityConfig}) has already verified the OIDC
 * access token (RS256/ES256 against the IdP JWKS) and placed a {@link JwtAuthenticationToken} in
 * the {@link SecurityContextHolder} <i>before</i> this filter runs. In that mode the workspace is
 * resolved from that verified token's {@code workspace_id} claim and the two default-mode paths
 * below are not consulted at all. This branch is inert in the default posture, where the security
 * context holds no {@code JwtAuthenticationToken}.
 *
 * <p><b>Primary path (T067, default posture):</b> an {@code Authorization: Bearer <jwt>} header,
 * cryptographically verified by {@link JwtService} — signature, issuer, and expiry all checked —
 * with the workspace resolved from the token's {@code workspace_id} claim, never from anything
 * client-asserted.
 *
 * <p><b>Fallback path (v1 stopgap, T010):</b> an unauthenticated {@code X-Workspace-Id} header.
 * This is accepted ONLY when {@link JwtProperties#allowHeaderWorkspaceFallback()} is {@code true} —
 * which defaults to {@code false} and is enabled solely in {@code
 * app/src/test/resources/application.properties}, so production genuinely requires a valid JWT (the
 * header path is provably unreachable unless explicitly opted into) while every existing
 * integration test — authored before JWT support existed, and asserting business logic, not auth —
 * keeps passing unchanged.
 *
 * <p><b>Health probes bypass the filter entirely</b> (see {@link #shouldNotFilter}): actuator
 * health/liveness/readiness endpoints carry no tenant data, and under the production no-fallback
 * posture a workspace-less probe would otherwise get 401 — making an orchestrator consider a
 * perfectly healthy app down.
 */
@Component
public class WorkspaceContextFilter extends OncePerRequestFilter {

  private static final String WORKSPACE_HEADER = "X-Workspace-Id";
  private static final String AUTH_HEADER = "Authorization";
  private static final String BEARER_PREFIX = "Bearer ";

  /**
   * OIDC access-token claim carrying the tenant scope (008 US5, T048;
   * contracts/auth-and-rbac.yaml).
   */
  private static final String WORKSPACE_CLAIM = "workspace_id";

  /**
   * Actuator health base path (default management base-path {@code /actuator}); covers
   * liveness/readiness sub-paths.
   */
  private static final String HEALTH_PATH = "/actuator/health";

  private final JwtService jwtService;
  private final JwtProperties jwtProperties;

  public WorkspaceContextFilter(JwtService jwtService, JwtProperties jwtProperties) {
    this.jwtService = jwtService;
    this.jwtProperties = jwtProperties;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    // Never gate infrastructure health probes on a workspace-scoping JWT — they are
    // tenant-agnostic,
    // and gating them would 401 healthy instances under the production no-fallback posture.
    String path = request.getRequestURI();
    String contextPath = request.getContextPath();
    if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
      path = path.substring(contextPath.length());
    }
    return path.equals(HEALTH_PATH) || path.startsWith(HEALTH_PATH + "/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    // 008 US5 (T048) — OIDC path: if the resource-server chain has already authenticated this
    // request (OIDC mode), resolve the workspace from the VERIFIED token's workspace_id claim and
    // bypass the default-mode HS256/header paths entirely. In the default posture no
    // JwtAuthenticationToken is present, so this branch is skipped and behavior is unchanged.
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth instanceof JwtAuthenticationToken jwtAuth) {
      Object claim = jwtAuth.getToken().getClaims().get(WORKSPACE_CLAIM);
      if (claim == null || claim.toString().isBlank()) {
        response.sendError(
            HttpServletResponse.SC_UNAUTHORIZED,
            "OIDC token is missing the required workspace_id claim");
        return;
      }
      UUID oidcWorkspaceId;
      try {
        oidcWorkspaceId = UUID.fromString(claim.toString());
      } catch (IllegalArgumentException e) {
        response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid workspace_id claim");
        return;
      }
      bindAndContinue(oidcWorkspaceId, request, response, chain);
      return;
    }

    // ---- Default (workspace-scoping) posture: UNCHANGED from T067/T010 --------------------------
    Optional<UUID> workspaceId = resolveFromBearerToken(request);

    if (workspaceId.isEmpty()) {
      String authHeader = request.getHeader(AUTH_HEADER);
      if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
        // A Bearer token WAS presented but failed verification — never silently fall through
        // to the header path for a request that attempted (and failed) real authentication.
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired bearer token");
        return;
      }
      workspaceId = resolveFromHeaderFallback(request, response);
      if (workspaceId == null) {
        return; // fallback helper already wrote the error response
      }
    }

    bindAndContinue(workspaceId.get(), request, response, chain);
  }

  /**
   * Bind the resolved workspace to {@link WorkspaceContext} for the request, then always clear it.
   */
  private void bindAndContinue(
      UUID workspaceId, HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    try {
      WorkspaceContext.set(workspaceId);
      chain.doFilter(request, response);
    } finally {
      WorkspaceContext.clear();
    }
  }

  private Optional<UUID> resolveFromBearerToken(HttpServletRequest request) {
    String authHeader = request.getHeader(AUTH_HEADER);
    if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
      return Optional.empty();
    }
    return jwtService.validateAndExtractWorkspace(authHeader.substring(BEARER_PREFIX.length()));
  }

  /**
   * Returns the resolved workspace, or null if it already wrote an error response (caller must
   * return).
   */
  private Optional<UUID> resolveFromHeaderFallback(
      HttpServletRequest request, HttpServletResponse response) throws IOException {
    if (!jwtProperties.allowHeaderWorkspaceFallback()) {
      response.sendError(
          HttpServletResponse.SC_UNAUTHORIZED,
          "Missing Authorization bearer token (unauthenticated X-Workspace-Id fallback is disabled)");
      return null;
    }
    String header = request.getHeader(WORKSPACE_HEADER);
    if (header == null || header.isBlank()) {
      response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing " + WORKSPACE_HEADER);
      return null;
    }
    try {
      return Optional.of(UUID.fromString(header));
    } catch (IllegalArgumentException e) {
      response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid " + WORKSPACE_HEADER);
      return null;
    }
  }
}
