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

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    protected ArtifactRevision() {}

    public ArtifactRevision(UUID id, UUID workspaceId, UUID artifactId, int revisionNo,
                            String storageRef, String contentHash, UUID producedByOperationExecutionId) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.artifactId = artifactId;
        this.revisionNo = revisionNo;
        this.storageRef = storageRef;
        this.contentHash = contentHash;
        this.producedByOperationExecutionId = producedByOperationExecutionId;
    }

    public UUID getId() { return id; }
    public UUID getArtifactId() { return artifactId; }
    public String getStorageRef() { return storageRef; }
    public String getContentHash() { return contentHash; }
}
