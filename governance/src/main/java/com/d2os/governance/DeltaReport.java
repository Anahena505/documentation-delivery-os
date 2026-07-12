package com.d2os.governance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A deterministic unified diff, either between two revisions of one Artifact (V20 {@code
 * delta_report}, research R2, T020) OR — since V26's widening (tasks.md T005/T014, research R3) —
 * between two {@code DefinitionAsset} versions' {@code body} JSON (the studio's D4 publish-review
 * content). Produced by {@link DeltaReportService} — never an AI-generated "what changed" summary,
 * so the report itself is replayable and tamper-checkable ({@code diffHash} = SHA-256 over {@code
 * diffContent}). {@code chk_delta_report_subject_shape} (V26) enforces that a row is unambiguously
 * EITHER the artifact-triple shape ({@code artifactId}/{@code fromRevisionId}/{@code toRevisionId}
 * set, {@code fromDefinitionId}/{@code toDefinitionId} null) OR the definition-pair shape (the
 * reverse), never both and never neither — so all five id columns are nullable at the Java level
 * too, with exactly one triple/pair populated per row depending on which constructor built it.
 */
@Entity
@Table(name = "delta_report")
public class DeltaReport {

  @Id private UUID id;

  @Column(name = "workspace_id", nullable = false)
  private UUID workspaceId;

  @Column(name = "artifact_id")
  private UUID artifactId;

  @Column(name = "from_revision_id")
  private UUID fromRevisionId;

  @Column(name = "to_revision_id")
  private UUID toRevisionId;

  /**
   * V26, T014: the prior published {@code DefinitionAsset} being superseded (definition-pair
   * shape).
   */
  @Column(name = "from_definition_id")
  private UUID fromDefinitionId;

  /** V26, T014: the {@code DefinitionAsset} (draft) under review (definition-pair shape). */
  @Column(name = "to_definition_id")
  private UUID toDefinitionId;

  @Column(name = "diff_content", nullable = false)
  private String diffContent;

  @Column(name = "diff_hash", nullable = false)
  private String diffHash;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt = OffsetDateTime.now();

  protected DeltaReport() {}

  /** Artifact-triple shape (V20, unchanged) — two revisions of one Artifact. */
  public DeltaReport(
      UUID id,
      UUID workspaceId,
      UUID artifactId,
      UUID fromRevisionId,
      UUID toRevisionId,
      String diffContent,
      String diffHash) {
    this.id = id;
    this.workspaceId = workspaceId;
    this.artifactId = artifactId;
    this.fromRevisionId = fromRevisionId;
    this.toRevisionId = toRevisionId;
    this.diffContent = diffContent;
    this.diffHash = diffHash;
  }

  /**
   * Definition-pair shape (V26, T014): diffs two {@code DefinitionAsset} bodies directly — no
   * object storage involved, the content is already in-memory. {@code fromDefinitionId}/{@code
   * toDefinitionId} must both be non-null (the CHECK constraint requires it); a first-ever publish
   * with no prior version to diff against has no legal row to write under this shape — {@code
   * DeltaReportService#generateForDefinitions}'s caller must skip generation entirely in that case
   * (documented in {@code studio.PublishService}). Distinguishable from the artifact-triple
   * constructor above by arity alone (6 args vs. 7), so no disambiguating flag is needed.
   */
  public DeltaReport(
      UUID id,
      UUID workspaceId,
      UUID fromDefinitionId,
      UUID toDefinitionId,
      String diffContent,
      String diffHash) {
    this.id = id;
    this.workspaceId = workspaceId;
    this.fromDefinitionId = fromDefinitionId;
    this.toDefinitionId = toDefinitionId;
    this.diffContent = diffContent;
    this.diffHash = diffHash;
  }

  public UUID getId() {
    return id;
  }

  public UUID getWorkspaceId() {
    return workspaceId;
  }

  public UUID getArtifactId() {
    return artifactId;
  }

  public UUID getFromRevisionId() {
    return fromRevisionId;
  }

  public UUID getToRevisionId() {
    return toRevisionId;
  }

  public UUID getFromDefinitionId() {
    return fromDefinitionId;
  }

  public UUID getToDefinitionId() {
    return toDefinitionId;
  }

  public String getDiffContent() {
    return diffContent;
  }

  public String getDiffHash() {
    return diffHash;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }
}
