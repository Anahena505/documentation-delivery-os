package com.d2os.governance;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Reads/writes {@link GateInstance} rows (RLS-scoped to the caller's workspace). */
public interface GateInstanceRepository extends JpaRepository<GateInstance, UUID> {

  List<GateInstance> findByCaseInstanceId(UUID caseInstanceId);

  /** Correlation lookup for {@code GateTaskBridge} (orchestration) — one gate per engine task. */
  Optional<GateInstance> findByEngineTaskId(String engineTaskId);

  List<GateInstance> findByStatus(String status);

  /**
   * Phase 5 (T019, research R2): {@code RegenerationDelegate} (orchestration) looks up the case's
   * most recently decided REGENERATING gate — the one REQUEST_CHANGES just transitioned — to read
   * its {@code reviewerComments} and {@code subjectArtifactRevisionId} (the "from" revision for the
   * eventual delta report).
   */
  Optional<GateInstance> findFirstByCaseInstanceIdAndStatusOrderByDecidedAtDesc(
      UUID caseInstanceId, String status);

  /**
   * Tasks.md T016/T017: {@code studio.PublishService.publish} requires EVERY gate opened against a
   * draft's {@code (DEFINITION_VERSION, draftId)} subject to be {@code APPROVED} — one gate for an
   * ordinary publish (the D4 catalog-owner gate), two for a MAJOR-version bump (D4 plus the
   * architecture-board gate, T017). {@code subjectType} is passed as the raw enum-name string
   * (matching how {@link GateInstance#subjectType} is actually persisted) rather than the enum
   * itself, since Spring Data derives this query straight off the entity's String-typed field.
   */
  List<GateInstance> findBySubjectTypeAndSubjectId(String subjectType, UUID subjectId);
}
