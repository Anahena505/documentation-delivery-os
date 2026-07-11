package com.d2os.governance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
    Optional<GateInstance> findFirstByCaseInstanceIdAndStatusOrderByDecidedAtDesc(UUID caseInstanceId, String status);
}
