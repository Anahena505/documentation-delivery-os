package com.d2os.tenancy.security;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Binds the RLS workspace variable onto the <em>current transaction's</em> connection (T045).
 *
 * <p>Needed for work that runs outside an HTTP request — specifically Flowable async job-executor
 * threads. There, {@link WorkspaceAwareDataSource} stamps the connection at checkout time, but
 * Flowable opens the job's command/transaction (and checks out the connection) <em>before</em> the
 * delegate can set {@link com.d2os.tenancy.WorkspaceContext} — so the connection carries the nil
 * system workspace and RLS filters out the tenant's own rows. Calling this at the start of a
 * delegate re-issues {@code set_config(..., true)} (transaction-local) on the connection the
 * transaction is already using, so every read/write for the rest of that transaction sees the
 * correct workspace. {@code true} makes it {@code SET LOCAL}, so it is discarded automatically at
 * transaction end — nothing leaks back to the pool.
 */
@Component
public class WorkspaceRlsBinder {

  private final JdbcTemplate jdbcTemplate;

  public WorkspaceRlsBinder(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public void bindCurrentTransaction(UUID workspaceId) {
    // workspaceId is a validated UUID (never user-controlled free text), so literal interpolation
    // is safe here; set_config's parameterized form can't be used for the SET target anyway.
    jdbcTemplate.execute("SELECT set_config('app.workspace_id', '" + workspaceId + "', true)");
  }
}
