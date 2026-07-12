package com.d2os.artifacts.dto;

import com.d2os.artifacts.ExecutionPackage;
import com.d2os.artifacts.HandoverRecord;
import java.util.UUID;

/** API view of a delivered package (contracts/api.yaml #/ExecutionPackage). */
public record PackageResponse(
    UUID id, Object manifest, String manifestHash, HandoverView handoverRecord) {
  public record HandoverView(
      Object contentsIndex,
      UUID submissionRef,
      UUID definitionSnapshotRef,
      Object artifactHashes,
      String decisionLogRef,
      String ownerName,
      String nextAction) {}

  public static PackageResponse from(
      ExecutionPackage pkg,
      HandoverRecord handover,
      Object parsedManifest,
      Object parsedContentsIndex,
      Object parsedArtifactHashes) {
    return new PackageResponse(
        pkg.getId(),
        parsedManifest,
        pkg.getManifestHash(),
        new HandoverView(
            parsedContentsIndex,
            handover.getSubmissionRef(),
            handover.getDefinitionSnapshotRef(),
            parsedArtifactHashes,
            handover.getDecisionLogRef(),
            handover.getOwnerName(),
            handover.getNextAction()));
  }
}
