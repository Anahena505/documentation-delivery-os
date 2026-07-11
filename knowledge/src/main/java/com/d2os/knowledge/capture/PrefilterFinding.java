package com.d2os.knowledge.capture;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A deterministic sensitivity/PII finding produced by {@link SensitivityPreFilter} (FR-010,
 * research R7, V13 {@code prefilter_finding}). Append-only (V13 REVOKEs UPDATE/DELETE for {@code
 * d2os_app}) — a finding is a fact about the captured content at pre-filter time and is never
 * edited.
 */
@Entity
@Table(name = "prefilter_finding")
public class PrefilterFinding {

  public enum Category {
    EMAIL,
    PHONE,
    ID_NUMBER,
    CREDENTIAL,
    TAGGED_SENSITIVE
  }

  @Id private UUID id;

  @Column(name = "workspace_id", nullable = false)
  private UUID workspaceId;

  @Column(name = "candidate_id", nullable = false)
  private UUID candidateId;

  @Column(nullable = false)
  private String category;

  @Column(name = "span_start", nullable = false)
  private int spanStart;

  @Column(name = "span_end", nullable = false)
  private int spanEnd;

  @Column(nullable = false)
  private String source;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt = OffsetDateTime.now();

  protected PrefilterFinding() {}

  public PrefilterFinding(
      UUID id,
      UUID workspaceId,
      UUID candidateId,
      Category category,
      int spanStart,
      int spanEnd,
      String source) {
    this.id = id;
    this.workspaceId = workspaceId;
    this.candidateId = candidateId;
    this.category = category.name();
    this.spanStart = spanStart;
    this.spanEnd = spanEnd;
    this.source = source;
  }

  public UUID getId() {
    return id;
  }

  public UUID getWorkspaceId() {
    return workspaceId;
  }

  public UUID getCandidateId() {
    return candidateId;
  }

  public String getCategory() {
    return category;
  }

  public int getSpanStart() {
    return spanStart;
  }

  public int getSpanEnd() {
    return spanEnd;
  }

  public String getSource() {
    return source;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }
}
