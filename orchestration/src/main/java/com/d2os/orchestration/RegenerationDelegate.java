package com.d2os.orchestration;

import com.d2os.artifacts.Artifact;
import com.d2os.artifacts.ArtifactRepository;
import com.d2os.artifacts.ArtifactRevision;
import com.d2os.artifacts.ArtifactRevisionRepository;
import com.d2os.artifacts.ArtifactService;
import com.d2os.artifacts.spi.PersonaOutputPort;
import com.d2os.governance.DeltaReport;
import com.d2os.governance.DeltaReportService;
import com.d2os.governance.GateInstance;
import com.d2os.governance.GateInstanceRepository;
import com.d2os.governance.GateStatus;
import com.d2os.persona.PersonaExecutionService;
import com.d2os.tenancy.WorkspaceContext;
import com.d2os.tenancy.security.WorkspaceRlsBinder;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

/**
 * REQUEST_CHANGES → comment-and-regenerate re-entry (Phase 5, T019, US2, research R2, FR-004/005,
 * Principle II). Wired into {@code initiation-v3}/{@code assessment-v2}'s {@code gw-gate} exclusive
 * gateway: when {@code GateService.decide()} recorded REQUEST_CHANGES (gate → REGENERATING), the
 * workflow routes here instead of the generic halt end event, then loops back to the SAME {@code
 * review-gate-call} callActivity so the reviewer is re-presented with a fresh gate cycle (research
 * R2's "REQUEST_CHANGES → gate REGENERATING → re-enter persona path → new gate cycle" loop).
 *
 * <p>Follows {@link PersonaStepDelegate}'s WorkspaceContext/RLS-binding boilerplate exactly — this
 * delegate also runs on the Flowable async job-executor thread pool with no HTTP request behind it.
 * Does its work in several smaller transactional calls (mirroring {@link PersonaExecutionService}'s
 * own posture) rather than one big transaction, since {@code executePersona} makes an external AI
 * Gateway call it would be wrong to hold a DB connection open across.
 *
 * <p><b>What this class does NOT do</b>: it never opens the new {@code GateInstance} itself and
 * never writes artifact content directly. Re-entering {@code review-gate-call} spawns a fresh
 * engine userTask, whose {@code create} listener ({@link GateTaskBridge}) is the ONE place a {@code
 * GateInstance} row is ever created (T010/T014) — this delegate only produces the new
 * ArtifactRevision + delta report and hands the delta report id forward as a process variable
 * ({@code regenerationDeltaReportId}) for that bridge to attach once the new gate cycle actually
 * opens (T020's "set the resulting delta_report.id onto the new GateInstance.deltaReportId field").
 * The only artifact-content write path anywhere is {@link ArtifactService#createRevision} (T023's
 * API-surface scan asserts this).
 */
@Component("regenerationDelegate")
public class RegenerationDelegate implements JavaDelegate {

  private final GateInstanceRepository gateInstanceRepository;
  private final ArtifactRepository artifactRepository;
  private final ArtifactRevisionRepository artifactRevisionRepository;
  private final ArtifactService artifactService;
  private final PersonaOutputPort personaOutputPort;
  private final PersonaExecutionService personaExecutionService;
  private final DeltaReportService deltaReportService;
  private final WorkspaceRlsBinder workspaceRlsBinder;

  public RegenerationDelegate(
      GateInstanceRepository gateInstanceRepository,
      ArtifactRepository artifactRepository,
      ArtifactRevisionRepository artifactRevisionRepository,
      ArtifactService artifactService,
      PersonaOutputPort personaOutputPort,
      PersonaExecutionService personaExecutionService,
      DeltaReportService deltaReportService,
      WorkspaceRlsBinder workspaceRlsBinder) {
    this.gateInstanceRepository = gateInstanceRepository;
    this.artifactRepository = artifactRepository;
    this.artifactRevisionRepository = artifactRevisionRepository;
    this.artifactService = artifactService;
    this.personaOutputPort = personaOutputPort;
    this.personaExecutionService = personaExecutionService;
    this.deltaReportService = deltaReportService;
    this.workspaceRlsBinder = workspaceRlsBinder;
  }

