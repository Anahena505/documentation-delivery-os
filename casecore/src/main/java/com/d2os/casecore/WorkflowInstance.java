package com.d2os.casecore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Correlates a Flowable process instance to its Case (E1.5). */
@Entity
@Table(name = "workflow_instance")
public class WorkflowInstance {

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "case_instance_id", nullable = false)
    private UUID caseInstanceId;

    @Column(name = "engine_instance_id", nullable = false)
    private String engineInstanceId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    protected WorkflowInstance() {}

    public WorkflowInstance(UUID id, UUID workspaceId, UUID caseInstanceId, String engineInstanceId) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.caseInstanceId = caseInstanceId;
        this.engineInstanceId = engineInstanceId;
    }

    public UUID getId() { return id; }
    public UUID getCaseInstanceId() { return caseInstanceId; }
    public String getEngineInstanceId() { return engineInstanceId; }
}
