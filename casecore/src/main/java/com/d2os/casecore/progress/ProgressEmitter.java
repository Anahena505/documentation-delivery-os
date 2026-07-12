package com.d2os.casecore.progress;

import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single write point for the progress stream (T009, FR-011). Emissions join the caller's
 * transaction by default, so a lifecycle event that is rolled back with its step never surfaces —
 * the stream reflects what actually happened. The heartbeat scheduler (T034) calls this outside any
 * step transaction, where the save runs in its own transaction.
 *
 * <p>Writes are workspace-scoped and rely on the caller having bound the workspace RLS context
 * (every delegate/request path does); the row's {@code workspace_id} is passed explicitly so it is
 * correct even before any read touches an RLS-filtered table.
 */
@Component
public class ProgressEmitter {

  private final ProgressEventRepository repository;

  public ProgressEmitter(ProgressEventRepository repository) {
    this.repository = repository;
  }

  @Transactional
  public void emit(
      UUID workspaceId,
      UUID caseId,
      ProgressEvent.Kind kind,
      String activityId,
      String detailJson) {
    repository.save(new ProgressEvent(workspaceId, caseId, kind, activityId, detailJson));
  }

  public void emit(UUID workspaceId, UUID caseId, ProgressEvent.Kind kind) {
    emit(workspaceId, caseId, kind, null, null);
  }
}
