package com.d2os.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Records the KPIs (T054, FR-015, §KP; Phase 3 US4 adds {@code knowledge_influence}). Emit points live
 * in the pipeline: {@code rubric_first_pass_rate} from persona validation, {@code package_completeness}
 * and {@code case_cost_tokens} from package assembly, and {@code knowledge_influence} from the paired
 * influence evaluation. Kept as a thin write API so any stage can emit without knowing the storage shape.
 */
@Component
public class KpiEmitter {

    public static final String RUBRIC_FIRST_PASS_RATE = "rubric_first_pass_rate";
    public static final String PACKAGE_COMPLETENESS = "package_completeness";
    public static final String CASE_COST_TOKENS = "case_cost_tokens";
    public static final String KNOWLEDGE_INFLUENCE = "knowledge_influence";

    private final KpiSampleRepository repository;
    private final ObjectMapper objectMapper;

    public KpiEmitter(KpiSampleRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void emit(UUID workspaceId, String metric, UUID caseId, double value) {
        repository.save(new KpiSample(UUID.randomUUID(), workspaceId, metric, caseId, value));
    }

    /**
     * Emit a {@code knowledge_influence} sample (US4, FR-018): the rubric-score delta a KnowledgeItem
     * version contributed, attributed via {@code dimensions = {"key":..., "version":...}} on the reused
     * V9 {@code kpi_sample} stream. The delta is the measured with-minus-without value — never fabricated.
     */
    @Transactional
    public void emitKnowledgeInfluence(UUID workspaceId, UUID caseId, double delta, String key, int version) {
        Map<String, Object> dims = new LinkedHashMap<>();
        dims.put("key", key);
        dims.put("version", version);
        repository.save(new KpiSample(
                UUID.randomUUID(), workspaceId, KNOWLEDGE_INFLUENCE, caseId, delta, toJson(dims)));
    }

    private String toJson(Map<String, Object> dims) {
        try {
            return objectMapper.writeValueAsString(dims);
        } catch (Exception e) {
            throw new IllegalStateException("Unserializable kpi dimensions", e);
        }
    }
}
