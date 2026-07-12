package com.d2os.casecore;

import java.util.UUID;

/**
 * Phase 4 Q2 guard conflict (research R3, FR-012/013): {@link MutatingCaseGuard#acquire} found the
 * Feature's mutating-case slot already occupied (or the caller's expected {@code aggregate_version}
 * was stale) → HTTP 409. Distinct from {@link CaseConflictException} (the Phase 1-3 DB partial
 * unique index on {@code case_instance}) — this one carries the Feature/occupying-case ids so the
 * caller can report exactly which case holds the slot.
 */
public class MutatingGuardConflictException extends RuntimeException {

  private final UUID featureId;
  private final UUID activeCaseId;

  public MutatingGuardConflictException(UUID featureId, UUID activeCaseId) {
    super(
        "feature "
            + featureId
            + " already has an active mutating case"
            + (activeCaseId != null ? " (" + activeCaseId + ")" : ""));
    this.featureId = featureId;
    this.activeCaseId = activeCaseId;
  }

  public UUID getFeatureId() {
    return featureId;
  }

  public UUID getActiveCaseId() {
    return activeCaseId;
  }
}
