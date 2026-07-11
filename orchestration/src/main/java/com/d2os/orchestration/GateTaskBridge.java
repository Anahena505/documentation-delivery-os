package com.d2os.orchestration;

import com.d2os.artifacts.ArtifactRevision;
import com.d2os.artifacts.ArtifactService;
import com.d2os.artifacts.spi.PersonaOutputPort;
import com.d2os.governance.GateEventPublisher;
import com.d2os.governance.GateInstance;
import com.d2os.governance.GateInstance.GateType;
import com.d2os.governance.GateInstanceRepository;
import com.d2os.governance.GateService;
import com.d2os.tenancy.WorkspaceContext;
import com.d2os.tenancy.security.WorkspaceRlsBinder;
import java.util.List;
import java.util.UUID;
import org.flowable.engine.delegate.TaskListener;
import org.flowable.task.service.delegate.DelegateTask;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Engine userTask ↔ governance {@code GateInstance} sync point (T010 Phase 2, wired in Phase 3,
 * research R1) — same engine-coupling pattern as {@link PersonaStepDelegate}: this is the ONLY
 * class in {@code orchestration} allowed to reach into {@code governance} JPA/service state
 * (plan.md Structure Decision, enforced later by an ArchUnit rule, Phase 9 T048), keeping {@code
 * governance} itself engine-agnostic (it has no Flowable dependency at all).
 *
 * <p><b>T012/T013 wiring (Phase 3)</b>: bound via {@code <flowable:taskListener event="create"
 * delegateExpression="${gateTaskBridge}"/>} on the {@code review-gate.bpmn20.xml} / {@code
 * approval-gate.bpmn20.xml} gate userTask. Row creation + {@code GATE_OPENED} event emission are
 * delegated to {@link GateService#open}, not done directly here (the Phase 2 placeholder used the
 * repository directly; T014/T016 centralized that in governance so {@code GateEventPublisher} can
 * wire cleanly off one call site) — the complete-task-on-decide half lives in {@code
 * GateService#decide} via {@code EngineGateReleasePort}.
 *
 * <p><b>Expected variable contract</b> (satisfied by the gate callActivity's in-parameter mapping,
 * T012/T013/T015): the subprocess execution must carry {@code workspaceId} (same convention as
 * {@link PersonaStepDelegate}), {@code caseInstanceId}, {@code gateType} ({@code REVIEW}|{@code
 * APPROVAL}), {@code gateDefinitionKey}, {@code gateDefinitionVersion}, {@code inputsRef} (JSON
 * string — the exact information the decision is based on), and optionally {@code
 * subjectArtifactRevisionId}, {@code escalationPolicyKey}, {@code escalationPolicyVersion}.
 *
 * <p><b>T019/T020/T022 additions (Phase 5, US2, research R2)</b>: neither {@code initiation-v3} nor
 * {@code assessment-v2} (T015) actually maps a {@code subjectArtifactRevisionId} variable in — no
 * ArtifactRevision exists yet at gate-open time in either workflow (materialization is otherwise
 * deferred to {@code AssemblePackageDelegate}, post-approval). Without a subject revision there is
 * nothing for comment-and-regenerate to diff against, so this bridge now ALSO resolves it when the
 * variable is absent: it takes the case's most recently validated persona output (the one that just
 * fed into this gate) and materializes it via {@link ArtifactService#createRevision} — idempotent
 * by content hash (T019/T020's {@code ArtifactService} change), so re-entering the SAME gate cycle
 * (or the later approve-path re-materialization at {@code AssemblePackageDelegate}) never creates a
 * duplicate revision of unchanged content.
 *
 * <p>When {@code RegenerationDelegate} (T019) has just re-run the persona under review with
 * reviewer comments injected and produced a genuinely new revision, it leaves a {@code
 * regenerationDeltaReportId} process variable behind (carried across the callActivity boundary by
 * the caller's {@code flowable:in} mapping) — this bridge attaches that report to the newly-opened
 * {@link GateInstance} (T020's "set the resulting delta_report.id onto the new
 * GateInstance.deltaReportId field") and emits {@code GATE_REGENERATION_TRIGGERED} (T022) in the
 * SAME transaction as the gate's own open, since this is the one place a comment-and-regenerate
 * loop's new gate cycle is actually created.
 */
@Component("gateTaskBridge")
public class GateTaskBridge implements TaskListener {

  private final GateService gateService;
  private final GateInstanceRepository gateInstanceRepository;
  private final GateEventPublisher gateEventPublisher;
  private final ArtifactService artifactService;
  private final PersonaOutputPort personaOutputPort;
  private final WorkspaceRlsBinder workspaceRlsBinder;

  public GateTaskBridge(
      GateService gateService,
      GateInstanceRepository gateInstanceRepository,
      GateEventPublisher gateEventPublisher,
      ArtifactService artifactService,
      PersonaOutputPort personaOutputPort,
      WorkspaceRlsBinder workspaceRlsBinder) {
    this.gateService = gateService;
    this.gateInstanceRepository = gateInstanceRepository;
    this.gateEventPublisher = gateEventPublisher;
    this.artifactService = artifactService;
    this.personaOutputPort = personaOutputPort;
    this.workspaceRlsBinder = workspaceRlsBinder;
  }

  @Override
  @Transactional
  public void notify(DelegateTask delegateTask) {
    UUID workspaceId = UUID.fromString((String) delegateTask.getVariable("workspaceId"));
    UUID caseInstanceId = UUID.fromString((String) delegateTask.getVariable("caseInstanceId"));

    WorkspaceContext.set(workspaceId);
    try {
      // Re-bind RLS on this job's transaction connection — same reasoning as PersonaStepDelegate:
      // Flowable checked the connection out before WorkspaceContext could be set.
      workspaceRlsBinder.bindCurrentTransaction(workspaceId);

      GateType gateType = GateType.valueOf((String) delegateTask.getVariable("gateType"));
      String gateDefinitionKey = (String) delegateTask.getVariable("gateDefinitionKey");
      int gateDefinitionVersion =
          ((Number) delegateTask.getVariable("gateDefinitionVersion")).intValue();
      String inputsRef = (String) delegateTask.getVariable("inputsRef");

      Object subjectRevisionVar = delegateTask.getVariable("subjectArtifactRevisionId");
      UUID subjectArtifactRevisionId =
          subjectRevisionVar == null ? null : UUID.fromString((String) subjectRevisionVar);
      if (subjectArtifactRevisionId == null) {
        subjectArtifactRevisionId = resolveSubjectArtifactRevision(workspaceId, caseInstanceId);
      }

      String escalationPolicyKey = (String) delegateTask.getVariable("escalationPolicyKey");
      Object escalationPolicyVersionVar = delegateTask.getVariable("escalationPolicyVersion");
      Integer escalationPolicyVersion =
          escalationPolicyVersionVar == null
              ? null
              : ((Number) escalationPolicyVersionVar).intValue();

      GateInstance gate =
          gateService.open(
              workspaceId,
              caseInstanceId,
              gateType,
              gateDefinitionKey,
              gateDefinitionVersion,
              subjectArtifactRevisionId,
              inputsRef,
              escalationPolicyKey,
              escalationPolicyVersion,
              delegateTask.getId());

      Object deltaReportIdVar = delegateTask.getVariable("regenerationDeltaReportId");
      if (deltaReportIdVar != null) {
        UUID deltaReportId = UUID.fromString((String) deltaReportIdVar);
        gate.attachDeltaReport(deltaReportId);
        gateInstanceRepository.save(gate);
        gateEventPublisher.publishRegenerationTriggered(gate, subjectArtifactRevisionId);
      }
    } finally {
      WorkspaceContext.clear();
    }
  }

  /**
   * T019/T020: materialize (or reuse, if unchanged) the ArtifactRevision for the case's most
   * recently validated persona output — the one that just fed into this gate — so the gate always
   * has a real subject to review/regenerate against, matching {@code
   * gate_instance.subjectArtifactRevisionId}'s intent even though neither embedding workflow (T015)
   * populates it explicitly. Fails open (returns null) when the case has no validated persona
   * output yet (e.g. a services-only slice test with no pipeline behind it) — the gate still opens,
   * just without a resolvable subject.
   */
  private UUID resolveSubjectArtifactRevision(UUID workspaceId, UUID caseInstanceId) {
    List<PersonaOutputPort.ValidatedOutput> outputs =
        personaOutputPort.validatedOutputsForCase(caseInstanceId);
    if (outputs.isEmpty()) {
      return null;
    }
    PersonaOutputPort.ValidatedOutput last = outputs.get(outputs.size() - 1);
    String kind = artifactService.deriveArtifactKind(last.personaKey());
    return artifactService
        .createRevision(workspaceId, caseInstanceId, caseInstanceId, last, kind)
        .map(ArtifactRevision::getId)
        .orElse(null);
  }
}
