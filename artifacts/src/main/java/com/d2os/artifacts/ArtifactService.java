package com.d2os.artifacts;

import com.d2os.artifacts.spi.PersonaOutputPort;
import com.d2os.artifacts.storage.ObjectStoreClient;
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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private final ObjectStoreClient objectStoreClient;

    public ArtifactService(ArtifactRepository artifactRepository,
                           ArtifactRevisionRepository revisionRepository,
                           PersonaOutputPort personaOutputPort,
                           CaseDefinitionSnapshotRepository snapshotRepository,
                           AuditWriter auditWriter,
                           AuditEntryRepository auditEntryRepository,
                           ObjectMapper objectMapper,
                           JdbcTemplate jdbcTemplate,
                           ObjectProvider<ArtifactRevisionListener> revisionListener,
                           ObjectStoreClient objectStoreClient) {
        this.artifactRepository = artifactRepository;
        this.revisionRepository = revisionRepository;
        this.personaOutputPort = personaOutputPort;
        this.snapshotRepository = snapshotRepository;
        this.auditWriter = auditWriter;
        this.auditEntryRepository = auditEntryRepository;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.revisionListener = revisionListener;
        this.objectStoreClient = objectStoreClient;
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
        CaseDefinitionSnapshot snapshot = snapshotRepository.findByCaseInstanceId(ownerCaseId).orElse(null);
        CaseTypeCapability.Capability capability = CaseTypeCapability.from(objectMapper, snapshot);
        if (!capability.mutating()) {
            boolean crossCase = !targetCaseId.equals(ownerCaseId);
            boolean kindAllowed = capability.artifactKindAllowlist().contains(artifactKind);
            if (crossCase || !kindAllowed) {
                refuse(workspaceId, ownerCaseId, targetCaseId, output, artifactKind, crossCase);
                return Optional.empty();
            }
        }

        // US6 (T056, FR-014, research R9): if the owning case pinned a renderable TemplateDefinition
        // for this persona output, render its body deterministically ({{slot}} substitution, NO AI
        // call — see #renderFromPinnedTemplate) and carry provenance on the revision. When no template
        // is resolvable (every pre-US6 case shape — e.g. Initiation, which pins no template refs), this
        // is NULL and the revision keeps the CURRENT behavior: the persona output IS the content and
        // provenance stays NULL. Purely additive.
        RenderedContent rendered = renderFromPinnedTemplate(snapshot, output, artifactKind);
        String effectiveStorageRef = rendered != null ? rendered.storageRef() : output.storageRef();
        String effectiveHash = rendered != null ? rendered.contentHash() : output.contentHash();
        UUID provenanceTemplateId = rendered != null ? rendered.sourceTemplateId() : null;
        String provenanceVersion = rendered != null ? rendered.templateVersion() : null;

        // Phase 5 (T019/T020, research R2, Q4): if this persona key already has a materialized
        // Artifact for this case (e.g. the gate-open path pre-materialized it for review, or this is a
        // comment-and-regenerate re-entry), APPEND a new revision to that SAME Artifact — never mint a
        // second Artifact for content that is logically a correction of the first. Idempotent when the
        // content is byte-identical to the latest revision (the read-only-guarded approve-path
        // re-materialization at assemble-package would otherwise re-insert the exact same bytes as a
        // pointless new revision every time). The comparison uses the EFFECTIVE hash (the rendered
        // hash when rendering applied), so a deterministic re-render of the same inputs stays idempotent.
        Optional<Artifact> existing = artifactRepository
                .findFirstByCaseInstanceIdAndArtifactTypeOrderByCreatedAtDesc(targetCaseId, output.personaKey());
        if (existing.isPresent()) {
            Artifact artifact = existing.get();
            Optional<ArtifactRevision> latest =
                    revisionRepository.findFirstByArtifactIdOrderByRevisionNoDesc(artifact.getId());
            if (latest.isPresent() && latest.get().getContentHash().equals(effectiveHash)) {
                return latest;   // unchanged content — no new revision, never mutate the existing one
            }
            int nextRevisionNo = latest.map(ArtifactRevision::getRevisionNo).orElse(0) + 1;
            ArtifactRevision revision = new ArtifactRevision(
                    UUID.randomUUID(), workspaceId, artifact.getId(), nextRevisionNo,
                    effectiveStorageRef, effectiveHash, output.operationExecutionId(),
                    provenanceTemplateId, provenanceVersion);
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

        // US6 (T056): when a real TemplateDefinition was resolved, the Artifact carries THAT template's
        // id + pinned version (replacing the T020 persona-key stand-in). When none was resolvable, the
        // persona key still stands in as the association, exactly as before (additive).
        UUID artifactTemplateId = rendered != null
                ? rendered.sourceTemplateId() : UUID.nameUUIDFromBytes(output.personaKey().getBytes());
        String artifactTemplateVersion = rendered != null ? rendered.templateVersion() : "1.0.0";
        Artifact artifact = new Artifact(
                UUID.randomUUID(), workspaceId, targetCaseId,
                artifactTemplateId, artifactTemplateVersion, output.personaKey());
        artifactRepository.save(artifact);

        ArtifactRevision revision = new ArtifactRevision(
                UUID.randomUUID(), workspaceId, artifact.getId(), 1,
                effectiveStorageRef, effectiveHash, output.operationExecutionId(),
                provenanceTemplateId, provenanceVersion);
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

    // ---- US6 (T056, research R9): deterministic Template -> Artifact content rendering --------------

    private static final Pattern SLOT_PATTERN = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_]+)\\s*}}");

    /** The content + provenance produced by rendering a pinned TemplateDefinition (T056). */
    private record RenderedContent(String storageRef, String contentHash, UUID sourceTemplateId,
                                   String templateVersion) {}

    /**
     * Resolve the pinned {@link CaseDefinitionSnapshot} TemplateDefinition that {@code produces} this
     * persona output and render its body deterministically, or return {@code null} when there is no
     * renderable template (leaving the caller on the pre-US6 default path with NULL provenance).
     *
     * <p>Resolution walks the snapshot's frozen {@code template} refs (never the live catalog —
     * Principle I), loads each referenced {@code definition_asset} body (visible to the case's
     * workspace OR the system workspace via the {@code ws_isolation_definition} RLS policy), and picks
     * the one whose {@code producedBy} equals the output's persona key AND that declares a non-blank
     * {@code content} skeleton. The rendered bytes are a pure function of the immutable template body
     * and the immutable, content-addressed persona output — no AI call — so a replay of the same
     * inputs reproduces byte-identical content (research R9). Any failure (missing snapshot, malformed
     * body, unreadable output object) degrades to {@code null}: the default behavior is always
     * preserved, never broken, by the rendering path.
     */
    private RenderedContent renderFromPinnedTemplate(CaseDefinitionSnapshot snapshot,
            PersonaOutputPort.ValidatedOutput output, String artifactKind) {
        if (snapshot == null || output.storageRef() == null) {
            return null;
        }
        try {
            JsonNode entries = objectMapper.readTree(snapshot.getEntries());
            for (JsonNode entry : entries) {
                if (!"template".equals(entry.path("type").asText())) continue;
                String key = entry.path("key").asText(null);
                String version = entry.path("version").asText(null);
                if (key == null || version == null) continue;

                Map<String, Object> asset = loadTemplateAsset(key, version);
                if (asset == null) continue;
                JsonNode body = objectMapper.readTree(String.valueOf(asset.get("body")));
                if (!output.personaKey().equals(body.path("producedBy").asText(null))) continue;
                String contentTemplate = body.path("content").asText(null);
                if (contentTemplate == null || contentTemplate.isBlank()) continue;

                String renderedText = renderContent(contentTemplate, output, artifactKind, version);
                String contentHash = HashUtil.sha256Hex(renderedText);
                // Content-addressed key: identical rendered bytes -> identical key -> idempotent PUT,
                // so a re-render (gate pre-materialization, then assemble-package) is a no-op write.
                String storageRef = "rendered/" + contentHash + ".md";
                objectStoreClient.put(storageRef, renderedText.getBytes(StandardCharsets.UTF_8),
                        "text/markdown; charset=utf-8");
                return new RenderedContent(storageRef, contentHash, (UUID) asset.get("id"), version);
            }
        } catch (Exception e) {
            // Never let rendering break the write path — degrade to the default (unrendered) behavior.
            return null;
        }
        return null;
    }

    /** Load a pinned {@code (template,key,version)} definition_asset's id + body, or {@code null}. */
    private Map<String, Object> loadTemplateAsset(String key, String version) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, body FROM definition_asset WHERE type = 'template' AND key = ? AND version = ?",
                key, version);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Deterministic {@code {{slot}}} substitution, reusing studio {@code PromptEditorModel}'s slot
     * convention (T056, research R9). Fills a fixed set of slots derivable purely from recorded,
     * immutable inputs — the actual persona output body, its provenance hash, the persona key, the
     * artifact kind, and the template version — so the result is reproducible on replay. Any slot the
     * template declares outside this set renders empty (never a leaked {@code {{...}}} placeholder).
     */
    private String renderContent(String contentTemplate, PersonaOutputPort.ValidatedOutput output,
            String artifactKind, String templateVersion) {
        String personaOutput = new String(objectStoreClient.get(output.storageRef()), StandardCharsets.UTF_8);
        Map<String, String> slots = new LinkedHashMap<>();
        slots.put("body", personaOutput);
        slots.put("content", personaOutput);
        slots.put("personaKey", output.personaKey());
        slots.put("sourceContentHash", output.contentHash());
        slots.put("artifactKind", artifactKind);
        slots.put("templateVersion", templateVersion);

        Matcher m = SLOT_PATTERN.matcher(contentTemplate);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String replacement = slots.getOrDefault(m.group(1), "");
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
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
