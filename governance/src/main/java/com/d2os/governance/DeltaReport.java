package com.d2os.governance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A deterministic unified diff between two revisions of one Artifact (V20 {@code delta_report},
 * research R2, T020). Produced by {@link DeltaReportService} whenever comment-and-regenerate
 * (REQUEST_CHANGES → {@code RegenerationDelegate}) yields a new {@code ArtifactRevision} — never an
 * AI-generated "what changed" summary, so the report itself is replayable and tamper-checkable
 * ({@code diffHash} = SHA-256 over {@code diffContent}).
 */
@Entity
@Table(name = "delta_report")
public class DeltaReport {

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "artifact_id", nullable = false)
    private UUID artifactId;

    @Column(name = "from_revision_id", nullable = false)
    private UUID fromRevisionId;

    @Column(name = "to_revision_id", nullable = false)
    private UUID toRevisionId;

    @Column(name = "diff_content", nullable = false)
    private String diffContent;

    @Column(name = "diff_hash", nullable = false)
    private String diffHash;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    protected DeltaReport() {}

    public DeltaReport(UUID id, UUID workspaceId, UUID artifactId, UUID fromRevisionId, UUID toRevisionId,
                       String diffContent, String diffHash) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.artifactId = artifactId;
        this.fromRevisionId = fromRevisionId;
        this.toRevisionId = toRevisionId;
        this.diffContent = diffContent;
        this.diffHash = diffHash;
    }

    public UUID getId() { return id; }
    public UUID getWorkspaceId() { return workspaceId; }
    public UUID getArtifactId() { return artifactId; }
    public UUID getFromRevisionId() { return fromRevisionId; }
    public UUID getToRevisionId() { return toRevisionId; }
    public String getDiffContent() { return diffContent; }
    public String getDiffHash() { return diffHash; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
