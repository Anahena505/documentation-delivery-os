package com.d2os.artifacts;

import com.d2os.artifacts.spi.PersonaOutputPort;
import com.d2os.casecore.AuditEntryRecord;
import com.d2os.casecore.AuditEntryRepository;
import com.d2os.casecore.AuditWriter;
import com.d2os.casecore.CaseDefinitionSnapshot;
import com.d2os.casecore.CaseDefinitionSnapshotRepository;
import com.d2os.casecore.CaseTypeCapability;
import com.d2os.casecore.spi.ArtifactRevisionListener;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Materializes an Artifact + first ArtifactRevision from each validated persona output (T035).
 * The revision's content hash is taken directly from the already-computed
 * {@code OperationExecution.output_hash} rather than re-hashing — the persisted operation output
 * *is* the artifact content in v1 (one persona step → one artifact), so recomputation would be
 * redundant, not more correct.
 *
 * <p>Phase 4 (T017, research R2, FR-006): {@link #createRevision} is now the single choke point every
 * artifact write for a case passes through — {@link #materializeForCase} is just its loop over the
 * case's validated persona outputs. There, read-only enforcement is applied: if the owning case's
 * pinned {@link CaseDefinitionSnapshot} says {@code mutating=false}, a write is refused (no Artifact/
 * ArtifactRevision row is created) when its artifact kind falls outside the case type's allowlist, or
 * when it targets a DIFFERENT case's artifacts (a cross-case/baseline write — not yet exercised by any
 * caller in this phase, but part of the R2 contract for the Enhancement baseline work later). A
 * refusal never throws — the case keeps running; only that one persona output fails to become a
 * durable artifact revision, and the refusal is recorded as an AuditEntry.
 */
@Service
public class ArtifactService {

    private final ArtifactRepository artifactRepository;
    private final ArtifactRevisionRepository revisionRepository;
    private final PersonaOutputPort personaOutputPort;
    private final CaseDefinitionSnapshotRepository snapshotRepository;
    private final AuditWriter auditWriter;
    private final AuditEntryRepository auditEntryRepository;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectProvider<ArtifactRevisionListener> revisionListener;

    public ArtifactService(ArtifactRepository artifactRepository,
                           ArtifactRevisionRepository revisionRepository,
                           PersonaOutputPort personaOutputPort,
                           CaseDefinitionSnapshotRepository snapshotRepository,
                           AuditWriter auditWriter,
                           AuditEntryRepository auditEntryRepository,
                           ObjectMapper objectMapper,
                           JdbcTemplate jdbcTemplate,
                           ObjectProvider<ArtifactRevisionListener> revisionListener) {
        this.artifactRepository = artifactRepository;
        this.revisionRepository = revisionRepository;
        this.personaOutputPort = personaOutputPort;
        this.snapshotRepository = snapshotRepository;
        this.auditWriter = auditWriter;
        this.auditEntryRepository = auditEntryRepository;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.revisionListener = revisionListener;
    }

    @Transactional
    public List<ArtifactRevision> materializeForCase(UUID workspaceId, UUID caseId) {
        List<ArtifactRevision> revisions = new ArrayList<>();
        // Phase 5 (T023, US3, research R4): resolved once per case, not per persona output — Enhancement's
        // BaselineResolutionDelegate records this as a BASELINE_RESOLVED audit entry (empty/absent for
        // every non-Enhancement case, so this is a no-op list read for them).
        List<UUID> baselineRevisionIds = baselineRevisionIdsFor(caseId);
        for (PersonaOutputPort.ValidatedOutput output : personaOutputPort.validatedOutputsForCase(caseId)) {
            String kind = deriveArtifactKind(output.personaKey());
            createRevision(workspaceId, caseId, caseId, output, kind, baselineRevisionIds).ifPresent(revisions::add);
        }
        return revisions;
    }

    /**
     * Create one ArtifactRevision (and its owning Artifact, if this is the first revision) for a
     * validated persona output — the single choke point every artifact write passes through (T017).
     *
     * @param ownerCaseId  the Case whose pinned snapshot governs this write (whose capability flags
     *                     are read for enforcement)
     * @param targetCaseId the Case the artifact is being written FOR/into — equal to {@code
     *                     ownerCaseId} for every normal persona-output write; a mismatch represents a
     *                     write attempting to attach to another case's artifacts (e.g. a mutating
     *                     write reaching for a Feature's baseline case), which is always refused when
     *                     the owner is read-only
     * @param artifactKind the artifact kind (e.g. {@code FINDINGS}, {@code RECOMMENDATION}) this
     *                     output produces, checked against the owner's allowlist when read-only
     * @return the persisted revision, or {@link Optional#empty()} if the write was refused
     */
    @Transactional
    public Optional<ArtifactRevision> createRevision(UUID workspaceId, UUID ownerCaseId, UUID targetCaseId,
            PersonaOutputPort.ValidatedOutput output, String artifactKind) {
        return createRevision(workspaceId, ownerCaseId, targetCaseId, output, artifactKind, List.of());
    }

    /**
     * Phase 5 (T023, US3, research R4) overload: {@code baselineRevisionIds} is the Enhancement case's
     * pinned baseline ArtifactRevision ids (empty for every other case type). When non-empty AND a NEW
     * ArtifactRevision row is actually persisted below (either a brand-new Artifact's revision 1, or an
     * appended revision — never on the idempotent "unchanged content" early-return, which persists no
     * new row), one {@code DERIVES_FROM} trace_link edge is written per baseline revision id, in this
     * SAME {@code @Transactional} method call — i.e. the same transaction as the artifact row (R4's
     * explicit requirement; see {@code orchestration}'s {@code BaselineResolutionDelegate} javadoc for
     * why the linking happens HERE, at artifact-creation time, rather than when the baseline is first
     * resolved).
     */
    @Transactional
    public Optional<ArtifactRevision> createRevision(UUID workspaceId, UUID ownerCaseId, UUID targetCaseId,
            PersonaOutputPort.ValidatedOutput output, String artifactKind, List<UUID> baselineRevisionIds) {
        CaseTypeCapability.Capability capability = capabilityFor(ownerCaseId);
        if (!capability.mutating()) {
            boolean crossCase = !targetCaseId.equals(ownerCaseId);
            boolean kindAllowed = capability.artifactKindAllowlist().contains(artifactKind);
            if (crossCase || !kindAllowed) {
                refuse(workspaceId, ownerCaseId, targetCaseId, output, artifactKind, crossCase);
                return Optional.empty();
            }
        }

        // Phase 5 (T019/T020, research R2, Q4): if this persona key already has a materialized
        // Artifact for this case (e.g. the gate-open path pre-materialized it for review, or this is a
        // comment-and-regenerate re-entry), APPEND a new revision to that SAME Artifact — never mint a
        // second Artifact for content that is logically a correction of the first. Idempotent when the
        // content is byte-identical to the latest revision (the read-only-guarded approve-path
        // re-materialization at assemble-package would otherwise re-insert the exact same bytes as a
        // pointless new revision every time).
        Optional<Artifact> existing = artifactRepository
                .findFirstByCaseInstanceIdAndArtifactTypeOrderByCreatedAtDesc(targetCaseId, output.personaKey());
        if (existing.isPresent()) {
            Artifact artifact = existing.get();
            Optional<ArtifactRevision> latest =
                    revisionRepository.findFirstByArtifactIdOrderByRevisionNoDesc(artifact.getId());
            if (latest.isPresent() && latest.get().getContentHash().equals(output.contentHash())) {
                return latest;   // unchanged content — no new revision, never mutate the existing one
            }
            int nextRevisionNo = latest.map(ArtifactRevision::getRevisionNo).orElse(0) + 1;
            ArtifactRevision revision = new ArtifactRevision(
                    UUID.randomUUID(), workspaceId, artifact.getId(), nextRevisionNo,
                    output.storageRef(), output.contentHash(), output.operationExecutionId());
            ArtifactRevision saved = revisionRepository.save(revision);
            linkToBaseline(workspaceId, saved.getId(), baselineRevisionIds);
            // Phase 5 US3 (T025): the revision that was just SUPERSEDED (`latest`, before this append)
            // is the one existing trace_link edges point to (DERIVES_FROM/SATISFIES `to_id` is set at
            // the DEPENDENT's creation time, so it always names whichever revision was current then) —
            // governance (if present) looks up dependents of THAT id, not the brand-new one nothing yet
            // depends on. Best-effort/optional, same posture as GateService's EngineGateReleasePort.
            latest.ifPresent(prior -> revisionListener.ifAvailable(l -> l.onNewRevision(workspaceId, prior.getId())));
            return Optional.of(saved);
        }

        Artifact artifact = new Artifact(
                UUID.randomUUID(), workspaceId, targetCaseId,
                // Template definition association is deferred (see T020 note: template content
                // authoring is out of scope for this pass); the persona key stands in as the
                // artifact type until real TemplateDefinition mapping lands.
                UUID.nameUUIDFromBytes(output.personaKey().getBytes()), "1.0.0", output.personaKey());
        artifactRepository.save(artifact);

        ArtifactRevision revision = new ArtifactRevision(
                UUID.randomUUID(), workspaceId, artifact.getId(), 1,
                output.storageRef(), output.contentHash(), output.operationExecutionId());
        ArtifactRevision saved = revisionRepository.save(revision);
        linkToBaseline(workspaceId, saved.getId(), baselineRevisionIds);
        return Optional.of(saved);
    }

    /**
     * Write one {@code DERIVES_FROM} trace_link edge from {@code newRevisionId} to each id in {@code
     * baselineRevisionIds} (research R4). Raw {@code JdbcTemplate} insert — same idiom as {@code
     * ConsistencyService#writeConflictEdge}, the only other {@code trace_link} writer in this codebase
     * (no JPA entity exists for {@code trace_link}). No-op when {@code baselineRevisionIds} is empty
     * (every non-Enhancement case).
     */
    private void linkToBaseline(UUID workspaceId, UUID newRevisionId, List<UUID> baselineRevisionIds) {
        for (UUID baselineRevisionId : baselineRevisionIds) {
            jdbcTemplate.update(
                    "INSERT INTO trace_link (workspace_id, from_type, from_id, to_type, to_id, link_type) "
                            + "VALUES (?, 'artifact_revision', ?, 'artifact_revision', ?, 'DERIVES_FROM')",
                    workspaceId, newRevisionId, baselineRevisionId);
        }
    }

    /**
     * Read back the Enhancement case's {@code BASELINE_RESOLVED} audit entry ({@link
     * BaselineResolutionDelegate}, T023) as the list of pinned baseline ArtifactRevision ids. Empty for
     * any case with no such entry (every non-Enhancement case, or an Enhancement case whose baseline
     * step hasn't run yet).
     */
    private List<UUID> baselineRevisionIdsFor(UUID caseId) {
        return auditEntryRepository
                .findFirstBySubjectTypeAndSubjectIdAndActionOrderByTxTimeDesc(
                        "case_instance", caseId, "BASELINE_RESOLVED")
                .map(this::parseBaselineRevisionIds)
                .orElse(List.of());
    }

    private List<UUID> parseBaselineRevisionIds(AuditEntryRecord entry) {
        try {
            JsonNode revisions = objectMapper.readTree(entry.getDetails()).path("revisions");
            List<UUID> ids = new ArrayList<>();
            revisions.forEach(r -> ids.add(UUID.fromString(r.path("revisionId").asText())));
            return ids;
        } catch (Exception e) {
            return List.of();
        }
    }

    private void refuse(UUID workspaceId, UUID ownerCaseId, UUID targetCaseId,
            PersonaOutputPort.ValidatedOutput output, String artifactKind, boolean crossCase) {
        auditWriter.record(workspaceId, "case_instance", ownerCaseId, "ARTIFACT_WRITE_REFUSED", "system", Map.of(
                "targetCaseId", targetCaseId.toString(),
                "personaKey", output.personaKey(),
                "artifactKind", artifactKind,
                "reason", crossCase ? "cross-case-write" : "kind-not-allowlisted"));
    }

    private CaseTypeCapability.Capability capabilityFor(UUID caseId) {
        CaseDefinitionSnapshot snapshot = snapshotRepository.findByCaseInstanceId(caseId).orElse(null);
        return CaseTypeCapability.from(objectMapper, snapshot);
    }

    /**
     * Derive the artifact kind a persona output represents. Phase 4 (T017): with Assessment as the
     * first read-only case type, there is still no real per-{@code TemplateDefinition} "kind" field in
     * the schema (artifact type == persona key, see the note above) — so "kind" is provisionally
     * derived from the persona key by convention: the recommendation-authoring persona produces {@code
     * RECOMMENDATION}, every other persona (intake context, the parallel subject-analysis specialists,
     * and findings consolidation) produces {@code FINDINGS}. This is intentionally conservative:
     * anything not explicitly recognized as recommendation content falls back to {@code FINDINGS}
     * rather than to a permissive wildcard.
     */
    public String deriveArtifactKind(String personaKey) {
        return personaKey != null && personaKey.contains("recommendation") ? "RECOMMENDATION" : "FINDINGS";
    }
}
