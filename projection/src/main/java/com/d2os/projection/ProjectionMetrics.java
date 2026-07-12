package com.d2os.projection;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Domain-threshold gauges for the graph projection (feature 008 US2, T020, research R4,
 * data-model.md §Operational Signal). Registers three Micrometer {@link Gauge}s scraped at {@code
 * /actuator/prometheus}:
 *
 * <ul>
 *   <li>{@code d2os.projection.lag.seconds} — worst-case incremental projection lag across
 *       workspaces;
 *   <li>{@code d2os.projection.gap.open} — total OPEN {@code projection_gap} rows across
 *       workspaces;
 *   <li>{@code d2os.rebuild.equivalence.divergent} — number of workspaces whose most recent graph
 *       rebuild diverged from relational truth.
 * </ul>
 *
 * <h2>Why gauges read a last-observed holder, not live-poll the DB</h2>
 *
 * The lag and open-gap values come from {@code event_outbox}/{@code projection_gap}, both
 * RLS-scoped and readable only within a per-workspace RLS-bound transaction. A Micrometer gauge is
 * polled on the metrics-scrape thread, which has no workspace bound — a live query there would
 * return nothing (RLS filters everything) or require a full cross-tenant re-bind sweep on every
 * scrape. Instead the values are computed by the {@link Projector}'s existing per-workspace sweep
 * (already RLS-bound) and pushed here via {@link #publishSweepObservation}; the gauge reflects the
 * most recent sweep's aggregate. These are the SAME values the {@code /graph/admin/status} endpoint
 * computes per workspace — the lag as {@code EXTRACT(EPOCH FROM now() - MIN(created_at))} over the
 * unconsumed outbox tail and the count of {@code status='OPEN'} gap rows — aggregated to a
 * worst-case/total across the swept workspaces. The divergence gauge is a live set the {@link
 * RebuildJob} maintains per workspace (add on FAIL, remove on PASS), so it needs no polling.
 */
@Component
public class ProjectionMetrics {

  /** Worst-case projection lag (seconds) seen in the last projector sweep. */
  private volatile double lagSeconds = 0.0;

  /** Total OPEN projection_gap rows seen in the last projector sweep. */
  private volatile long openGaps = 0L;

  /** Workspaces whose most recent rebuild diverged (gauge value = set size). */
  private final Set<UUID> divergentWorkspaces = ConcurrentHashMap.newKeySet();

  public ProjectionMetrics(MeterRegistry registry) {
    Gauge.builder("d2os.projection.lag.seconds", this, m -> m.lagSeconds)
        .description(
            "Worst-case incremental projection lag in seconds across workspaces, "
                + "as of the last projector sweep")
        .register(registry);
    Gauge.builder("d2os.projection.gap.open", this, m -> (double) m.openGaps)
        .description(
            "Total OPEN projection_gap rows across workspaces, as of the last projector sweep")
        .register(registry);
    Gauge.builder("d2os.rebuild.equivalence.divergent", divergentWorkspaces, Set::size)
        .description(
            "Number of workspaces whose most recent graph rebuild diverged from relational truth")
        .register(registry);
  }

  /** Called by the {@link Projector} at the end of each sweep with the aggregate it observed. */
  public void publishSweepObservation(double maxLagSeconds, long totalOpenGaps) {
    this.lagSeconds = maxLagSeconds;
    this.openGaps = totalOpenGaps;
  }

  /**
   * Called by the {@link RebuildJob} after each rebuild: divergent = the equivalence check did not
   * pass.
   */
  public void recordRebuildResult(UUID workspaceId, boolean divergent) {
    if (divergent) {
      divergentWorkspaces.add(workspaceId);
    } else {
      divergentWorkspaces.remove(workspaceId);
    }
  }
}
