package com.d2os.casecore;

import com.d2os.casecore.progress.ActiveCaseRegistry;
import com.d2os.casecore.progress.ProgressEmitter;
import com.d2os.casecore.progress.ProgressEvent;
import com.d2os.casecore.spi.ConditionalArtifactPort;
import com.d2os.casecore.spi.SubmissionLookup;
import com.d2os.catalog.DefinitionRef;
import com.d2os.catalog.DefinitionResolutionService;
import com.d2os.catalog.DefinitionView;
import com.d2os.tenancy.Feature;
import com.d2os.tenancy.FeatureRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opens and drives a Case through its lifecycle (T026, T028, T031, T058). Every transition is
 * written with an {@link AuditWriter} entry in the same transaction (FR-007, Principle V). The
 * one-active-mutating-Case invariant (FR-016) is enforced by the DB partial unique index; this
 * class surfaces its violation as {@link CaseConflictException}.
 */
@Service
public class CaseService {

  private final CaseInstanceRepository caseRepository;
  private final CaseDefinitionSnapshotRepository snapshotRepository;
  private final FeatureRepository featureRepository;
  private final DefinitionResolutionService definitionResolution;
  private final AuditWriter auditWriter;
  private final ObjectMapper objectMapper;
  private final ProgressEmitter progressEmitter;
  private final ActiveCaseRegistry activeCaseRegistry;
  private final MutatingCaseGuard mutatingCaseGuard;
  private final ConditionalArtifactPort conditionalArtifactPort;
  private final long defaultTokenBudget;

  public CaseService(
      CaseInstanceRepository caseRepository,
      CaseDefinitionSnapshotRepository snapshotRepository,
      FeatureRepository featureRepository,
      DefinitionResolutionService definitionResolution,
      AuditWriter auditWriter,
      ObjectMapper objectMapper,
      ProgressEmitter progressEmitter,
      ActiveCaseRegistry activeCaseRegistry,
      MutatingCaseGuard mutatingCaseGuard,
      ConditionalArtifactPort conditionalArtifactPort,
      @Value("${d2os.case.default-token-budget:1000000}") long defaultTokenBudget) {
    this.caseRepository = caseRepository;
    this.snapshotRepository = snapshotRepository;
    this.featureRepository = featureRepository;
    this.definitionResolution = definitionResolution;
    this.auditWriter = auditWriter;
    this.objectMapper = objectMapper;
    this.progressEmitter = progressEmitter;
    this.activeCaseRegistry = activeCaseRegistry;
    this.mutatingCaseGuard = mutatingCaseGuard;
    this.conditionalArtifactPort = conditionalArtifactPort;
    this.defaultTokenBudget = defaultTokenBudget;
  }

  @Transactional
  public CaseInstance openCase(SubmissionLookup.SubmissionInfo submission, UUID featureId) {
    // Phase 4 (T012, US1, contracts `/cases` 412): a submission's confirmed flag now reflects the
    // case-type classification's classification_status = CONFIRMED (IntakeSubmissionLookup), which
    // subsumes the Phase 1-3 confirm-classification flow (ProblemSubmission.confirm keeps both in
    // sync). Case creation is blocked with 412, not the generic 422, until that gate is satisfied.
    if (!submission.confirmed()) {
      throw new ClassificationNotConfirmedException(
          "submission " + submission.id() + " classification is not yet confirmed");
    }

    Feature feature =
        featureRepository
            .findById(featureId)
            .orElseThrow(() -> new CaseCreationException("feature " + featureId + " not found"));
    if (!feature.getWorkspaceId().equals(submission.workspaceId())) {
      throw new CaseCreationException("feature and submission are in different workspaces");
    }

    // Resolve the case-type definition; a Case cannot be planned without a published definition.
    DefinitionView caseType =
        definitionResolution
            .latestPublishedView("case_type", submission.caseTypeKey())
            .orElseThrow(
                () ->
                    new CaseCreationException(
                        "no published case_type definition for key " + submission.caseTypeKey()));

    CaseInstance kase =
        new CaseInstance(
            UUID.randomUUID(),
            submission.workspaceId(),
            featureId,
            submission.id(),
            caseType.key(),
            caseType.version(),
            defaultTokenBudget,
            "api");

    // Submitted -> Classified -> Planned; pin the snapshot exactly at Planned (FR-003).
    kase.transitionTo(CaseStatus.Classified);
    kase.transitionTo(CaseStatus.Planned);

    try {
      caseRepository.saveAndFlush(kase); // flush so the FR-016 unique index fires here
    } catch (DataIntegrityViolationException e) {
      throw new CaseConflictException(
          "an active mutating Case already exists on feature " + featureId);
    }

    CaseDefinitionSnapshot snapshot = pinSnapshot(kase, caseType, submission);

    // Phase 4 Q2 guard (T027, research R3, FR-012/013): only case types pinned mutating=true hold
    // the Feature's single-active-mutating-case slot (Assessment, mutating=false, is exempt —
    // T018).
    // A conflict here throws before any audit entry is written and the whole @Transactional method
    // rolls back, so a rejected create leaves no orphaned Case/snapshot row.
    if (requiresMutatingSlot(snapshot)) {
      mutatingCaseGuard.acquire(featureId, feature.getAggregateVersion(), kase.getId());
    }

    auditWriter.record(
        kase.getWorkspaceId(),
        "case_instance",
        kase.getId(),
        "Planned",
        "api",
        Map.of("caseTypeKey", caseType.key(), "caseTypeVersion", caseType.version()));
    return kase;
  }

