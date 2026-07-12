package com.d2os.knowledge.capture;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A lessons-learned proposal from the case-end capture subprocess (FR-008, V13, data-model.md).
 * Born PROJECT-scoped/confidential/non-promotable; the promotion pipeline is a strict, fixed-order
 * state machine with NO skip path:
 *
 * <pre>
 *   CAPTURED → PREFILTERED → REDACTED → D4_PENDING → PUBLISHED
 *        └────────┴─────────────┴────────────┴──────→ REJECTED (terminal, with stage + reason)
 * </pre>
 *
 * <p>A redaction never edits a candidate in place — it creates a NEW revision row (revision+1,
 * {@code revisionOf} = the prior id) so the pre-redaction version is preserved for audit (FR-011).
 * The D4 gate reviews the LATEST revision only.
 *
 * <p>Every V13 {@code capture_candidate} column is mapped here; {@code tags} is a Postgres {@code
 * text[]} mapped as {@code String[]} with {@link JdbcTypeCode}({@link SqlTypes#ARRAY}) exactly as
 * {@code KnowledgeItem.tags} in US1. The status mutators VALIDATE the allowed transitions and throw
 * {@link IllegalCandidateTransitionException} on any illegal move — the state machine is enforced
 * in code, not merely commented.
 */
@Entity
@Table(name = "capture_candidate")
public class CaptureCandidate {

  public enum Status {
    CAPTURED,
    PREFILTERED,
    REDACTED,
    D4_PENDING,
    PUBLISHED,
    REJECTED
  }

  public enum RejectionStage {
    PREFILTER,
    CURATION,
    D4
  }

  @Id private UUID id;

  @Column(name = "workspace_id", nullable = false)
  private UUID workspaceId;

  @Column(name = "case_instance_id", nullable = false)
  private UUID caseInstanceId;

  @Column(name = "project_id", nullable = false)
  private UUID projectId;

  @Column(nullable = false)
  private int revision = 1;

  @Column(name = "revision_of")
  private UUID revisionOf;

  @Column(nullable = false)
  private String title;

  @Column(nullable = false)
  private String content;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(nullable = false)
  private String[] tags = new String[0];

  @Column(nullable = false)
  private String status = Status.CAPTURED.name();

  @Column(name = "curator_operation_execution_id")
  private UUID curatorOperationExecutionId;

  @Column(name = "rejection_stage")
  private String rejectionStage;

  @Column(name = "rejection_reason")
  private String rejectionReason;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt = OffsetDateTime.now();

  protected CaptureCandidate() {}

  /**
   * Construct a fresh revision-1 candidate, born {@code CAPTURED} and non-promotable (FR-008). The
   * project scope is the case's project (birth scope is always PROJECT — R4).
   */
  public CaptureCandidate(
      UUID id,
      UUID workspaceId,
      UUID caseInstanceId,
      UUID projectId,
      String title,
      String content,
      String[] tags) {
    this.id = id;
    this.workspaceId = workspaceId;
    this.caseInstanceId = caseInstanceId;
    this.projectId = projectId;
    this.revision = 1;
    this.revisionOf = null;
    this.title = title;
    this.content = content;
    this.tags = tags == null ? new String[0] : tags;
    this.status = Status.CAPTURED.name();
  }

  /**
   * Construct a NEW redaction revision (revision+1, {@code revisionOf} = prior id), born already
   * {@code REDACTED} — the prior revision is preserved untouched (FR-011). The D4 gate reviews this
   * (latest) revision only.
   */
  static CaptureCandidate redactionRevision(
      UUID id,
      CaptureCandidate prior,
      String title,
      String content,
      String[] tags,
      UUID curatorOperationExecutionId) {
    CaptureCandidate c = new CaptureCandidate();
    c.id = id;
    c.workspaceId = prior.workspaceId;
    c.caseInstanceId = prior.caseInstanceId;
    c.projectId = prior.projectId;
    c.revision = prior.revision + 1;
    c.revisionOf = prior.id;
    c.title = title;
    c.content = content;
    c.tags = tags == null ? new String[0] : tags;
    c.status = Status.REDACTED.name();
    c.curatorOperationExecutionId = curatorOperationExecutionId;
    return c;
  }

  public Status status() {
    return Status.valueOf(status);
  }

  /** CAPTURED → PREFILTERED once the deterministic pre-filter has run and findings are recorded. */
  public void markPrefiltered() {
    transitionTo(Status.PREFILTERED);
  }

  /**
   * Apply the pre-filter's default-exclusion: replace the content with the redacted content and
   * transition CAPTURED → PREFILTERED in one step, so a single persist writes both (avoids a stale
   * JPA write-back clobbering a separate raw-JDBC content update). This is the allowed {@code
   * capture_candidate} UPDATE (T3-c default exclusion on revision 1).
   */
  void applyPrefilterRedaction(String redactedContent) {
    this.content = redactedContent;
    transitionTo(Status.PREFILTERED);
  }

  /**
   * REDACTED → D4_PENDING: the latest redacted revision is queued for the workspace-owner D4 gate.
   * Called on the revision row that carries the Curator redaction.
   */
  public void markD4Pending() {
    transitionTo(Status.D4_PENDING);
  }

  /**
   * D4_PENDING → PUBLISHED once the D4 gate APPROVES and the KnowledgeItem version is published.
   */
  public void markPublished() {
    transitionTo(Status.PUBLISHED);
  }

  /**
   * Any non-terminal stage → REJECTED (terminal): the candidate stays confidential/non-promotable
   * with the gate stage + reason recorded (FR-013). No partial promotion.
   */
  public void reject(RejectionStage stage, String reason) {
    transitionTo(Status.REJECTED);
    this.rejectionStage = stage.name();
    this.rejectionReason = reason;
  }

  public void setCuratorOperationExecutionId(UUID id) {
    this.curatorOperationExecutionId = id;
  }

  /**
   * Enforce the fixed-order state machine (data-model.md §CaptureCandidate). Any REDACTED,
   * PREFILTERED, CAPTURED or D4_PENDING state may transition to REJECTED; the forward path is
   * strictly ordered. Terminal states (PUBLISHED, REJECTED) accept no further transition.
   */
  private void transitionTo(Status target) {
    Status current = status();
    boolean allowed =
        switch (target) {
          case PREFILTERED -> current == Status.CAPTURED;
          case REDACTED -> current == Status.PREFILTERED;
          case D4_PENDING -> current == Status.REDACTED;
          case PUBLISHED -> current == Status.D4_PENDING;
          case REJECTED ->
              current == Status.CAPTURED
                  || current == Status.PREFILTERED
                  || current == Status.REDACTED
                  || current == Status.D4_PENDING;
          case CAPTURED -> false; // birth-only; never a transition target
        };
    if (!allowed) {
      throw new IllegalCandidateTransitionException(current, target);
    }
    this.status = target.name();
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

  public UUID getProjectId() {
    return projectId;
  }

  public int getRevision() {
    return revision;
  }

  public UUID getRevisionOf() {
    return revisionOf;
  }

  public String getTitle() {
    return title;
  }

  public String getContent() {
    return content;
  }

  public String[] getTags() {
    return tags;
  }

  public String getStatus() {
    return status;
  }

  public UUID getCuratorOperationExecutionId() {
    return curatorOperationExecutionId;
  }

  public String getRejectionStage() {
    return rejectionStage;
  }

  public String getRejectionReason() {
    return rejectionReason;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }
}
