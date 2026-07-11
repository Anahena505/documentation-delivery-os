package com.d2os.artifacts;

import com.d2os.artifacts.spi.PersonaOutputPort;
import com.d2os.casecore.AuditWriter;
import com.d2os.casecore.CaseDefinitionSnapshot;
import com.d2os.casecore.CaseDefinitionSnapshotRepository;
import com.d2os.casecore.CaseTypeCapability;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final ObjectMapper objectMapper;

    public ArtifactService(ArtifactRepository artifactRepository,
                           ArtifactRevisionRepository revisionRepository,
                           PersonaOutputPort personaOutputPort,
                           CaseDefinitionSnapshotRepository snapshotRepository,
                           AuditWriter auditWriter,
                           ObjectMapper objectMapper) {
        this.artifactRepository = artifactRepository;
        this.revisionRepository = revisionRepository;
        this.personaOutputPort = personaOutputPort;
        this.snapshotRepository = snapshotRepository;
        this.auditWriter = auditWriter;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public List<ArtifactRevision> materializeForCase(UUID workspaceId, UUID caseId) {
        List<ArtifactRevision> revisions = new ArrayList<>();
        for (PersonaOutputPort.ValidatedOutput output : personaOutputPort.validatedOutputsForCase(caseId)) {
            String kind = deriveArtifactKind(output.personaKey());
            createRevision(workspaceId, caseId, caseId, output, kind).ifPresent(revisions::add);
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
            return Optional.of(revisionRepository.save(revision));
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
        return Optional.of(revisionRepository.save(revision));
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
