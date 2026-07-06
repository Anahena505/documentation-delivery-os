package com.d2os.observability;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** KPI read API + minimal dashboard (T055/T056, contracts/api.yaml — metrics tag). */
@RestController
public class MetricsController {

    private final KpiSampleRepository repository;

    public MetricsController(KpiSampleRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/api/v1/metrics/kpis")
    public List<KpiView> kpis(@RequestParam String metric) {
        return repository.findByMetricOrderByAtAsc(metric).stream()
                .map(s -> new KpiView(s.getMetric(), s.getCaseInstanceId(), s.getValue(), s.getAt().toString()))
                .toList();
    }

    /** Minimal self-contained dashboard (T056) — fetches the three KPI series client-side. */
    @GetMapping(value = "/dashboard", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> dashboard() {
        String html = """
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
}
