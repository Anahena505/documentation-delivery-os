package com.d2os.casecore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The frozen set of definition ({@code type,key,version}) refs bound to a Case at {@code Planned}
 * (AD-4). Written once; the running Case resolves every definition from here, never from the live
 * catalog.
 */
@Entity
@Table(name = "case_definition_snapshot")
public class CaseDefinitionSnapshot {

  @Id private UUID id;

  @Column(name = "workspace_id", nullable = false)
  private UUID workspaceId;

  @Column(name = "case_instance_id", nullable = false)
  private UUID caseInstanceId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false)
  private String entries;

  @Column(name = "frozen_at", nullable = false)
  private OffsetDateTime frozenAt = OffsetDateTime.now();

  protected CaseDefinitionSnapshot() {}

  public CaseDefinitionSnapshot(UUID id, UUID workspaceId, UUID caseInstanceId, String entries) {
    this.id = id;
    this.workspaceId = workspaceId;
    this.caseInstanceId = caseInstanceId;
    this.entries = entries;
  }

  public UUID getId() {
    return id;
  }

  public UUID getCaseInstanceId() {
    return caseInstanceId;
  }

  public String getEntries() {
    return entries;
  }
}
