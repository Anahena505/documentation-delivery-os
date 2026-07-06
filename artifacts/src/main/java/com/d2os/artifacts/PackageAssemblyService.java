package com.d2os.artifacts;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
    private final ObjectMapper objectMapper;

    public PackageAssemblyService(ArtifactRepository artifactRepository,
                                  ArtifactRevisionRepository revisionRepository,
                                  ExecutionPackageRepository packageRepository,
                                  ObjectMapper objectMapper) {
        this.artifactRepository = artifactRepository;
        this.revisionRepository = revisionRepository;
        this.packageRepository = packageRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ExecutionPackage assemble(UUID workspaceId, UUID caseId) {
        List<Artifact> artifacts = artifactRepository.findByCaseInstanceId(caseId);
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
        return packageRepository.save(pkg);
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

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unserializable manifest", e);
        }
    }
}
