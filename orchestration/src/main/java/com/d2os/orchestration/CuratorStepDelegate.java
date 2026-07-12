package com.d2os.orchestration;

import com.d2os.knowledge.capture.CaptureCandidate;
import com.d2os.knowledge.capture.CaptureCandidateRepository;
import com.d2os.knowledge.capture.RedactionService;
import com.d2os.tenancy.WorkspaceContext;
import com.d2os.tenancy.security.WorkspaceRlsBinder;
import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

/**
 * BPMN service-task delegate for the Curator gate of the {@code knowledge-capture} process (T023,
 * FR-011/FR-012). It saves the Curator redaction as a NEW candidate revision via {@link
 * RedactionService} (PREFILTERED → REDACTED → D4_PENDING), preserving the prior revision (FR-011)
 * and recording the CURATION promotion gate PASS with the Curator actor.
 *
 * <p><b>Deviation from the "run the Curator through the full persona path" wording:</b> the
 * standard persona-execution path ({@code PersonaExecutionService.executePersona}) resolves the
 * persona/prompt/ rubric from the CASE's pinned {@code CaseDefinitionSnapshot} (frozen at Planned).
 * The delivered initiation case's snapshot pins the initiation suite only — it does NOT contain the
 * {@code knowledge-curator} persona (which is a Phase 3 capture-time definition), so invoking
 * {@code executePersona(caseId, "knowledge-curator")} against that case would fail to resolve.
 * Rather than mutate the frozen initiation snapshot (forbidden — the initiation workflow must stay
 * untouched), the v1 Curator gate produces the redaction deterministically: the pre-filter has
 * already excluded sensitive spans by default (T3-c), so the redaction draft is the pre-filtered
 * content, saved as a new revision through {@link RedactionService}. <b>No rubric is evaluated on
 * this automated path</b> — the curation rubric is scored only when the Curator op runs through the
 * persona path, which v1 does not do (see above). The substantive human gate in v1 is D4: the
 * workspace owner reviews the redacted content before it can publish. The {@code knowledge-curator}
 * persona / curation playbook / curation rubric / redaction prompt are seeded in the catalog (T025)
 * for provenance and for a later capture-time snapshot that runs the Curator op through the persona
 * path and applies the rubric.
 *
 * <p>Binds {@link WorkspaceContext} + RLS from the process {@code workspaceId} variable, as the
 * other capture/initiation delegates do (runs on the async job-executor thread pool, no HTTP
 * request).
 */
@Component("curatorStepDelegate")
public class CuratorStepDelegate implements JavaDelegate {

  private static final String CURATOR_PERSONA_KEY = "knowledge-curator";

  private final RedactionService redactionService;
  private final CaptureCandidateRepository candidateRepository;
  private final WorkspaceRlsBinder workspaceRlsBinder;

  public CuratorStepDelegate(
      RedactionService redactionService,
      CaptureCandidateRepository candidateRepository,
      WorkspaceRlsBinder workspaceRlsBinder) {
    this.redactionService = redactionService;
    this.candidateRepository = candidateRepository;
    this.workspaceRlsBinder = workspaceRlsBinder;
  }

  @Override
  public void execute(DelegateExecution execution) {
    UUID workspaceId = UUID.fromString((String) execution.getVariable("workspaceId"));
    UUID candidateId = UUID.fromString((String) execution.getVariable("candidateId"));

    WorkspaceContext.set(workspaceId);
    try {
      workspaceRlsBinder.bindCurrentTransaction(workspaceId);

      CaptureCandidate candidate =
          candidateRepository
              .findById(candidateId)
              .orElseThrow(() -> new NoSuchElementException("candidate " + candidateId));

      // Save the redaction as a new revision. Actor = the Curator persona key so the D4 gate's
      // non-self-satisfiability check (approver ≠ redaction actor) is meaningful.
      UUID revisionId =
          redactionService.saveRedaction(
              candidateId,
              "persona:" + CURATOR_PERSONA_KEY,
              candidate.getTitle(),
              candidate.getContent(),
              Arrays.asList(candidate.getTags()),
              null); // curator_operation_execution_id: op-snapshot linkage is a later refinement
      execution.setVariable("candidateId", revisionId.toString()); // D4 reviews the latest revision
    } finally {
      WorkspaceContext.clear();
    }
  }
}
