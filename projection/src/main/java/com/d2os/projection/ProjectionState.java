package com.d2os.projection;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row per workspace (V28, data-model.md ProjectionState, research R4) — {@link #liveGeneration}
 * is {@link com.d2os.projection.GraphNode}/{@link GraphEdge}'s live-read filter and the atomic-flip
 * target of the rebuild job (T009, a later phase); {@code lastEquivalenceCheck}/{@code
 * lastEquivalenceResult} record the most recent {@code EquivalenceVerifier} run (T010, a later
 * phase), whether from a rebuild or the scheduled live-generation drift check (research R5).
 */
@Entity
@Table(name = "projection_state")
public class ProjectionState {

  @Id
  @Column(name = "workspace_id", nullable = false)
  private UUID workspaceId;

  @Column(name = "live_generation", nullable = false)
  private int liveGeneration;

  @Column(name = "last_equivalence_check")
  private OffsetDateTime lastEquivalenceCheck;

  @Column(name = "last_equivalence_result")
  private String lastEquivalenceResult;

  protected ProjectionState() {}

  public ProjectionState(
      UUID workspaceId,
      int liveGeneration,
      OffsetDateTime lastEquivalenceCheck,
      String lastEquivalenceResult) {
    this.workspaceId = workspaceId;
    this.liveGeneration = liveGeneration;
    this.lastEquivalenceCheck = lastEquivalenceCheck;
    this.lastEquivalenceResult = lastEquivalenceResult;
  }

  public UUID getWorkspaceId() {
    return workspaceId;
  }

  public int getLiveGeneration() {
    return liveGeneration;
  }

  public OffsetDateTime getLastEquivalenceCheck() {
    return lastEquivalenceCheck;
  }

  public String getLastEquivalenceResult() {
    return lastEquivalenceResult;
  }
}