  @Override
  public void execute(DelegateExecution execution) {
    UUID caseId = UUID.fromString(execution.getProcessInstanceBusinessKey());
    UUID workspaceId = UUID.fromString((String) execution.getVariable("workspaceId"));

    WorkspaceContext.set(workspaceId);
    try {
      workspaceRlsBinder.bindCurrentTransaction(workspaceId);

      GateInstance regeneratingGate =
          gateInstanceRepository
              .findFirstByCaseInstanceIdAndStatusOrderByDecidedAtDesc(
                  caseId, GateStatus.REGENERATING.name())
              .orElse(null);
      if (regeneratingGate == null) {
        return; // defensive no-op — this delegate is only ever reached via a REQUEST_CHANGES route
      }

      UUID priorRevisionId = regeneratingGate.getSubjectArtifactRevisionId();
      if (priorRevisionId == null) {
        return; // nothing to regenerate against (no persona output was ever materialized)
      }
      ArtifactRevision priorRevision =
          artifactRevisionRepository.findById(priorRevisionId).orElse(null);
      if (priorRevision == null) {
        return;
      }
      Artifact artifact = artifactRepository.findById(priorRevision.getArtifactId()).orElse(null);
      if (artifact == null) {
        return;
      }
      String personaKey = artifact.getArtifactType();

      // Re-enter the standard persona execution path (T019, research R2) — reviewer comments are
      // injected as delimited untrusted data (T1-a framing, PromptRenderer) by
      // PersonaExecutionService/ExecutionEnvelopeBuilder's regenerationComments overload, never
      // spliced into the prompt template's instruction portion.
      boolean validated =
          personaExecutionService.executePersona(
              caseId, personaKey, null, regeneratingGate.getReviewerComments());
      if (!validated) {
        return; // exhausted the revise loop and escalated (PersonaExecutionService) — no new
        // revision
      }

      PersonaOutputPort.ValidatedOutput newOutput = latestOutputFor(caseId, personaKey);
      if (newOutput == null) {
        return;
      }

      String kind = artifactService.deriveArtifactKind(personaKey);
      Optional<ArtifactRevision> newRevisionOpt =
          artifactService.createRevision(workspaceId, caseId, caseId, newOutput, kind);
      if (newRevisionOpt.isEmpty()) {
        return; // refused by the read-only guard — shouldn't happen (the original materialized
        // fine)
      }
      ArtifactRevision newRevision = newRevisionOpt.get();

      if (!newRevision.getId().equals(priorRevisionId)) {
        // Content actually changed (ArtifactService's idempotent-by-content-hash check did not
        // short-circuit) — the original revision is untouched (a new row, never an in-place
        // edit), so diff prior vs. new and hand the report id forward for GateTaskBridge to
        // attach to the new gate cycle it is about to open (T020).
        DeltaReport deltaReport =
            deltaReportService.generate(workspaceId, artifact.getId(), priorRevision, newRevision);
        execution.setVariable("regenerationDeltaReportId", deltaReport.getId().toString());
      }
    } finally {
      WorkspaceContext.clear();
    }
  }

  /**
   * The most recently recorded validated output for {@code personaKey} (the just-completed re-run).
   */
  private PersonaOutputPort.ValidatedOutput latestOutputFor(UUID caseId, String personaKey) {
    List<PersonaOutputPort.ValidatedOutput> outputs =
        personaOutputPort.validatedOutputsForCase(caseId);
    for (int i = outputs.size() - 1; i >= 0; i--) {
      if (personaKey.equals(outputs.get(i).personaKey())) {
        return outputs.get(i);
      }
    }
    return null;
  }
}