  /** Transition Planned -> Running when the engine starts the pipeline (T028). */
  @Transactional
  public CaseInstance startRunning(UUID caseId) {
    return transition(caseId, CaseStatus.Running, "api", Map.of());
  }

  /**
   * Transition Running -> Suspended on token-budget breach (T031, FR-012, NFR-7). Runs in its own
   * transaction ({@code REQUIRES_NEW}) so the suspension COMMITS even though the caller then throws
   * to abort the current AI operation — otherwise Flowable's job rollback would undo it.
   * Idempotent: a Case already Suspended is left as-is (retried jobs re-enter this path
   * harmlessly).
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public CaseInstance suspend(UUID caseId, String reason) {
    CaseInstance kase =
        caseRepository
            .findById(caseId)
            .orElseThrow(() -> new NoSuchElementException("case " + caseId));
    if (kase.status() == CaseStatus.Suspended) {
      return kase;
    }
    kase.transitionTo(CaseStatus.Suspended);
    caseRepository.save(kase);
    auditWriter.record(
        kase.getWorkspaceId(),
        "case_instance",
        caseId,
        "Suspended",
        "system",
        Map.of("reason", reason));
    progressEmitter.emit(kase.getWorkspaceId(), caseId, ProgressEvent.Kind.SUSPENDED);
    activeCaseRegistry.clear(caseId); // parked — not executing, no heartbeat
    return kase;
  }

  /** Resume a Suspended/Escalated Case back to Running (explicit governance action, AD-4). */
  @Transactional
  public CaseInstance resume(UUID caseId, String actor) {
    return transition(caseId, CaseStatus.Running, actor, Map.of());
  }

  /** Transition Running -> Escalated after the bounded revise loop is exhausted (T058, FR-005). */
  @Transactional
  public CaseInstance escalate(UUID caseId, String reason) {
    return transition(caseId, CaseStatus.Escalated, "system", Map.of("reason", reason));
  }

  /** Transition Running -> Delivered once the package is assembled (T036/T037). */
  @Transactional
  public CaseInstance deliver(UUID caseId) {
    return transition(caseId, CaseStatus.Delivered, "system", Map.of());
  }

  /** Cancel a Case (from an escalation resolution or explicit governance action, T059). */
  @Transactional
  public CaseInstance cancel(UUID caseId, String reason) {
    return transition(caseId, CaseStatus.Cancelled, "system", Map.of("reason", reason));
  }

