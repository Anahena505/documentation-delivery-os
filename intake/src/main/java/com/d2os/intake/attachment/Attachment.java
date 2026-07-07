package com.d2os.intake.attachment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * An uploaded file bound to a {@code problem_submission} (US5, FR-015, T040). The raw bytes live in
 * the object store under {@link #objectKey}; this row tracks lifecycle + provenance only. A persona
 * NEVER consumes these bytes — only the sanitized {@link AttachmentSummary#getSummaryText()} produced
 * by the sandboxed extract → summarize pipeline. {@link #filename} is display-only and is never used
 * to derive a storage path (path-traversal defense — the object key is workspace-scoped and random).
 */
@Entity
@Table(name = "attachment")
public class Attachment {

    /** RECEIVED → EXTRACTING → SUMMARIZED, or → REJECTED (disallowed type / oversize / unparseable). */
    public enum Status { RECEIVED, EXTRACTING, SUMMARIZED, REJECTED }

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "submission_id", nullable = false)
    private UUID submissionId;

    @Column(nullable = false)
    private String filename;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "object_key", nullable = false)
    private String objectKey;

    @Column(name = "content_hash", nullable = false)
    private String contentHash;

    @Column(nullable = false)
    private String status = Status.RECEIVED.name();

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    protected Attachment() {}

    public Attachment(UUID id, UUID workspaceId, UUID submissionId, String filename, String contentType,
                      long sizeBytes, String objectKey, String contentHash) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.submissionId = submissionId;
        this.filename = filename;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.objectKey = objectKey;
        this.contentHash = contentHash;
    }

    public void markExtracting() { this.status = Status.EXTRACTING.name(); }

    public void markSummarized() { this.status = Status.SUMMARIZED.name(); }

    /** Terminal failure: the file was disallowed, oversized, or could not be safely extracted (T1-d). */
    public void reject(String reason) {
        this.status = Status.REJECTED.name();
        this.rejectionReason = reason;
    }

    public UUID getId() { return id; }
    public UUID getWorkspaceId() { return workspaceId; }
    public UUID getSubmissionId() { return submissionId; }
    public String getFilename() { return filename; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getObjectKey() { return objectKey; }
    public String getContentHash() { return contentHash; }
    public String getStatus() { return status; }
    public String getRejectionReason() { return rejectionReason; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
