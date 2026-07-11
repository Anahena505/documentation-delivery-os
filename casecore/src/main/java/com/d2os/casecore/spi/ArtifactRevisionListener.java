package com.d2os.casecore.spi;

import java.util.UUID;

/**
 * Port {@code artifacts}' {@code ArtifactService} notifies whenever a new {@code ArtifactRevision}
 * is persisted (Phase 5 US3, T025, research R3, FR-006). {@code governance} depends on {@code
 * artifacts} (for gate {@code inputsRef}/delta-report diffing), so {@code artifacts} cannot depend
 * back on {@code governance} to identify reopen candidates directly — this port lives in {@code
 * casecore} (a module both already depend on) and is implemented by governance's {@code
 * ReopenCandidateService}, injected into {@code ArtifactService} as an optional {@code
 * ObjectProvider} exactly like {@code MutatingCaseGuard}'s sibling ports elsewhere in this codebase
 * — a build with no {@code governance} bean on the classpath gets a harmless no-op.
 */
public interface ArtifactRevisionListener {

  /**
   * A new revision was just persisted for an existing Artifact (never called for a brand-new
   * Artifact's first revision — nothing could depend on content that didn't exist yet).
   */
  void onNewRevision(UUID workspaceId, UUID artifactRevisionId);
}
