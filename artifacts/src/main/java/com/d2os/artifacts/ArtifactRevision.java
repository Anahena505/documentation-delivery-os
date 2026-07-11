package com.d2os.artifacts;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A content-addressed revision of an Artifact (E1.7, R8). Corrections are new revisions, never in-place edits. */
@Entity
@Table(name = "artifact_revision")
public class ArtifactRevision {

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "artifact_id", nullable = false)
    private UUID artifactId;

    @Column(name = "revision_no", nullable = false)
    private int revisionNo;

    @Column(name = "storage_ref", nullable = false)
    private String storageRef;

    @Column(name = "content_hash", nullable = false)
    private String contentHash;

    @Column(name = "produced_by_operation_execution_id")
    private UUID producedByOperationExecutionId;

    // US6 (V32, FR-014): provenance to the immutable TemplateDefinition version this revision's
    // content was deterministically rendered from. NULL for placeholder/legacy revisions and for any
    // revision whose owning case pinned no renderable template (additive, behavior-preserving).
    @Column(name = "source_template_id")
    private UUID sourceTemplateId;

    @Column(name = "template_version")
    private String templateVersion;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    protected ArtifactRevision() {}

    public ArtifactRevision(UUID id, UUID workspaceId, UUID artifactId, int revisionNo,
                            String storageRef, String contentHash, UUID producedByOperationExecutionId) {
        this(id, workspaceId, artifactId, revisionNo, storageRef, contentHash,
                producedByOperationExecutionId, null, null);
    }

    /** US6 (T056): provenance-carrying constructor — {@code sourceTemplateId}/{@code templateVersion}
     * are NULL when this revision was not rendered from a pinned TemplateDefinition. */
    public ArtifactRevision(UUID id, UUID workspaceId, UUID artifactId, int revisionNo,
                            String storageRef, String contentHash, UUID producedByOperationExecutionId,
                            UUID sourceTemplateId, String templateVersion) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.artifactId = artifactId;
        this.revisionNo = revisionNo;
        this.storageRef = storageRef;
        this.contentHash = contentHash;
        this.producedByOperationExecutionId = producedByOperationExecutionId;
        this.sourceTemplateId = sourceTemplateId;
        this.templateVersion = templateVersion;
    }

    public UUID getId() { return id; }
    public UUID getWorkspaceId() { return workspaceId; }
    public UUID getArtifactId() { return artifactId; }
    public int getRevisionNo() { return revisionNo; }
    public String getStorageRef() { return storageRef; }
    public String getContentHash() { return contentHash; }
    public UUID getProducedByOperationExecutionId() { return producedByOperationExecutionId; }
    public UUID getSourceTemplateId() { return sourceTemplateId; }
    public String getTemplateVersion() { return templateVersion; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
