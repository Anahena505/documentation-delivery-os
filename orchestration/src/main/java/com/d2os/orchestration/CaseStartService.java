package com.d2os.orchestration;

import com.d2os.casecore.CaseDefinitionSnapshot;
import com.d2os.casecore.CaseDefinitionSnapshotRepository;
import com.d2os.casecore.CaseInstance;
import com.d2os.casecore.CaseService;
import com.d2os.casecore.WorkflowInstance;
import com.d2os.casecore.WorkflowInstanceRepository;
import com.d2os.catalog.DefinitionLookupService;
import com.d2os.catalog.DefinitionView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.ProcessInstance;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Starts a planned Case's pipeline (T028): transitions Planned → Running, launches the embedded
 * Flowable process, and records the engine↔Case correlation in {@code workflow_instance}. All in
 * one transaction, so a failed engine start rolls back the state change.
 *
 * <p>Phase 4 (T014/T019): which BPMN process to start is no longer a single hardcoded key — with
 * Assessment (and, later, Enhancement) shipping their own {@code workflow.<key>} bindings, the
 * process definition key MUST come from the Case's own pinned {@link CaseDefinitionSnapshot}
 * (AD-4), same as every other definition a running Case touches. {@link
 * #resolveProcessDefinitionKey} reads the snapshot's {@code workflow} entry and looks up its frozen
 * body for {@code processDefinitionKey}. Initiation is unaffected: its pinned workflow entry
 * resolves to {@code initiation-v2}, the same value this class used to hardcode.
 */
@Service
public class CaseStartService {

  // Legacy fallback only — every case type's dependsOn (Initiation included) has pinned a
  // `workflow`
  // entry since Phase 1/2, so this should never actually be reached; kept so a snapshot that
  // somehow
  // predates the workflow entry (there is none in this codebase) fails soft rather than NPEs.
  private static final String LEGACY_DEFAULT_PROCESS_KEY = "initiation-v2";

  private final CaseService caseService;
  private final CaseDefinitionSnapshotRepository snapshotRepository;
  private final DefinitionLookupService definitionLookupService;
  private final ObjectMapper objectMapper;
  private final RuntimeService runtimeService;
  private final WorkflowInstanceRepository workflowInstanceRepository;

  public CaseStartService(
      CaseService caseService,
      CaseDefinitionSnapshotRepository snapshotRepository,
      DefinitionLookupService definitionLookupService,
      ObjectMapper objectMapper,
      RuntimeService runtimeService,
      WorkflowInstanceRepository workflowInstanceRepository) {
    this.caseService = caseService;
    this.snapshotRepository = snapshotRepository;
    this.definitionLookupService = definitionLookupService;
    this.objectMapper = objectMapper;
    this.runtimeService = runtimeService;
    this.workflowInstanceRepository = workflowInstanceRepository;
  }

  @Transactional
  public void start(UUID caseId) {
    CaseInstance kase = caseService.startRunning(caseId); // Planned -> Running (guarded)
    String processKey = resolveProcessDefinitionKey(caseId);

    // workspaceId rides along as a process variable: async job-executor threads run outside
    // any HTTP request (no WorkspaceContextFilter), so they have no other way to learn which
    // workspace's RLS context to bind before touching the database (T045 hardening).
    ProcessInstance pi =
        runtimeService.startProcessInstanceByKey(
            processKey,
            caseId.toString(), // businessKey = case id (correlation)
            Map.of("caseId", caseId.toString(), "workspaceId", kase.getWorkspaceId().toString()));

    workflowInstanceRepository.save(
        new WorkflowInstance(UUID.randomUUID(), kase.getWorkspaceId(), caseId, pi.getId()));
  }

  /**
   * The pinned {@code workflow} entry's {@code processDefinitionKey}, read from its frozen body.
   */
  private String resolveProcessDefinitionKey(UUID caseId) {
    CaseDefinitionSnapshot snapshot =
        snapshotRepository
            .findByCaseInstanceId(caseId)
            .orElseThrow(
                () -> new IllegalStateException("case " + caseId + " has no pinned snapshot"));
    try {
      JsonNode entries = objectMapper.readTree(snapshot.getEntries());
      for (JsonNode entry : entries) {
        if (!"workflow".equals(entry.path("type").asText())) continue;
        String key = entry.path("key").asText();
        String version = entry.path("version").asText();
        DefinitionView workflowDef =
            definitionLookupService
                .byTypeKeyVersion("workflow", key, version)
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "case "
                                + caseId
                                + " pins workflow "
                                + key
                                + "@"
                                + version
                                + " but it is not resolvable"));
        String processDefinitionKey =
            objectMapper.readTree(workflowDef.body()).path("processDefinitionKey").asText();
        if (!processDefinitionKey.isBlank()) {
          return processDefinitionKey;
        }
      }
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      // Malformed snapshot JSON (should never happen — CaseService writes it) falls through to
      // the legacy default below rather than failing case start outright.
    }
    return LEGACY_DEFAULT_PROCESS_KEY;
  }
}
