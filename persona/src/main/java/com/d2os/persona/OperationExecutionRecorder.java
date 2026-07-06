package com.d2os.persona;

import com.d2os.artifacts.storage.ObjectStoreClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * Persists the OperationExecution snapshot for one generation attempt (T034, Principle II). The raw
 * output is written to object storage (content-addressable by its own hash) and the row records
 * prompt version, model id/version, inputs, injected knowledge, the output reference/hash, and the
 * validation result — everything the replay harness (T041) needs to reconstruct and verify later.
 */
@Component
public class OperationExecutionRecorder {

    private final OperationExecutionRepository repository;
    private final ObjectStoreClient objectStoreClient;
    private final ObjectMapper objectMapper;

    public OperationExecutionRecorder(OperationExecutionRepository repository,
                                      ObjectStoreClient objectStoreClient,
                                      ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectStoreClient = objectStoreClient;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public OperationExecution record(UUID workspaceId, UUID personaInvocationId, PersonaEnvelope envelope,
                                     String renderedPrompt, String modelId, String modelVersion,
                                     String outputText, long tokensUsed, int attemptNo,
                                     ValidationResult validationResult) {
        UUID operationId = UUID.randomUUID();
        byte[] outputBytes = outputText.getBytes(StandardCharsets.UTF_8);
        String outputHash = HashUtil.sha256Hex(outputBytes);
        String outputRef = "operations/%s.txt".formatted(operationId);

        objectStoreClient.put(outputRef, outputBytes, "text/plain; charset=utf-8");

        String inputsJson = toJson(Map.of(
                "renderedPrompt", renderedPrompt,
                "submissionFormData", envelope.submissionFormDataJson()));

        OperationExecution execution = new OperationExecution(
                operationId, workspaceId, personaInvocationId,
                envelope.promptDefinitionId(), envelope.promptDefinitionVersion(),
                modelId, modelVersion, inputsJson, "[]",
                outputRef, outputHash, attemptNo,
                toJson(Map.of(
                        "weightedScore", validationResult.weightedScore(),
                        "criticalFailures", validationResult.criticalFailures(),
                        "passed", validationResult.passed())),
                tokensUsed);

        return repository.save(execution);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unserializable operation execution field", e);
        }
    }
}
