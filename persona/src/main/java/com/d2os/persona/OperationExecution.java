package com.d2os.persona;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The crown-jewel audit row (Principle II, T034): one per generation attempt, carrying the full
 * reproducibility snapshot (prompt version, model id/version, inputs, injected knowledge) plus the
 * recorded output and its hash — the replay target (R5). INSERT-only; never updated after creation.
 */
@Entity
@Table(name = "operation_execution")
public class OperationExecution {

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "persona_invocation_id", nullable = false)
    private UUID personaInvocationId;

    @Column(name = "prompt_definition_id", nullable = false)
    private UUID promptDefinitionId;

    @Column(name = "prompt_definition_version", nullable = false)
    private String promptDefinitionVersion;

    @Column(name = "model_id", nullable = false)
    private String modelId;

    @Column(name = "model_version", nullable = false)
    private String modelVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String inputs;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "injected_knowledge", nullable = false)
    private String injectedKnowledge = "[]";

    @Column(name = "output_ref")
    private String outputRef;

    @Column(name = "output_hash")
    private String outputHash;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "validation_result")
    private String validationResult;

    @Column(name = "tokens_used", nullable = false)
    private long tokensUsed;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    protected OperationExecution() {}

    public OperationExecution(UUID id, UUID workspaceId, UUID personaInvocationId,
                              UUID promptDefinitionId, String promptDefinitionVersion,
                              String modelId, String modelVersion, String inputs,
                              String injectedKnowledge, String outputRef, String outputHash,
                              int attemptNo, String validationResult, long tokensUsed) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.personaInvocationId = personaInvocationId;
        this.promptDefinitionId = promptDefinitionId;
        this.promptDefinitionVersion = promptDefinitionVersion;
        this.modelId = modelId;
        this.modelVersion = modelVersion;
        this.inputs = inputs;
        this.injectedKnowledge = injectedKnowledge == null ? "[]" : injectedKnowledge;
        this.outputRef = outputRef;
        this.outputHash = outputHash;
        this.attemptNo = attemptNo;
        this.validationResult = validationResult;
        this.tokensUsed = tokensUsed;
    }

    public UUID getId() { return id; }
    public UUID getPersonaInvocationId() { return personaInvocationId; }
    public String getOutputRef() { return outputRef; }
    public String getOutputHash() { return outputHash; }
    public int getAttemptNo() { return attemptNo; }
    public String getModelId() { return modelId; }
    public String getModelVersion() { return modelVersion; }
    public String getInputs() { return inputs; }
    public String getInjectedKnowledge() { return injectedKnowledge; }
    public String getPromptDefinitionVersion() { return promptDefinitionVersion; }
    public long getTokensUsed() { return tokensUsed; }
}
