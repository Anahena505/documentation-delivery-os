package com.d2os.artifacts;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** The assembled, integrity-stamped deliverable for a Case (E1.7, SC-005). */
@Entity
@Table(name = "execution_package")
public class ExecutionPackage {

  @Id private UUID id;

  @Column(name = "workspace_id", nullable = false)
  private UUID workspaceId;

  @Column(name = "case_instance_id", nullable = false)
  private UUID caseInstanceId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false)
  private String manifest;

  @Column(name = "manifest_hash", nullable = false)
  private String manifestHash;

  @Column(nullable = false)
  private String status = "assembled";

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt = OffsetDateTime.now();

  protected ExecutionPackage() {}

  public ExecutionPackage(
      UUID id, UUID workspaceId, UUID caseInstanceId, String manifest, String manifestHash) {
    this.id = id;
    this.workspaceId = workspaceId;
    this.caseInstanceId = caseInstanceId;
    this.manifest = manifest;
    this.manifestHash = manifestHash;
  }

  public UUID getId() {
    return id;
  }

  public UUID getWorkspaceId() {
    return workspaceId;
  }

  public UUID getCaseInstanceId() {
    return caseInstanceId;
  }

  public String getManifest() {
    return manifest;
  }

  public String getManifestHash() {
    return manifestHash;
  }
}
