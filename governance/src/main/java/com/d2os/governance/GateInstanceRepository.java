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
}
