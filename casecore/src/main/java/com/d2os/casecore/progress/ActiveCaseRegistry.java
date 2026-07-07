package com.d2os.casecore.progress;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

/**
 * In-memory set of Cases currently in the Running state, with their workspace (T034). The heartbeat
 * scheduler reads this to emit liveness events for the whole active lifetime of a case — including
 * the brief queue waits between persona steps under load — so a running Case never appears frozen
 * (FR-011, ≤5s). {@link CaseService} registers a Case on entry to Running and deregisters it on any
 * non-Running transition (Delivered/Escalated/Suspended/Cancelled), since a parked Case is not
 * executing an operation and needs no heartbeat.
 *
 * <p>In-memory is correct here: the registry is pure transient liveness for a single deployable
 * (PD-1); it is rebuilt naturally as cases transition, and a restart simply resumes emitting once the
 * async jobs re-acquire. Avoiding a cross-tenant DB scan also sidesteps RLS on the scheduler thread.
 */
@Component
public class ActiveCaseRegistry {

    private final Map<UUID, UUID> runningCaseToWorkspace = new ConcurrentHashMap<>();

    public void markRunning(UUID caseId, UUID workspaceId) {
        runningCaseToWorkspace.put(caseId, workspaceId);
    }

    public void clear(UUID caseId) {
        runningCaseToWorkspace.remove(caseId);
    }

    /** Snapshot of currently-running (caseId → workspaceId) for the heartbeat sweep. */
    public Map<UUID, UUID> snapshot() {
        return Map.copyOf(runningCaseToWorkspace);
    }
}
