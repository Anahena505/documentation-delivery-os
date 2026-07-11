package com.d2os.governance.reopen;

import com.d2os.artifacts.ArtifactRevision;
import com.d2os.artifacts.ArtifactRevisionRepository;
import com.d2os.casecore.AuditWriter;
import com.d2os.casecore.DecisionRecord;
import com.d2os.casecore.DecisionRepository;
import com.d2os.governance.DeltaReport;
import com.d2os.governance.DeltaReportService;
import com.d2os.governance.GateEventPublisher;
import com.d2os.governance.GateInstance;
import com.d2os.governance.GateInstanceRepository;
import com.d2os.governance.GateStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Acts on a direct ({@code depth=1}) reopen candidate (Phase 5 US3, T027, research R3, FR-007/008,
 * Principle V). Requires an {@link ImpactAssessment} to exist first (409 without one); transitive
 * candidates are never accepted here (409 — {@code MANUAL_REVIEW} only, Q3/AD-5).
 *
 * <p><b>Scope note</b>: this transitions the domain state {@code REOPEN_CANDIDATE -> REOPENED} and
 * records the Decision/AuditEntry/delta-report attachment — the durable, auditable facts the task
 * calls for. It does NOT additionally drive {@code REOPENED -> OPEN} with a live re-activated engine
 * userTask: no existing BPMN mechanism in this codebase supports resuming a completed {@code
 * review-gate}/{@code approval-gate} callActivity instance (Flowable tasks are terminal once
 * completed), and building one (e.g. a dedicated re-entry event subprocess) is a BPMN redesign beyond
 * this delivery's scope. {@link GateStatus}'s own state-machine doc documents {@code REOPENED -> OPEN}
 * as a distinct, later edge — this method stops at {@code REOPENED}, honestly, rather than faking an
 * engine signal that would do nothing.
 */
@Service
public class ReopenService {

    private final GateInstanceRepository gateInstanceRepository;
    private final GateReopenCandidateRepository candidateRepository;
    private final ImpactAssessmentRepository impactAssessmentRepository;
    private final ArtifactRevisionRepository artifactRevisionRepository;
    private final DeltaReportService deltaReportService;
    private final DecisionRepository decisionRepository;
    private final AuditWriter auditWriter;
    private final GateEventPublisher gateEventPublisher;

    public ReopenService(GateInstanceRepository gateInstanceRepository,
                         GateReopenCandidateRepository candidateRepository,
                         ImpactAssessmentRepository impactAssessmentRepository,
                         ArtifactRevisionRepository artifactRevisionRepository,
                         DeltaReportService deltaReportService,
                         DecisionRepository decisionRepository,
                         AuditWriter auditWriter,
                         GateEventPublisher gateEventPublisher) {
        this.gateInstanceRepository = gateInstanceRepository;
        this.candidateRepository = candidateRepository;
        this.impactAssessmentRepository = impactAssessmentRepository;
        this.artifactRevisionRepository = artifactRevisionRepository;
        this.deltaReportService = deltaReportService;
        this.decisionRepository = decisionRepository;
        this.auditWriter = auditWriter;
        this.gateEventPublisher = gateEventPublisher;
    }

    /**
     * Record an {@link ImpactAssessment} for {@code gateId} (T026, FR-007) — the prerequisite {@link
     * #reopen} checks for. At most one per {@code (gate, upstreamRevision)} pair (DB unique constraint;
     * a duplicate call is rejected by the constraint violation surfacing as a 500 today — acceptable
     * since the worklist UI only ever offers this action once per candidate).
     */
    @Transactional
    public ImpactAssessment recordImpactAssessment(UUID gateId, UUID upstreamArtifactRevisionId,
                                                    String reason, String scope, String risk, String author) {
        GateInstance gate = gateInstanceRepository.findById(gateId)
                .orElseThrow(() -> new NoSuchElementException("gate " + gateId));
        if (gate.status() != GateStatus.REOPEN_CANDIDATE) {
            throw new ReopenNotAllowedException(
                    "gate " + gateId + " is not a reopen candidate (status=" + gate.status() + ")");
        }
        ImpactAssessment assessment = new ImpactAssessment(UUID.randomUUID(), gate.getWorkspaceId(), gateId,
                upstreamArtifactRevisionId, reason, scope, risk, author);
        impactAssessmentRepository.save(assessment);
        gateEventPublisher.publishImpactAssessed(gate, assessment.getId(), author);
        return assessment;
    }

