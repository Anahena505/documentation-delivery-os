package com.d2os.artifacts;

import com.d2os.artifacts.access.PackageAccessService;
import com.d2os.casecore.CaseDefinitionSnapshot;
import com.d2os.casecore.CaseDefinitionSnapshotRepository;
import com.d2os.casecore.CaseService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Assembles the Execution Package manifest and hash-stamp for a Case (T036, SC-005). The manifest
 * hash is SHA-256 over the ordered concatenation of member content hashes — verifiable by anyone
 * holding the artifact contents, without needing the package row itself.
 */
@Service
public class PackageAssemblyService {

    private final ArtifactRepository artifactRepository;
    private final ArtifactRevisionRepository revisionRepository;
    private final ExecutionPackageRepository packageRepository;
    private final CaseDefinitionSnapshotRepository snapshotRepository;
    private final CaseService caseService;
    private final PackageAccessService packageAccessService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    public PackageAssemblyService(ArtifactRepository artifactRepository,
                                  ArtifactRevisionRepository revisionRepository,
                                  ExecutionPackageRepository packageRepository,
                                  CaseDefinitionSnapshotRepository snapshotRepository,
                                  CaseService caseService,
                                  PackageAccessService packageAccessService,
                                  ObjectMapper objectMapper,
                                  JdbcTemplate jdbcTemplate) {
        this.artifactRepository = artifactRepository;
        this.revisionRepository = revisionRepository;
        this.packageRepository = packageRepository;
        this.snapshotRepository = snapshotRepository;
        this.caseService = caseService;
        this.packageAccessService = packageAccessService;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public ExecutionPackage assemble(UUID workspaceId, UUID caseId) {
        // Defense in depth (US3, T032, FR-007): never assemble a package while an unresolved
        // deterministic cross-artifact contradiction exists — the consistency-check subprocess should
        // already have blocked such a case, this guarantees it even if the flow is ever changed.
        Long openDeterministic = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM consistency_finding WHERE case_id = ? "
                        + "AND tier = 'DETERMINISTIC' AND status = 'OPEN'", Long.class, caseId);
        if (openDeterministic != null && openDeterministic > 0) {
            throw new IllegalStateException(
                    "cannot assemble package: " + openDeterministic + " open deterministic consistency finding(s)");
        }

        List<Artifact> artifacts = artifactRepository.findByCaseInstanceId(caseId);
        assertConditionalArtifactsFulfilled(caseId, artifacts);

        List<Map<String, String>> manifestEntries = new ArrayList<>();
        StringBuilder hashConcat = new StringBuilder();

        for (Artifact artifact : artifacts.stream()
                .sorted((a, b) -> a.getArtifactType().compareTo(b.getArtifactType())).toList()) {
            List<ArtifactRevision> revisions = revisionRepository.findByArtifactId(artifact.getId());
            if (revisions.isEmpty()) continue;
            ArtifactRevision latest = revisions.get(revisions.size() - 1);
            manifestEntries.add(Map.of(
                    "artifactType", artifact.getArtifactType(),
                    "artifactRevisionId", latest.getId().toString(),
                    "contentHash", latest.getContentHash()));
            hashConcat.append(latest.getContentHash());
        }

        String manifestJson = toJson(manifestEntries);
        String manifestHash = HashUtil.sha256Hex(hashConcat.toString());

        ExecutionPackage pkg = new ExecutionPackage(UUID.randomUUID(), workspaceId, caseId, manifestJson, manifestHash);
        packageRepository.save(pkg);
        // Phase 7 US5 (T040, research R6, FR-015): reading a delivered package is default-deny; seed
        // the one participant grant at delivery time rather than leaving it workspace-wide-open.
        packageAccessService.seedParticipantGrant(workspaceId, pkg.getId());
        return pkg;
    }

    /** SC-005: recompute the manifest hash from current member hashes and compare. */
    public boolean verify(ExecutionPackage pkg) {
        try {
            var entries = objectMapper.readTree(pkg.getManifest());
            StringBuilder hashConcat = new StringBuilder();
            entries.forEach(e -> hashConcat.append(e.path("contentHash").asText()));
            return HashUtil.sha256Hex(hashConcat.toString()).equals(pkg.getManifestHash());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Phase 4 US5 completeness gate (T033, FR-014/015): block assembly — and so delivery — until
     * every CONDITIONAL required artifact the pinned snapshot froze in (T032) has at least one
     * produced artifact of its kind. No prior "package-completeness" blocking mechanism existed in
     * this codebase to reuse (the task's premise); this is new, built in the same defense-in-depth
     * style as the deterministic-consistency-finding guard directly above it. BASE required artifacts
     * are intentionally NOT enforced here — a missing BASE artifact means a persona never validated
     * (already surfaced as an Escalated Case upstream, materializeForCase returning empty), so this
     * gate stays scoped to what T033 actually asks for.
     */
    private void assertConditionalArtifactsFulfilled(UUID caseId, List<Artifact> artifacts) {
        CaseDefinitionSnapshot snapshot = snapshotRepository.findByCaseInstanceId(caseId).orElse(null);
        Set<String> producedKinds = artifacts.stream().map(Artifact::getArtifactType).collect(Collectors.toSet());
        List<String> unfulfilled = caseService.requiredArtifacts(snapshot).stream()
                .filter(entry -> "CONDITIONAL".equals(entry.get("source")))
                .map(entry -> (String) entry.get("artifactKind"))
                .filter(kind -> !producedKinds.contains(kind))
                .toList();
        if (!unfulfilled.isEmpty()) {
            throw new IllegalStateException(
                    "cannot assemble package: conditionally required artifact kind(s) not yet produced: " + unfulfilled);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unserializable manifest", e);
        }
    }
}
