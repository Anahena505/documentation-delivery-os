package com.d2os.projection;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Composite primary key for {@link ProjectionCheckpoint} — {@code (consumer, workspaceId)}. */
public class ProjectionCheckpointId implements Serializable {

  private String consumer;
  private UUID workspaceId;

  public ProjectionCheckpointId() {}

  public ProjectionCheckpointId(String consumer, UUID workspaceId) {
    this.consumer = consumer;
    this.workspaceId = workspaceId;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof ProjectionCheckpointId that)) return false;
    return Objects.equals(consumer, that.consumer) && Objects.equals(workspaceId, that.workspaceId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(consumer, workspaceId);
  }
}
