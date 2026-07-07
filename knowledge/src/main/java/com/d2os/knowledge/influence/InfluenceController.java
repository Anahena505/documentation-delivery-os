package com.d2os.knowledge.influence;

import com.d2os.knowledge.KnowledgeItem;
import com.d2os.knowledge.KnowledgeItemRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * The knowledge-influence surface (US4, T034, contracts/api.yaml): trigger a paired influence evaluation
 * and read the resulting KPI. The read NEVER fabricates — an item that was never evaluated returns
 * {@code NOT_YET_MEASURABLE} with no value (FR-018).
 */
@RestController
public class InfluenceController {

    // dimensions->>'version' extracts the JSON number as text, so the (key,version) filter binds version
    // as a string. Ordered by time so the caller sees the full measured series and the latest delta.
    private static final String SAMPLES_SQL = """
            SELECT value, at
              FROM kpi_sample
             WHERE metric = 'knowledge_influence'
               AND dimensions->>'key' = ?
               AND dimensions->>'version' = ?
             ORDER BY at
            """;

    private final InfluenceEvaluationService influenceEvaluationService;
    private final KnowledgeItemRepository itemRepository;
    private final JdbcTemplate jdbcTemplate;

    public InfluenceController(InfluenceEvaluationService influenceEvaluationService,
                               KnowledgeItemRepository itemRepository,
                               JdbcTemplate jdbcTemplate) {
        this.influenceEvaluationService = influenceEvaluationService;
        this.itemRepository = itemRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Trigger a paired with/without influence evaluation of a KnowledgeItem against a case's pinned
     * rubric for the given persona. Returns 202 Accepted with the measured result (the evaluation runs
     * synchronously against the deterministic gateway; 202 reflects that this is a diagnostic side-run,
     * not a delivery operation). 404 if the item or case is unknown.
     */
    @PostMapping("/api/v1/knowledge/items/{id}/influence-evaluations")
    public ResponseEntity<InfluenceEvaluationService.InfluenceResult> evaluate(
            @PathVariable UUID id, @RequestBody EvaluationRequest request) {
        try {
            InfluenceEvaluationService.InfluenceResult result =
                    influenceEvaluationService.evaluate(id, request.caseId(), request.personaKey());
            return ResponseEntity.accepted().body(result);
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Read an item version's influence KPI. {@code MEASURED} with the sample series + latest delta when
     * at least one paired evaluation has run; {@code NOT_YET_MEASURABLE} (no value) for a never-evaluated
     * item — never fabricated (FR-018). Accepts either {@code itemId} or explicit {@code key}+{@code version}.
     */
    @GetMapping("/api/v1/metrics/knowledge-influence")
    public ResponseEntity<InfluenceMetric> metric(@RequestParam(required = false) UUID itemId,
                                                  @RequestParam(required = false) String key,
                                                  @RequestParam(required = false) Integer version) {
        String resolvedKey = key;
        Integer resolvedVersion = version;
        if (itemId != null) {
            Optional<KnowledgeItem> item = itemRepository.findById(itemId);
            if (item.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            resolvedKey = item.get().getKey();
            resolvedVersion = item.get().getVersion();
        }
        if (resolvedKey == null || resolvedVersion == null) {
            return ResponseEntity.badRequest().build();
        }

        List<Sample> samples = jdbcTemplate.query(
                SAMPLES_SQL,
                (rs, rowNum) -> new Sample(rs.getDouble("value"), rs.getObject("at").toString()),
                resolvedKey, String.valueOf(resolvedVersion));

        if (samples.isEmpty()) {
            return ResponseEntity.ok(new InfluenceMetric(
                    "NOT_YET_MEASURABLE", resolvedKey, resolvedVersion, null, List.of()));
        }
        double latestDelta = samples.get(samples.size() - 1).value();
        return ResponseEntity.ok(new InfluenceMetric(
                "MEASURED", resolvedKey, resolvedVersion, latestDelta, samples));
    }

    // ---- DTOs ------------------------------------------------------------------------------------

    public record EvaluationRequest(UUID caseId, String personaKey) {}

    public record Sample(double value, String at) {}

    public record InfluenceMetric(String status, String key, int version, Double latestDelta,
                                  List<Sample> samples) {}
}
