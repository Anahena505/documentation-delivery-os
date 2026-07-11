package com.d2os.intake;

import com.d2os.casecore.spi.ConditionalArtifactPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flowable.dmn.api.DmnDecisionService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Phase 4 conditional-artifacts evaluation (T030-T032, US5, research R6, FR-014/015). Runs the
 * {@code conditionalArtifacts} decision table (deployed from {@code dmn/conditional-artifacts.dmn},
 * already registered by T002's classpath scan) — COLLECT hit policy, so every matching row accumulates
 * into the result rather than picking one winner (the classification DMN's UNIQUE-and-single-result
 * style doesn't apply here). Follows {@link CaseTypeClassificationService}'s resilience convention: a
 * DMN/engine hiccup (not deployed, no matching rows, engine unavailable) degrades to an empty list —
 * the case-type's BASE required-artifact set is unaffected — rather than failing case creation.
 */
@Service
public class ConditionalArtifactService implements ConditionalArtifactPort {

    private static final String DECISION_KEY = "conditionalArtifacts";

    private final DmnDecisionService dmnDecisionService;
    private final ObjectMapper objectMapper;

    public ConditionalArtifactService(DmnDecisionService dmnDecisionService, ObjectMapper objectMapper) {
        this.dmnDecisionService = dmnDecisionService;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<ConditionalArtifact> evaluate(String formDataJson) {
        boolean personalData = readPersonalDataFlag(formDataJson);

        List<Map<String, Object>> rows;
        try {
            rows = dmnDecisionService.createExecuteDecisionBuilder()
                    .decisionKey(DECISION_KEY)
                    .variable("personal_data", personalData)
                    .executeDecision();
        } catch (RuntimeException e) {
            return List.of();
        }

        if (rows == null) {
            return List.of();
        }
        List<ConditionalArtifact> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object templateKey = row.get("templateKey");
            Object artifactKind = row.get("artifactKind");
            if (templateKey == null || artifactKind == null) continue;
            result.add(new ConditionalArtifact(
                    String.valueOf(templateKey), String.valueOf(artifactKind),
                    String.valueOf(row.getOrDefault("reason", ""))));
        }
        return result;
    }

    /** {@code formData.personalData}, same opaque-JSON/never-instructions handling as elsewhere (AD-12). */
    private boolean readPersonalDataFlag(String formDataJson) {
        if (formDataJson == null || formDataJson.isBlank()) {
            return false;
        }
        try {
            JsonNode node = objectMapper.readTree(formDataJson).path("personalData");
            return node.asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }
}
