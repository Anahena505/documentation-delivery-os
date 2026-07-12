package com.d2os.orchestration;

import com.d2os.artifacts.Artifact;
import com.d2os.artifacts.ArtifactRepository;
import com.d2os.artifacts.ArtifactRevision;
import com.d2os.artifacts.ArtifactRevisionRepository;
import com.d2os.casecore.AuditWriter;
import com.d2os.casecore.CaseInstance;
import com.d2os.casecore.CaseInstanceRepository;
import com.d2os.casecore.CaseStatus;
import com.d2os.tenancy.WorkspaceContext;
import com.d2os.tenancy.security.WorkspaceRlsBinder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * First step of {@code enhancement-v1} (T020/T023, US3, research R4). Resolves the Enhancement
 * case's Feature's most recent <b>Delivered</b> Case and pins the EXACT ArtifactRevisions that Case
 * delivered — not "latest as of now" — as this case's baseline set.
 *
 * <p><b>Durable record (design decision):</b> the resolved baseline set is recorded as a single
 * {@code BASELINE_RESOLVED} {@link AuditWriter} entry on the Enhancement case ({@code
 * subject_type='case_instance'}), carrying every baseline ArtifactRevision's id/type/revision-no/
 * content-hash/storage-ref in {@code details}. This is the SAME "audit entry as durable record"
 * idiom {@code CaseController}'s {@code GET /cases/{id}/baseline} (T025) reads back — no new table,
 * matching this codebase's established convention for case-scoped facts that don't warrant a
 * dedicated column (research R4's own rationale: reuse existing structure, never add schema).
 *
 * <p><b>Same-transaction DERIVES_FROM linking — timing design decision (T023):</b> R4 requires
 * every delta/impact ArtifactRevision this case produces to carry a {@code DERIVES_FROM} trace_link
 * to a baseline revision, written in the SAME transaction as that artifact's revision row. Artifact
 * rows are NOT created here (this delegate runs as the pipeline's first step, before any persona
 * has produced anything) — they are created later, once per validated persona output, inside {@code
 * ArtifactService#materializeForCase} (invoked by {@code AssemblePackageDelegate} at the end of the
 * pipeline). So this delegate does not write any trace_link itself; it only resolves and durably
 * records the baseline set. {@code ArtifactService#materializeForCase}/{@code createRevision} read
 * the SAME {@code BASELINE_RESOLVED} audit entry back (by caseId, via {@code AuditEntryRepository})
 * and write one {@code DERIVES_FROM} edge per baseline revision, in the SAME {@code @Transactional}
 * method call that persists each new ArtifactRevision row (see {@code
 * ArtifactService#linkToBaseline}). This keeps "resolve the baseline" (this delegate, pipeline step
 * 1) and "link each produced artifact to it" (artifact-creation time — the actual moment a revision
 * row is born, at the end of the pipeline) as two distinct moments, exactly as R4 itself describes
 * them, without this delegate needing to run again per artifact or per persona step.
 *
 * <p>Every delta-analysis/impact-analysis persona in this case fans its DERIVES_FROM edges out to
 * EVERY baseline revision (not a narrower per-artifact-type mapping) — there is no existing schema
 * field associating one specific NEW artifact with one specific OLD one, and inventing one would be
 * new relationship modeling beyond this phase's zero-schema-change scope (research R1). Fanning out
 * to the whole baseline set is the honest, schema-free reading of "references the baseline" (R4) —
 * and since Enhancement's entire persona roster IS the delta/impact set (no unrelated intake
 * persona), this also satisfies "100% of delta/impact revisions carry DERIVES_FROM links"
 * (T026/SC-004) exactly.
 */
@Component("baselineResolutionDelegate")
public class BaselineResolutionDelegate implements JavaDelegate {

  /** Audit action T025's {@code GET /cases/{id}/baseline} and {@code ArtifactService} read back. */
  static final String BASELINE_RESOLVED_ACTION = "BASELINE_RESOLVED";

  private final CaseInstanceRepository caseInstanceRepository;
  private final ArtifactRepository artifactRepository;
  private final ArtifactRevisionRepository revisionRepository;
  private final AuditWriter auditWriter;
  private final WorkspaceRlsBinder workspaceRlsBinder;

  public BaselineResolutionDelegate(
      CaseInstanceRepository caseInstanceRepository,
      ArtifactRepository artifactRepository,
      ArtifactRevisionRepository revisionRepository,
      AuditWriter auditWriter,
      WorkspaceRlsBinder workspaceRlsBinder) {
    this.caseInstanceRepository = caseInstanceRepository;
    this.artifactRepository = artifactRepository;
    this.revisionRepository = revisionRepository;
    this.auditWriter = auditWriter;
    this.workspaceRlsBinder = workspaceRlsBinder;
  }

  @Override
  @Transactional
  public void execute(DelegateExecution execution) {
    UUID caseId = UUID.fromString(execution.getProcessInstanceBusinessKey());
    UUID workspaceId = UUID.fromString((String) execution.getVariable("workspaceId"));

    WorkspaceContext.set(workspaceId);
    try {
      // Re-bind RLS on this job's transaction connection (same as every other delegate in this
      // package): Flowable checked the connection out before WorkspaceContext could be set.
      workspaceRlsBinder.bindCurrentTransaction(workspaceId);

      CaseInstance kase =
          caseInstanceRepository
              .findById(caseId)
              .orElseThrow(() -> new NoSuchElementException("case " + caseId));

      CaseInstance baselineCase =
          caseInstanceRepository
              .findFirstByFeatureIdAndStatusOrderByCreatedAtDesc(
                  kase.getFeatureId(), CaseStatus.Delivered.name())
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "Enhancement case "
                              + caseId
                              + " has no Delivered baseline Case on feature "
                              + kase.getFeatureId()
                              + " — the confirm-time 422 gate "
                              + "(SubmissionService.confirmCaseType, T024) should have prevented this "
                              + "case from ever being created"));

      List<Map<String, Object>> revisionEntries = new ArrayList<>();
      for (Artifact artifact : artifactRepository.findByCaseInstanceId(baselineCase.getId())) {
        revisionRepository
            .findFirstByArtifactIdOrderByRevisionNoDesc(artifact.getId())
            .ifPresent(revision -> revisionEntries.add(baselineEntry(artifact, revision)));
      }
      if (revisionEntries.isEmpty()) {
        throw new IllegalStateException(
            "baseline case "
                + baselineCase.getId()
                + " on feature "
                + kase.getFeatureId()
                + " is Delivered but has no artifact revisions to anchor to");
      }

      Map<String, Object> details = new LinkedHashMap<>();
      details.put("featureId", kase.getFeatureId().toString());
      details.put("baselineCaseId", baselineCase.getId().toString());
      details.put("revisions", revisionEntries);
      auditWriter.record(
          workspaceId, "case_instance", caseId, BASELINE_RESOLVED_ACTION, "system", details);

      // Process-variable mirror (global scope, persists on the process instance): informational
      // only for later steps in this same run to inspect without a repository round-trip — the
      // audit entry above is the durable source of truth every OTHER component reads from.
      execution.setVariable("baselineRevisionCount", revisionEntries.size());
    } finally {
      WorkspaceContext.clear();
    }
  }

  private Map<String, Object> baselineEntry(Artifact artifact, ArtifactRevision revision) {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("artifactId", artifact.getId().toString());
    entry.put("artifactType", artifact.getArtifactType());
    entry.put("revisionId", revision.getId().toString());
    entry.put("revisionNo", revision.getRevisionNo());
    entry.put("contentHash", revision.getContentHash());
    entry.put("storageRef", revision.getStorageRef());
    return entry;
  }
}
