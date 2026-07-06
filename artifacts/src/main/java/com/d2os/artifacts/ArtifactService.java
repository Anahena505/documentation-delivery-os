package com.d2os.artifacts;

import com.d2os.artifacts.spi.PersonaOutputPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Materializes an Artifact + first ArtifactRevision from each validated persona output (T035).
 * The revision's content hash is taken directly from the already-computed
 * {@code OperationExecution.output_hash} rather than re-hashing — the persisted operation output
 * *is* the artifact content in v1 (one persona step → one artifact), so recomputation would be
 * redundant, not more correct.
 */
@Service
public class ArtifactService {

    private final ArtifactRepository artifactRepository;
    private final ArtifactRevisionRepository revisionRepository;
    private final PersonaOutputPort personaOutputPort;

    public ArtifactService(ArtifactRepository artifactRepository,
                           ArtifactRevisionRepository revisionRepository,
                           PersonaOutputPort personaOutputPort) {
        this.artifactRepository = artifactRepository;
        this.revisionRepository = revisionRepository;
        this.personaOutputPort = personaOutputPort;
    }

    @Transactional
    public List<ArtifactRevision> materializeForCase(UUID workspaceId, UUID caseId) {
        List<ArtifactRevision> revisions = new ArrayList<>();
        for (PersonaOutputPort.ValidatedOutput output : personaOutputPort.validatedOutputsForCase(caseId)) {
            Artifact artifact = new Artifact(
                    UUID.randomUUID(), workspaceId, caseId,
                    // Template definition association is deferred (see T020 note: template content
                    // authoring is out of scope for this pass); the persona key stands in as the
                    // artifact type until real TemplateDefinition mapping lands.
                    UUID.nameUUIDFromBytes(output.personaKey().getBytes()), "1.0.0", output.personaKey());
            artifactRepository.save(artifact);

            ArtifactRevision revision = new ArtifactRevision(
                    UUID.randomUUID(), workspaceId, artifact.getId(), 1,
                    output.storageRef(), output.contentHash(), output.operationExecutionId());
            revisions.add(revisionRepository.save(revision));
        }
        return revisions;
    }
}
