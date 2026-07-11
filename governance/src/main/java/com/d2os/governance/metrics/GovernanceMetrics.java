package com.d2os.governance.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Governance domain-threshold gauge (feature 008 US2, T020, research R4, data-model.md §Operational
 * Signal). Registers {@code d2os.gate.sla.breached} — the count of advisory gate-SLA escalation
 * firings observed since process start — scraped at {@code /actuator/prometheus} and alerted on
 * when {@code > 0} (deploy/prometheus/alert-rules.yml).
 *
 * <p>A gate breaches its advisory SLA when its non-interrupting boundary timer elapses and {@link
 * com.d2os.governance.escalation.TimerFiredHandler} records an {@code EscalationActivation}; that
 * handler calls {@link #recordSlaBreach()} at exactly that point. It is a cumulative gauge (an
 * {@link AtomicLong} that only grows over the process lifetime), which is the value an alert on
 * "any SLA breach" wants; Prometheus retains the series across restarts. governance stays
 * engine-agnostic — this component depends only on Micrometer, never on the orchestrator.
 */
@Component
public class GovernanceMetrics {

  private final AtomicLong slaBreaches = new AtomicLong(0);

  public GovernanceMetrics(MeterRegistry registry) {
    Gauge.builder("d2os.gate.sla.breached", slaBreaches, AtomicLong::doubleValue)
        .description("Cumulative count of advisory gate-SLA escalation firings observed")
        .register(registry);
  }

  /** Record one advisory SLA breach (an escalation firing on an OPEN gate). */
  public void recordSlaBreach() {
    slaBreaches.incrementAndGet();
  }
}
