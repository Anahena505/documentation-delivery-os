package com.d2os.intake.attachment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The sanitized, persona-consumable representation of one attachment (US5, FR-015, T043). This is
 * the ONLY attachment-derived text a persona ever sees — always inside untrusted-data delimiters
 * (T1-a). It carries the reproducibility snapshot of the summarize call inline (model id/version +
 * the hashes of the extracted input and the summary output) because summarization runs at upload
 * time, before any Case/operation_execution exists (Principle II; see V10 migration note).
 */
@Entity
@Table(name = "attachment_summary")
public class AttachmentSummary {

  @Id private UUID id;

  @Column(name = "workspace_id", nullable = false)
  private UUID workspaceId;

  @Column(name = "attachment_id", nullable = false)
  private UUID attachmentId;

  @Column(name = "extracted_chars", nullable = false)
  private int extractedChars;

  @Column(name = "extracted_text_hash", nullable = false)
  private String extractedTextHash;

  @Column(name = "summary_text", nullable = false)
  private String summaryText;

  @Column(name = "summary_hash", nullable = false)
  private String summaryHash;

  @Column(name = "model_id", nullable = false)
  private String modelId;

  @Column(name = "model_version", nullable = false)
  private String modelVersion;

  @Column(name = "tokens_used", nullable = false)
  private long tokensUsed;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt = OffsetDateTime.now();

  protected AttachmentSummary() {}

  public AttachmentSummary(
      UUID id,
      UUID workspaceId,
      UUID attachmentId,
      int extractedChars,
      String extractedTextHash,
      String summaryText,
      String summaryHash,
      String modelId,
      String modelVersion,
      long tokensUsed) {
    this.id = id;
    this.workspaceId = workspaceId;
    this.attachmentId = attachmentId;
    this.extractedChars = extractedChars;
    this.extractedTextHash = extractedTextHash;
    this.summaryText = summaryText;
    this.summaryHash = summaryHash;
    this.modelId = modelId;
    this.modelVersion = modelVersion;
    this.tokensUsed = tokensUsed;
  }

  public UUID getId() {
    return id;
  }

  public UUID getWorkspaceId() {
    return workspaceId;
  }

  public UUID getAttachmentId() {
    return attachmentId;
  }

  public int getExtractedChars() {
    return extractedChars;
  }

  public String getExtractedTextHash() {
    return extractedTextHash;
  }

  public String getSummaryText() {
    return summaryText;
  }

  public String getSummaryHash() {
    return summaryHash;
  }

  public String getModelId() {
    return modelId;
  }

  public String getModelVersion() {
    return modelVersion;
  }

  public long getTokensUsed() {
    return tokensUsed;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }
}
