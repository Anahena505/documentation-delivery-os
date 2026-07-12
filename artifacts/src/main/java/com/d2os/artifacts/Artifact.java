package com.d2os.artifacts;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/** A logical generated artifact for a Case (E1.7). */
@Entity
@Table(name = "artifact")
public class Artifact {

  @Id private UUID id;

  @Column(name = "workspace_id", nullable = false)
  private UUID workspaceId;

  @Column(name = "case_instance_id", nullable = false)
  private UUID caseInstanceId;

  @Column(name = "template_definition_id", nullable = false)
  private UUID templateDefinitionId;

  @Column(name = "template_definition_version", nullable = false)
  private String templateDefinitionVersion;

  @Column(name = "artifact_type", nullable = false)
  private String artifactType;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt = OffsetDateTime.now();

  protected Artifact() {}

  public Artifact(
      UUID id,
      UUID workspaceId,
      UUID caseInstanceId,
      UUID templateDefinitionId,
      String templateDefinitionVersion,
      String artifactType) {
    this.id = id;
    this.workspaceId = workspaceId;
    this.caseInstanceId = caseInstanceId;
    this.templateDefinitionId = templateDefinitionId;
    this.templateDefinitionVersion = templateDefinitionVersion;
    this.artifactType = artifactType;
  }

  public UUID getId() {
    return id;
  }

  public UUID getCaseInstanceId() {
    return caseInstanceId;
  }

  public String getArtifactType() {
    return artifactType;
  }
}
