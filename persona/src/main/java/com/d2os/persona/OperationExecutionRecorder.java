package com.d2os.persona;

import com.d2os.artifacts.storage.ObjectStoreClient;
import com.d2os.persona.spi.KnowledgeProvider;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
  private final KnowledgeInjectionSnapshotRepository injectionSnapshotRepository;

  public OperationExecutionRecorder(
      OperationExecutionRepository repository,
      ObjectStoreClient objectStoreClient,
      ObjectMapper objectMapper,
      KnowledgeInjectionSnapshotRepository injectionSnapshotRepository) {
    this.repository = repository;
    this.objectStoreClient = objectStoreClient;
    this.objectMapper = objectMapper;
    this.injectionSnapshotRepository = injectionSnapshotRepository;
  }

  @Transactional
  public OperationExecution record(
      UUID workspaceId,
      UUID personaInvocationId,
      PersonaEnvelope envelope,
      String renderedPrompt,
      String modelId,
      String modelVersion,
      String outputText,
      long tokensUsed,
      int attemptNo,
      ValidationResult validationResult) {
    return record(
        workspaceId,
        personaInvocationId,
        envelope,
        renderedPrompt,
        modelId,
        modelVersion,
        outputText,
        tokensUsed,
        attemptNo,
        validationResult,
        false);
  }

  /**
   * Overload that flags the row as an influence-evaluation run (US4, T032): the paired with/without
   * runs pass {@code evaluation=true} so they are excluded from delivery/normal listings. Snapshot
   * provenance is written identically — an evaluation run is still fully reproducible.
   */
  @Transactional
  public OperationExecution record(
      UUID workspaceId,
      UUID personaInvocationId,
      PersonaEnvelope envelope,
      String renderedPrompt,
      String modelId,
      String modelVersion,
      String outputText,
      long tokensUsed,
      int attemptNo,
      ValidationResult validationResult,
      boolean evaluation) {
    UUID operationId = UUID.randomUUID();
    byte[] outputBytes = outputText.getBytes(StandardCharsets.UTF_8);
    String outputHash = HashUtil.sha256Hex(outputBytes);
    String outputRef = "operations/%s.txt".formatted(operationId);

    objectStoreClient.put(outputRef, outputBytes, "text/plain; charset=utf-8");

    String inputsJson =
        toJson(
            Map.of(
                "renderedPrompt",
                renderedPrompt,
                "submissionFormData",
                envelope.submissionFormDataJson()));

    List<KnowledgeProvider.InjectedItem> injected =
        envelope.injectedKnowledge() == null ? List.of() : envelope.injectedKnowledge();
    String injectedKnowledgeJson = toJson(injectedKnowledgeManifest(injected));

    OperationExecution execution =
        new OperationExecution(
            operationId,
            workspaceId,
            personaInvocationId,
            envelope.promptDefinitionId(),
            envelope.promptDefinitionVersion(),
            modelId,
            modelVersion,
            inputsJson,
            injectedKnowledgeJson,
            outputRef,
            outputHash,
            attemptNo,
            toJson(
                Map.of(
                    "weightedScore", validationResult.weightedScore(),
                    "criticalFailures", validationResult.criticalFailures(),
                    "passed", validationResult.passed())),
            tokensUsed);

    if (evaluation) {
      execution.markEvaluation();
    }

    OperationExecution saved = repository.save(execution);

    // Same-transaction injection provenance (T014, FR-006): one snapshot row per injected item, in
    // envelope order (position from 0). No rows when nothing was injected → identical to
    // pre-Phase-3.
    for (int position = 0; position < injected.size(); position++) {
      KnowledgeProvider.InjectedItem item = injected.get(position);
      injectionSnapshotRepository.save(
          new KnowledgeInjectionSnapshot(
              UUID.randomUUID(),
              workspaceId,
              operationId,
              item.itemId(),
              item.key(),
              item.version(),
              item.contentHash(),
              position));
    }

    return saved;
  }

  /**
   * JSON manifest of injected items for the {@code operation_execution.injected_knowledge} column.
   */
  private List<Map<String, Object>> injectedKnowledgeManifest(
      List<KnowledgeProvider.InjectedItem> injected) {
    List<Map<String, Object>> manifest = new ArrayList<>(injected.size());
    for (int position = 0; position < injected.size(); position++) {
      KnowledgeProvider.InjectedItem item = injected.get(position);
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("itemId", item.itemId().toString());
      entry.put("key", item.key());
      entry.put("version", item.version());
      entry.put("contentHash", item.contentHash());
      entry.put("position", position);
      manifest.add(entry);
    }
    return manifest;
  }

  private String toJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Unserializable operation execution field", e);
    }
  }
}
