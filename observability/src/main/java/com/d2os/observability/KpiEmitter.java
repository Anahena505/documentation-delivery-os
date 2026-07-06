package com.d2os.observability;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Records the three Phase-1 KPIs (T054, FR-015, §KP). Emit points live in the pipeline:
 * {@code rubric_first_pass_rate} from persona validation, {@code package_completeness} and
 * {@code case_cost_tokens} from package assembly. Kept as a thin write API so any pipeline stage
 * can emit without knowing the storage shape.
 */
@Component
public class KpiEmitter {

    public static final String RUBRIC_FIRST_PASS_RATE = "rubric_first_pass_rate";
    public static final String PACKAGE_COMPLETENESS = "package_completeness";
    public static final String CASE_COST_TOKENS = "case_cost_tokens";

    private final KpiSampleRepository repository;

    public KpiEmitter(KpiSampleRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void emit(UUID workspaceId, String metric, UUID caseId, double value) {
        repository.save(new KpiSample(UUID.randomUUID(), workspaceId, metric, caseId, value));
    }
}
