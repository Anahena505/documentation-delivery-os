package com.d2os.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Per-job USE metrics for the 9 {@code @Scheduled} sweeps (feature 008 US2, T018, research R4,
 * data-model.md §Operational Signal). Wraps a Micrometer {@link MeterRegistry} and exposes three
 * meter families tagged by {@code job}:
 *
 * <ul>
 *   <li>{@code d2os.job.executions{job}} — a counter incremented once per run;
 *   <li>{@code d2os.job.duration{job}} — a timer of each run's wall-clock latency;
 *   <li>{@code d2os.job.failures{job}} — a counter incremented when a run throws.
 * </ul>
 *
 * <p>Kept as a thin, constructor-injected write API (mirroring {@link KpiEmitter} in this module)
 * so any scheduled job can time itself without knowing about Micrometer's meter shapes.
 * Unit-instantiable with a {@code SimpleMeterRegistry} — no Spring context required.
 *
 * <p>Jobs in modules that already depend on {@code observability} (orchestration) inject this bean
 * directly; jobs in modules that cannot depend on {@code observability} without forming a Gradle
 * cycle (casecore, tenancy — {@code observability} depends on {@code casecore}) record the
 * identical meters inline against an injected {@code MeterRegistry} instead (T019).
 */
@Component
public class JobMetrics {

  public static final String EXECUTIONS = "d2os.job.executions";
  public static final String DURATION = "d2os.job.duration";
  public static final String FAILURES = "d2os.job.failures";

  private final MeterRegistry meterRegistry;

  public JobMetrics(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  /** Count one execution of {@code job}. */
  public void recordExecution(String job) {
    meterRegistry.counter(EXECUTIONS, "job", job).increment();
  }

  /** Count one failed run of {@code job}. */
  public void recordFailure(String job) {
    meterRegistry.counter(FAILURES, "job", job).increment();
  }

  /**
   * Run {@code work} as one execution of {@code job}: increments the execution counter, times the
   * run into the duration timer, and increments the failure counter if {@code work} throws (the
   * throwable is rethrown so the job's own error handling is unchanged).
   */
  public void time(String job, Runnable work) {
    recordExecution(job);
    Timer.Sample sample = Timer.start(meterRegistry);
    try {
      work.run();
    } catch (RuntimeException e) {
      recordFailure(job);
      throw e;
    } finally {
      sample.stop(meterRegistry.timer(DURATION, "job", job));
    }
  }
}
