package com.d2os.persona;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates one persona output attempt (T033, T050, FR-005): a structural check, weighted rubric
 * scoring from the pinned RubricDefinition's criteria, and the injection-symptom gate (T1-b) — any
 * one of which can produce a critical failure. Passes only when the weighted score is ≥80% AND no
 * criterion (including the injection gate) is a critical failure.
 *
 * <p><b>Known v1 limitation:</b> rubric scoring here is a length/structure heuristic, not an
 * AI-judge semantic scorer — building a real judge requires its own AI Gateway call + prompt, which
 * is future work once the Gateway is exercised against a live provider. The pass/fail contract
 * (weighted ≥80%, no critical fail) is real and enforced; only the per-criterion scoring function
 * is a placeholder.
 */
@Component
public class ValidationPipeline {

    private static final int MIN_STRUCTURAL_LENGTH = 20;
    private static final int QUALITY_LENGTH_TARGET = 300;

    private final InjectionSymptomCheck injectionSymptomCheck;
    private final ObjectMapper objectMapper;

    public ValidationPipeline(InjectionSymptomCheck injectionSymptomCheck, ObjectMapper objectMapper) {
        this.injectionSymptomCheck = injectionSymptomCheck;
        this.objectMapper = objectMapper;
    }

    public ValidationResult validate(String output, String rubricJson) {
        List<String> criticalFailures = new ArrayList<>();

        if (!injectionSymptomCheck.isClean(output)) {
            criticalFailures.add("injection_symptom_detected");
        }

        double weightedScore = 0.0;
        JsonNode criteria = readCriteria(rubricJson);
        for (JsonNode criterion : criteria) {
            String name = criterion.path("name").asText();
            double weight = criterion.path("weight").asDouble(0.0);
            boolean critical = criterion.path("critical").asBoolean(false);
            double score = scoreCriterion(name, output);

            if (critical && score < 1.0) {
                criticalFailures.add(name);
            }
            weightedScore += weight * score;
        }

        return ValidationResult.of(weightedScore, criticalFailures);
    }

    private double scoreCriterion(String name, String output) {
        int length = output == null ? 0 : output.length();
        return switch (name) {
            case "structural_completeness" -> length >= MIN_STRUCTURAL_LENGTH ? 1.0 : 0.0;
            case "content_quality" -> Math.min(1.0, length / (double) QUALITY_LENGTH_TARGET);
            default -> length > 0 ? 1.0 : 0.0;
        };
    }

    private JsonNode readCriteria(String rubricJson) {
        try {
            return objectMapper.readTree(rubricJson).path("criteria");
        } catch (Exception e) {
            return objectMapper.createArrayNode();
        }
    }
}
