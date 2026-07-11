package com.d2os.governance.reopen;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One DMN-identified dependent of a revised, previously-approved artifact (V20 {@code
 * gate_reopen_candidate}, Phase 5 US3, T025, research R3, FR-006). {@code depth=1} rows are direct
 * dependents — reopenable via {@code ReopenService}; {@code depth>1} rows are transitive and always
 * {@code MANUAL_REVIEW}, never auto-reopened (Q3/AD-5).
 */
@Entity
@Table(name = "gate_reopen_candidate")
public class GateReopenCandidate {

  public enum Disposition {
    PENDING,
    REOPENED,
    MANUAL_REVIEW,
    DISMISSED
  }

  @Id private UUID id;

  @Column(name = "workspace_id", nullable = false)
  private UUID workspaceId;

  @Column(name = "upstream_artifact_revision_id", nullable = false)
  private UUID upstreamArtifactRevisionId;

  @Column(name = "dependent_artifact_revision_id", nullable = false)
  private UUID dependentArtifactRevisionId;

  @Column(name = "gate_instance_id")
  private UUID gateInstanceId;

  @Column(nullable = false)
  private int depth;

  @Column(nullable = false)
  private String disposition = Disposition.PENDING.name();

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt = OffsetDateTime.now();

  protected GateReopenCandidate() {}

  public GateReopenCandidate(
      UUID id,
      UUID workspaceId,
      UUID upstreamArtifactRevisionId,
      UUID dependentArtifactRevisionId,
      UUID gateInstanceId,
      int depth,
      Disposition disposition) {
    this.id = id;
    this.workspaceId = workspaceId;
    this.upstreamArtifactRevisionId = upstreamArtifactRevisionId;
    this.dependentArtifactRevisionId = dependentArtifactRevisionId;
    this.gateInstanceId = gateInstanceId;
    this.depth = depth;
    this.disposition = disposition.name();
  }

  public void markDisposition(Disposition target) {
    this.disposition = target.name();
  }

  public UUID getId() {
    return id;
  }

  public UUID getWorkspaceId() {
    return workspaceId;
  }

  public UUID getUpstreamArtifactRevisionId() {
    return upstreamArtifactRevisionId;
  }

  public UUID getDependentArtifactRevisionId() {
    return dependentArtifactRevisionId;
  }

  public UUID getGateInstanceId() {
    return gateInstanceId;
  }

  public int getDepth() {
    return depth;
  }

  public Disposition getDisposition() {
    return Disposition.valueOf(disposition);
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }
}