  private CaseInstance transition(
      UUID caseId, CaseStatus target, String actor, Map<String, Object> details) {
    CaseInstance kase =
        caseRepository
            .findById(caseId)
            .orElseThrow(() -> new NoSuchElementException("case " + caseId));
    kase.transitionTo(target);
    caseRepository.save(kase);
    // T027: release the Q2 mutating-case slot in the SAME transaction as the terminal transition.
    // Idempotent no-op for a Case that never held the slot (Assessment, or a rejected create).
    if (target.isTerminal()) {
      mutatingCaseGuard.release(caseId);
    }
    auditWriter.record(
        kase.getWorkspaceId(), "case_instance", caseId, target.name(), actor, details);
    if (target == CaseStatus.Escalated) {
      progressEmitter.emit(kase.getWorkspaceId(), caseId, ProgressEvent.Kind.ESCALATED);
    } else if (target == CaseStatus.Delivered) {
      progressEmitter.emit(kase.getWorkspaceId(), caseId, ProgressEvent.Kind.DELIVERED);
    }
    // A Case emits heartbeats exactly while it is executing (Running); any other state parks it.
    if (target == CaseStatus.Running) {
      activeCaseRegistry.markRunning(caseId, kase.getWorkspaceId());
    } else {
      activeCaseRegistry.clear(caseId);
    }
    return kase;
  }

  /**
   * Pin the case-type ref plus every definition its body's {@code dependsOn} list names (each entry
   * is {@code "type:key"}). This is the AD-4 freeze — every definition the workflow will touch must
   * be resolved and captured here, not just ones sharing the case type's own key.
   *
   * <p>Phase 4 (research R2/R3, Principle I): the case-type entry additionally carries the
   * capability flags read from the {@code CaseTypeDefinition} body — {@code mutating} (whether the
   * case may write mutating artifacts; drives the Q2 guard exemption, T018) and {@code
   * artifactKindAllowlist} (the read-only write-path allowlist, T017) — frozen into the snapshot at
   * the same moment as everything else, so later code reads capability from the pinned snapshot
   * rather than re-querying the live catalog. Existing case-type seeds (e.g. Initiation) predate
   * these fields; {@link #extractMutating} / {@link #extractArtifactKindAllowlist} default to
   * {@code mutating=true} / an empty (unrestricted) allowlist when absent, so nothing breaks.
   *
   * <p>Phase 4 US5 (T032, research R6, FR-014/019): additionally folds in the frozen
   * expected-artifact set — one {@code required_artifact} entry per template the case type depends
   * on (source {@code BASE}), plus every row the {@code conditionalArtifacts} DMN returns for this
   * submission's form data (source {@code CONDITIONAL}, evaluated here, before pinning, so a later
   * change to the DMN never perturbs an already-open Case). {@link #requiredArtifacts} reads this
   * back out; {@code PackageAssemblyService} (T033) enforces it at delivery time.
   */
  private CaseDefinitionSnapshot pinSnapshot(
      CaseInstance kase, DefinitionView caseType, SubmissionLookup.SubmissionInfo submission) {
    List<Map<String, Object>> entries = new ArrayList<>();
    entries.add(caseTypeEntry(caseType));

    for (String dep : parseDependsOn(caseType.body())) {
      String[] parts = dep.split(":", 2);
      if (parts.length != 2) continue;
      DefinitionView depView =
          definitionResolution
              .latestPublishedView(parts[0], parts[1])
              .orElseThrow(
                  () ->
                      new CaseCreationException(
                          "case type " + caseType.key() + " depends on unpublished " + dep));
      entries.add(refEntry(depView.toRef()));
      if ("template".equals(parts[0])) {
        entries.add(
            requiredArtifactEntry(
                depView.key(), extractKind(depView.body(), depView.key()), "BASE", null));
      }
    }

    for (ConditionalArtifactPort.ConditionalArtifact ca :
        conditionalArtifactPort.evaluate(submission.formData())) {
      entries.add(
          requiredArtifactEntry(ca.templateKey(), ca.artifactKind(), "CONDITIONAL", ca.reason()));
    }

    CaseDefinitionSnapshot snapshot =
        new CaseDefinitionSnapshot(
            UUID.randomUUID(), kase.getWorkspaceId(), kase.getId(), toJson(entries));
    snapshotRepository.save(snapshot);
    return snapshot;
  }

  private Map<String, Object> requiredArtifactEntry(
      String templateKey, String artifactKind, String source, String conditionalReason) {
    Map<String, Object> entry = new java.util.LinkedHashMap<>();
    entry.put("type", "required_artifact");
    entry.put("templateKey", templateKey);
    entry.put("artifactKind", artifactKind);
    entry.put("source", source);
    entry.put("conditionalReason", conditionalReason);
    return entry;
  }

