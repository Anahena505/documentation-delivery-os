package com.d2os.artifacts;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the full-provenance HandoverRecord for a delivered package (T037, FR-008, clarification
 * Q4). All six fields are populated — enforced by {@link HandoverRecord}'s constructor.
 */
@Service
public class HandoverRecordService {

  private final HandoverRecordRepository repository;
  private final ArtifactRepository artifactRepository;
  private final ArtifactRevisionRepository revisionRepository;
  private final ObjectMapper objectMapper;

  public HandoverRecordService(
      HandoverRecordRepository repository,
      ArtifactRepository artifactRepository,
      ArtifactRevisionRepository revisionRepository,
      ObjectMapper objectMapper) {
    this.repository = repository;
    this.artifactRepository = artifactRepository;
    this.revisionRepository = revisionRepository;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public HandoverRecord create(
      UUID workspaceId, ExecutionPackage pkg, UUID submissionRef, UUID definitionSnapshotRef) {
    List<Artifact> artifacts = artifactRepository.findByCaseInstanceId(pkg.getCaseInstanceId());
    List<String> contentsIndex = artifacts.stream().map(Artifact::getArtifactType).toList();

    List<Map<String, String>> artifactHashes =
        artifacts.stream()
            .flatMap(
                a ->
                    revisionRepository.findByArtifactId(a.getId()).stream()
                        .reduce((first, second) -> second)
                        .stream() // latest revision only
                        .map(
                            rev ->
                                Map.of(
                                    "artifact", a.getArtifactType(), "hash", rev.getContentHash())))
            .toList();

    HandoverRecord record =
        new HandoverRecord(
            UUID.randomUUID(),
            workspaceId,
            pkg.getId(),
            toJson(contentsIndex),
            submissionRef,
            definitionSnapshotRef,
            toJson(artifactHashes),
            "case:" + pkg.getCaseInstanceId() + ":audit",
            "workspace-owner",
            "Review the delivered package and route to the implementation team");

    return repository.save(record);
  }

  private String toJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Unserializable handover field", e);
    }
  }
}