    @Transactional
    public GateInstance reopen(UUID gateId, String actorId) {
        GateInstance gate = gateInstanceRepository.findById(gateId)
                .orElseThrow(() -> new NoSuchElementException("gate " + gateId));

        GateReopenCandidate candidate = candidateRepository.findByGateInstanceId(gateId).stream()
                .filter(c -> c.getDisposition() != GateReopenCandidate.Disposition.DISMISSED)
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("gate " + gateId + " has no reopen candidate row"));
        if (candidate.getDepth() > 1) {
            throw new ReopenNotAllowedException(
                    "candidate for gate " + gateId + " is transitive (depth=" + candidate.getDepth()
                            + ") — only direct (depth=1) candidates may be reopened, MANUAL_REVIEW otherwise (Q3/AD-5)");
        }
        if (!impactAssessmentRepository.existsByGateInstanceId(gateId)) {
            throw new ReopenNotAllowedException(
                    "gate " + gateId + " has no impact assessment yet — required before reopen (FR-007)");
        }
        if (gate.status() != GateStatus.REOPEN_CANDIDATE) {
            throw new ReopenNotAllowedException(
                    "gate " + gateId + " is not currently a reopen candidate (status=" + gate.status() + ")");
        }

        UUID decisionId = UUID.randomUUID();
        OffsetDateTime decidedAt = OffsetDateTime.now();
        decisionRepository.save(new DecisionRecord(decisionId, gate.getWorkspaceId(), gate.getCaseInstanceId(),
                "GATE_REOPEN", actorId, null, gate.getId().toString()));

        gate.transitionTo(GateStatus.REOPENED);
        gate.recordDecision(decisionId, decidedAt);
        gate.recordReopened(decidedAt);
        attachDeltaReport(gate, candidate);
        gateInstanceRepository.save(gate);

        // 008 US5 (T051): a reopen is a trust-sensitive decision (FR-013) — stamp the authenticated
        // actor + governance-approver role (no-op/NULL in default mode).
        auditWriter.recordDecision(gate.getWorkspaceId(), "gate_instance", gate.getId(), "GATE_REOPEN",
                actorId, "approver",
                Map.of("candidateId", candidate.getId().toString(), "depth", candidate.getDepth()));

        candidate.markDisposition(GateReopenCandidate.Disposition.REOPENED);
        candidateRepository.save(candidate);

        gateEventPublisher.publishReopened(gate, actorId);
        return gate;
    }

    /** Diff the candidate's stored (superseded) upstream revision against the artifact's current latest. */
    private void attachDeltaReport(GateInstance gate, GateReopenCandidate candidate) {
        ArtifactRevision fromRevision = artifactRevisionRepository
                .findById(candidate.getUpstreamArtifactRevisionId()).orElse(null);
        if (fromRevision == null) {
            return;   // upstream revision row no longer resolvable — reopen still proceeds without a delta
        }
        ArtifactRevision toRevision = artifactRevisionRepository
                .findFirstByArtifactIdOrderByRevisionNoDesc(fromRevision.getArtifactId()).orElse(null);
        if (toRevision == null || toRevision.getId().equals(fromRevision.getId())) {
            return;   // nothing newer to diff against
        }
        DeltaReport report = deltaReportService.generate(
                gate.getWorkspaceId(), fromRevision.getArtifactId(), fromRevision, toRevision);
        gate.attachDeltaReport(report.getId());
    }
}