  /** A TemplateDefinition body's {@code kind} field (data-model.md); falls back to its own key. */
  private String extractKind(String templateBody, String templateKey) {
    try {
      String kind = objectMapper.readTree(templateBody).path("kind").asText(null);
      return kind != null && !kind.isBlank() ? kind : templateKey;
    } catch (Exception e) {
      return templateKey;
    }
  }

  private List<String> parseDependsOn(String caseTypeBody) {
    try {
      JsonNode node = objectMapper.readTree(caseTypeBody).path("dependsOn");
      List<String> deps = new ArrayList<>();
      node.forEach(n -> deps.add(n.asText()));
      return deps;
    } catch (Exception e) {
      return List.of();
    }
  }

  private Map<String, Object> refEntry(DefinitionRef ref) {
    return Map.of("type", ref.type(), "key", ref.key(), "version", ref.version());
  }

  /** The case_type snapshot entry, extended with the Phase 4 capability flags (research R2/R3). */
  private Map<String, Object> caseTypeEntry(DefinitionView caseType) {
    Map<String, Object> entry = new java.util.LinkedHashMap<>(refEntry(caseType.toRef()));
    entry.put("mutating", extractMutating(caseType.body()));
    entry.put("artifactKindAllowlist", extractArtifactKindAllowlist(caseType.body()));
    return entry;
  }

  /** {@code mutating} capability flag (research R2/R3); absent ⇒ {@code true} (existing seeds). */
  private boolean extractMutating(String caseTypeBody) {
    try {
      return objectMapper.readTree(caseTypeBody).path("mutating").asBoolean(true);
    } catch (Exception e) {
      return true;
    }
  }

  /** Read-only write-path artifact-kind allowlist (research R2); absent ⇒ empty/unrestricted. */
  private List<String> extractArtifactKindAllowlist(String caseTypeBody) {
    try {
      JsonNode node = objectMapper.readTree(caseTypeBody).path("artifactKindAllowlist");
      List<String> allowlist = new ArrayList<>();
      node.forEach(n -> allowlist.add(n.asText()));
      return allowlist;
    } catch (Exception e) {
      return List.of();
    }
  }

  /**
   * Phase 4 (T018, research R2/R3): true if a Case pinned to {@code snapshot} must hold the Q2
   * single-active-mutating-case guard slot on its Feature — i.e. the pinned case-type entry's
   * {@code mutating} flag. Assessment ({@code mutating=false}) is exempt and this returns {@code
   * false} for it. Called from {@link #openCase} (T027) to gate {@link MutatingCaseGuard#acquire}.
   */
  public boolean requiresMutatingSlot(CaseDefinitionSnapshot snapshot) {
    return CaseTypeCapability.from(objectMapper, snapshot).mutating();
  }

  /**
   * Phase 4 US5 (T034, FR-014/015): every {@code required_artifact} entry the pinned snapshot froze
   * in at {@link #pinSnapshot} — the case type's BASE templates plus any CONDITIONAL rows the
   * conditional-artifacts DMN added. Read back by {@code GET /cases/{id}/required-artifacts} and by
   * {@code PackageAssemblyService}'s completeness gate (T033).
   */
  public List<Map<String, Object>> requiredArtifacts(CaseDefinitionSnapshot snapshot) {
    if (snapshot == null) {
      return List.of();
    }
    try {
      JsonNode entries = objectMapper.readTree(snapshot.getEntries());
      List<Map<String, Object>> result = new ArrayList<>();
      for (JsonNode entry : entries) {
        if (!"required_artifact".equals(entry.path("type").asText())) continue;
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("templateKey", entry.path("templateKey").asText(null));
        m.put("artifactKind", entry.path("artifactKind").asText(null));
        m.put("source", entry.path("source").asText(null));
        JsonNode reason = entry.path("conditionalReason");
        m.put(
            "conditionalReason",
            reason.isMissingNode() || reason.isNull() ? null : reason.asText());
        result.add(m);
      }
      return result;
    } catch (Exception e) {
      return List.of();
    }
  }

  private String toJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Unserializable snapshot", e);
    }
  }
}
