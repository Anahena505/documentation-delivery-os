package com.d2os.projection;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * {@code d2os_projector}-side equivalent of {@code com.d2os.tenancy.security.WorkspaceRlsBinder}
 * (T007). graph_node/graph_edge/etc. are RLS-policied (V28) and the projector role does not own
 * them, so writes through {@link com.d2os.projection.config.ProjectorDataSourceConfig}'s dedicated
 * datasource still need {@code app.workspace_id} bound on the connection for RLS to admit the row.
 *
 * <p>The projector is a background job sweeping every workspace (the {@code ReconciliationJob}
 * pattern in orchestration), not request-scoped, so — like {@code WorkspaceRlsBinder} — this binds
 * {@code SET LOCAL} on the CURRENT transaction's connection rather than stamping at pool checkout;
 * callers (the {@code Projector}/{@code RebuildJob}, T008/T009, a later phase) must call this at
 * the start of each per-workspace unit of work, inside a transaction bound to {@code
 * projectorTransactionManager}.
 */
@Component
public class ProjectorRlsBinder {

    private final JdbcTemplate projectorJdbcTemplate;

    public ProjectorRlsBinder(@Qualifier("projectorJdbcTemplate") JdbcTemplate projectorJdbcTemplate) {
        this.projectorJdbcTemplate = projectorJdbcTemplate;
    }

    public void bindCurrentTransaction(UUID workspaceId) {
        // workspaceId is a validated UUID (never user-controlled free text), so literal
        // interpolation is safe here; set_config's parameterized form can't be used for the SET
        // target anyway (same reasoning as WorkspaceRlsBinder).
        projectorJdbcTemplate.execute("SELECT set_config('app.workspace_id', '" + workspaceId + "', true)");
    }
}
