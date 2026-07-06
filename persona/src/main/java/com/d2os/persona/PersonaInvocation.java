package com.d2os.persona;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One persona step of a Case's pipeline (data-model.md §4). */
@Entity
@Table(name = "persona_invocation")
public class PersonaInvocation {

    public enum Status { pending, running, validated, escalated }

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "case_instance_id", nullable = false)
    private UUID caseInstanceId;

    @Column(name = "persona_definition_id", nullable = false)
    private UUID personaDefinitionId;

    @Column(name = "persona_definition_version", nullable = false)
    private String personaDefinitionVersion;

    @Column(name = "sequence_no", nullable = false)
    private int sequenceNo;

    @Column(nullable = false)
    private String status = Status.pending.name();

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    protected PersonaInvocation() {}

    public PersonaInvocation(UUID id, UUID workspaceId, UUID caseInstanceId, UUID personaDefinitionId,
                             String personaDefinitionVersion, int sequenceNo) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.caseInstanceId = caseInstanceId;
        this.personaDefinitionId = personaDefinitionId;
        this.personaDefinitionVersion = personaDefinitionVersion;
        this.sequenceNo = sequenceNo;
    }

    public void markStatus(Status s) { this.status = s.name(); }

    public UUID getId() { return id; }
    public UUID getWorkspaceId() { return workspaceId; }
    public UUID getCaseInstanceId() { return caseInstanceId; }
    public String getStatus() { return status; }
    public int getSequenceNo() { return sequenceNo; }
}
