package com.d2os.casecore.progress;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Emits a HEARTBEAT progress event for every Running Case on a fixed cadence (T034, FR-011, NFR-3),
 * so a long AI call — which produces no natural boundary events — never makes a Case look frozen. The
 * interval is set below the 5 s bound (default 4 s). Reads the in-memory {@link ActiveCaseRegistry}
 * rather than scanning the DB, so it needs no cross-tenant query; each write goes through
 * {@link HeartbeatWriter} to get a transaction with the Case's workspace RLS context bound.
 */
@Component
public class ProgressHeartbeat {

    private final ActiveCaseRegistry activeCaseRegistry;
    private final HeartbeatWriter heartbeatWriter;

    public ProgressHeartbeat(ActiveCaseRegistry activeCaseRegistry, HeartbeatWriter heartbeatWriter) {
        this.activeCaseRegistry = activeCaseRegistry;
        this.heartbeatWriter = heartbeatWriter;
    }

    @Scheduled(fixedDelayString = "${d2os.casecore.progress.heartbeat-interval-ms:4000}",
               initialDelayString = "${d2os.casecore.progress.heartbeat-interval-ms:4000}")
    public void beat() {
        for (Map.Entry<UUID, UUID> e : activeCaseRegistry.snapshot().entrySet()) {
            try {
                heartbeatWriter.write(e.getValue(), e.getKey());
            } catch (Exception ignored) {
                // A transient failure for one case must not stop the sweep.
            }
        }
    }
}
