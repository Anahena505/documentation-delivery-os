package com.d2os.casecore.progress;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
    private final MeterRegistry meterRegistry;

    public ProgressHeartbeat(ActiveCaseRegistry activeCaseRegistry, HeartbeatWriter heartbeatWriter,
                             MeterRegistry meterRegistry) {
        this.activeCaseRegistry = activeCaseRegistry;
        this.heartbeatWriter = heartbeatWriter;
        this.meterRegistry = meterRegistry;
    }

    // US2 (T019): per-job USE metrics inline against MeterRegistry (casecore cannot depend on
    // observability — cycle). Meter names match observability's JobMetrics exactly.
    private static final String JOB = "progress-heartbeat";

    // US3 (T034): deliberately NOT @SchedulerLock'd. Unlike the other 8 scheduled jobs — each a single
    // global sweep over every workspace that must run once per cycle cluster-wide — this beat reads the
    // in-memory ActiveCaseRegistry, which holds only the Running cases hosted on THIS JVM. It is thus
    // naturally partitioned per instance: each node must beat its own cases every cycle. A single global
    // ShedLock name would let just one instance beat per window, starving cases on every other instance
    // of heartbeats and making them look frozen — the exact failure this job exists to prevent. A true
    // per-case lock cannot be expressed via the static @SchedulerLock annotation (its name is a
    // compile-time constant) and would add no value here, since two instances never host the same case's
    // in-memory registry entry. So no lock is correct (data-model.md §1: heartbeat is per-case/partitioned).
    @Scheduled(fixedDelayString = "${d2os.casecore.progress.heartbeat-interval-ms:4000}",
               initialDelayString = "${d2os.casecore.progress.heartbeat-interval-ms:4000}")
    public void beat() {
        meterRegistry.counter("d2os.job.executions", "job", JOB).increment();
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            for (Map.Entry<UUID, UUID> e : activeCaseRegistry.snapshot().entrySet()) {
                try {
                    heartbeatWriter.write(e.getValue(), e.getKey());
                } catch (Exception ignored) {
                    // A transient failure for one case must not stop the sweep.
                }
            }
        } catch (RuntimeException e) {
            meterRegistry.counter("d2os.job.failures", "job", JOB).increment();
            throw e;
        } finally {
            sample.stop(meterRegistry.timer("d2os.job.duration", "job", JOB));
        }
    }
}
