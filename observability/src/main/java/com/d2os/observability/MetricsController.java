package com.d2os.observability;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** KPI read API + minimal dashboard (T055/T056, contracts/api.yaml — metrics tag). */
@RestController
public class MetricsController {

  private final KpiSampleRepository repository;
  private final JdbcTemplate jdbcTemplate;

  public MetricsController(KpiSampleRepository repository, JdbcTemplate jdbcTemplate) {
    this.repository = repository;
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Extended Phase 2 dashboard payload (T038, §KP): adds gate cycle time and regeneration rate to
   * the Phase 1 metrics. Both are derived from source-of-truth rows (progress_event,
   * operation_execution) rather than counters, so they stay rebuildable (Principle III).
   * Workspace-scoped via RLS.
   */
  @GetMapping("/api/v1/metrics/dashboard")
  public DashboardV2 dashboard2() {
    double firstPass = avgMetric(KpiEmitter.RUBRIC_FIRST_PASS_RATE);
    double completeness = avgMetric(KpiEmitter.PACKAGE_COMPLETENESS);
    // Revise generations beyond the first, per persona invocation. Exclude evaluation=true rows:
    // knowledge-influence paired runs (Phase 3, V14) are with/without probes that never feed
    // delivery,
    // so they must not dilute the real regeneration rate (they would inflate the denominator).
    Double regen =
        jdbcTemplate.queryForObject(
            "SELECT coalesce(count(*) FILTER (WHERE attempt_no > 1)::float "
                + "/ nullif(count(DISTINCT persona_invocation_id), 0), 0) "
                + "FROM operation_execution WHERE evaluation = false",
            Double.class);
    // Gate cycle time = seconds from an ESCALATED progress event to the next event for that case.
    Map<String, Object> gate =
        jdbcTemplate.queryForMap(
            """
                WITH ev AS (
                  SELECT case_id, kind, created_at,
                         lead(created_at) OVER (PARTITION BY case_id ORDER BY id) AS nxt
                  FROM progress_event)
                SELECT coalesce(percentile_cont(0.5) WITHIN GROUP (
                         ORDER BY extract(epoch FROM (nxt - created_at))), 0) AS p50,
                       coalesce(percentile_cont(0.95) WITHIN GROUP (
                         ORDER BY extract(epoch FROM (nxt - created_at))), 0) AS p95
                FROM ev WHERE kind = 'ESCALATED' AND nxt IS NOT NULL
                """);
    return new DashboardV2(
        firstPass,
        completeness,
        regen == null ? 0.0 : regen,
        ((Number) gate.get("p50")).doubleValue(),
        ((Number) gate.get("p95")).doubleValue());
  }

  private double avgMetric(String metric) {
    Double v =
        jdbcTemplate.queryForObject(
            "SELECT coalesce(avg(value), 0) FROM kpi_sample WHERE metric = ?",
            Double.class,
            metric);
    return v == null ? 0.0 : v;
  }

  @GetMapping("/api/v1/metrics/kpis")
  public List<KpiView> kpis(@RequestParam String metric) {
    return repository.findByMetricOrderByAtAsc(metric).stream()
        .map(
            s ->
                new KpiView(
                    s.getMetric(), s.getCaseInstanceId(), s.getValue(), s.getAt().toString()))
        .toList();
  }

  /** Minimal self-contained dashboard (T056) — fetches the three KPI series client-side. */
  @GetMapping(value = "/dashboard", produces = MediaType.TEXT_HTML_VALUE)
  public ResponseEntity<String> dashboard() {
    String html =
        """
                <!doctype html><html><head><title>D2OS KPIs</title></head><body>
                <h1>D2OS Phase 1 KPIs</h1>
                <div id="rubric_first_pass_rate"></div>
                <div id="package_completeness"></div>
                <div id="case_cost_tokens"></div>
                <script>
                  const wsId = new URLSearchParams(location.search).get('ws') || '';
                  ['rubric_first_pass_rate','package_completeness','case_cost_tokens'].forEach(m => {
                    fetch('/api/v1/metrics/kpis?metric=' + m, {headers: {'X-Workspace-Id': wsId}})
                      .then(r => r.json())
                      .then(rows => {
                        const avg = rows.length ? (rows.reduce((a,b)=>a+b.value,0)/rows.length).toFixed(3) : 'n/a';
                        document.getElementById(m).textContent = m + ': ' + avg + ' (' + rows.length + ' samples)';
                      });
                  });
                </script>
                </body></html>
                """;
    return ResponseEntity.ok(html);
  }

  public record KpiView(String metric, UUID caseId, double value, String at) {}

  public record DashboardV2(
      double firstPassValidationRate,
      double packageCompleteness,
      double regenerationRate,
      double gateCycleTimeP50Seconds,
      double gateCycleTimeP95Seconds) {}
}
