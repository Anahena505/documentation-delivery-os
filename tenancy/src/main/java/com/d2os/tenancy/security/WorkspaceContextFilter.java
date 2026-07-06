package com.d2os.tenancy.security;

import com.d2os.tenancy.WorkspaceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Resolves the authenticated principal's workspace and binds it to {@link WorkspaceContext} for
 * the duration of the request (T010, Principle IV). {@link WorkspaceAwareDataSource} reads this
 * binding and stamps it onto every JDBC connection at checkout — this filter does NOT itself touch
 * the database (an earlier version issued {@code SET app.workspace_id} via a one-off JdbcTemplate
 * call, which landed on whatever connection the pool happened to lend it, not necessarily the one
 * the request's actual transaction later used — a real RLS-bypass bug, found via the integration
 * test).
 *
 * <p>v1 reads the workspace from the {@code X-Workspace-Id} header (set by the authenticated
 * gateway/session layer upstream); replacing this with a JWT-claim lookup is a drop-in swap once
 * full authN is wired in a later phase.
 */
@Component
public class WorkspaceContextFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Workspace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        if (header == null || header.isBlank()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing " + HEADER);
            return;
        }

        UUID workspaceId;
        try {
            workspaceId = UUID.fromString(header);
        } catch (IllegalArgumentException e) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid " + HEADER);
            return;
        }

        try {
            WorkspaceContext.set(workspaceId);
            chain.doFilter(request, response);
        } finally {
            WorkspaceContext.clear();
        }
    }
}
