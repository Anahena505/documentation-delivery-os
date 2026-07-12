package com.d2os.tenancy.security;

import com.d2os.tenancy.WorkspaceContext;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Stamps every connection handed out by the pool with the current request's workspace id, right at
 * checkout (T045 hardening). Replaces an earlier, broken approach where {@code
 * WorkspaceContextFilter} issued {@code SET app.workspace_id} via a one-off {@code JdbcTemplate}
 * call: that statement landed on whichever connection the pool happened to lend it, not necessarily
 * the one the request's actual transaction later used — meaning RLS could silently see a stale or
 * absent workspace id, or (worse) a *previous* request's leftover value. Binding at {@code
 * getConnection()} time guarantees every connection used for a unit of work carries the correct
 * value, and unconditionally overwrites on every checkout so nothing leaks across pooled reuse.
 *
 * <p>Uses {@code set_config(..., false)} (session-scoped, not transaction-local) so it applies
 * regardless of whether the caller starts a transaction afterward. When no workspace is bound (e.g.
 * a startup-time seed job), the reserved system-workspace nil UUID is set instead of leaving the
 * setting unset — every RLS policy's {@code OR workspace_id = '0000...'} branch already accounts
 * for exactly this case.
 */
public class WorkspaceAwareDataSource extends DelegatingDataSource {

  private static final UUID SYSTEM_WORKSPACE =
      UUID.fromString("00000000-0000-0000-0000-000000000000");

  public WorkspaceAwareDataSource(DataSource targetDataSource) {
    super(targetDataSource);
  }

  @Override
  public Connection getConnection() throws SQLException {
    return stamp(super.getConnection());
  }

  @Override
  public Connection getConnection(String username, String password) throws SQLException {
    return stamp(super.getConnection(username, password));
  }

  private Connection stamp(Connection connection) throws SQLException {
    UUID workspaceId = WorkspaceContext.currentOrNull();
    UUID effective = workspaceId != null ? workspaceId : SYSTEM_WORKSPACE;
    try (Statement st = connection.createStatement()) {
      st.execute("SELECT set_config('app.workspace_id', '" + effective + "', false)");
    }
    return connection;
  }
}
