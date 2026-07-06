package com.d2os.artifacts;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Full-provenance handover record (T037, FR-008, clarification Q4). All six fields are mandatory —
 * enforced both at the DB (NOT NULL, V6) and in the constructor here.
 */
@Entity
@Table(name = "handover_record")
public class HandoverRecord {

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "execution_package_id", nullable = false)
    private UUID executionPackageId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "contents_index", nullable = false)
    private String contentsIndex;

    @Column(name = "submission_ref", nullable = false)
    private UUID submissionRef;

    @Column(name = "definition_snapshot_ref", nullable = false)
    private UUID definitionSnapshotRef;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "artifact_hashes", nullable = false)
    private String artifactHashes;

    @Column(name = "decision_log_ref", nullable = false)
    private String decisionLogRef;

    @Column(name = "owner_name", nullable = false)
    private String ownerName;

    @Column(name = "next_action", nullable = false)
    private String nextAction;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    protected HandoverRecord() {}

    public HandoverRecord(UUID id, UUID workspaceId, UUID executionPackageId, String contentsIndex,
                          UUID submissionRef, UUID definitionSnapshotRef, String artifactHashes,
                          String decisionLogRef, String ownerName, String nextAction) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.executionPackageId = executionPackageId;
        this.contentsIndex = require(contentsIndex, "contentsIndex");
        this.submissionRef = requireNonNull(submissionRef, "submissionRef");
        this.definitionSnapshotRef = requireNonNull(definitionSnapshotRef, "definitionSnapshotRef");
        this.artifactHashes = require(artifactHashes, "artifactHashes");
        this.decisionLogRef = require(decisionLogRef, "decisionLogRef");
        this.ownerName = require(ownerName, "ownerName");
        this.nextAction = require(nextAction, "nextAction");
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("HandoverRecord." + field + " is mandatory (FR-008)");
        }
        return value;
    }

    private static UUID requireNonNull(UUID value, String field) {
        if (value == null) {
            throw new IllegalArgumentException("HandoverRecord." + field + " is mandatory (FR-008)");
        }
        return value;
    }

    public UUID getId() { return id; }
    public String getContentsIndex() { return contentsIndex; }
    public UUID getSubmissionRef() { return submissionRef; }
    public UUID getDefinitionSnapshotRef() { return definitionSnapshotRef; }
    public String getArtifactHashes() { return artifactHashes; }
    public String getDecisionLogRef() { return decisionLogRef; }
    public String getOwnerName() { return ownerName; }
    public String getNextAction() { return nextAction; }
}
