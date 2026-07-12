package com.d2os.governance.reopen;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The reason/scope/risk record a reopen requires before {@code ReopenService.reopen} may proceed
 * (V20 {@code impact_assessment}, Phase 5 US3, T026, research R3, FR-007). At most one per {@code
 * (gateInstanceId, upstreamArtifactRevisionId)} pair — enforced by the DB's unique constraint.
 */
@Entity
@Table(name = "impact_assessment")
public class ImpactAssessment {

  @Id private UUID id;

  @Column(name = "workspace_id", nullable = false)
  private UUID workspaceId;

  @Column(name = "gate_instance_id", nullable = false)
  private UUID gateInstanceId;

  @Column(name = "upstream_artifact_revision_id", nullable = false)
  private UUID upstreamArtifactRevisionId;

  @Column(nullable = false)
  private String reason;

  @Column(nullable = false)
  private String scope;

  @Column(nullable = false)
  private String risk;

  @Column(nullable = false)
  private String author;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt = OffsetDateTime.now();

  protected ImpactAssessment() {}

  public ImpactAssessment(
      UUID id,
      UUID workspaceId,
      UUID gateInstanceId,
      UUID upstreamArtifactRevisionId,
      String reason,
      String scope,
      String risk,
      String author) {
    this.id = id;
    this.workspaceId = workspaceId;
    this.gateInstanceId = gateInstanceId;
    this.upstreamArtifactRevisionId = upstreamArtifactRevisionId;
    this.reason = reason;
    this.scope = scope;
    this.risk = risk;
    this.author = author;
  }

  public UUID getId() {
    return id;
  }

  public UUID getWorkspaceId() {
    return workspaceId;
  }

  public UUID getGateInstanceId() {
    return gateInstanceId;
  }

  public UUID getUpstreamArtifactRevisionId() {
    return upstreamArtifactRevisionId;
  }

  public String getReason() {
    return reason;
  }

  public String getScope() {
    return scope;
  }

  public String getRisk() {
    return risk;
  }

  public String getAuthor() {
    return author;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }
}
