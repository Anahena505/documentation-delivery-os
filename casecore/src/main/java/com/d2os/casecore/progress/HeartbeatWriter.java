package com.d2os.casecore.progress;

import com.d2os.tenancy.security.WorkspaceRlsBinder;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes a single heartbeat in its own transaction (T034). A separate bean from {@link
 * ProgressHeartbeat} so the {@code @Transactional} boundary is honoured (a self-invoked call would
 * bypass the proxy) and the RLS bind + insert share one connection — required because {@code
 * progress_event}'s RLS policy also gates INSERTs (implicit WITH CHECK), so {@code
 * app.workspace_id} must be set on that connection.
 */
@Component
public class HeartbeatWriter {

  private final ProgressEventRepository repository;
  private final WorkspaceRlsBinder workspaceRlsBinder;

  public HeartbeatWriter(
      ProgressEventRepository repository, WorkspaceRlsBinder workspaceRlsBinder) {
    this.repository = repository;
    this.workspaceRlsBinder = workspaceRlsBinder;
  }

  @Transactional
  public void write(UUID workspaceId, UUID caseId) {
    workspaceRlsBinder.bindCurrentTransaction(workspaceId);
    repository.save(
        new ProgressEvent(workspaceId, caseId, ProgressEvent.Kind.HEARTBEAT, null, null));
  }
}
