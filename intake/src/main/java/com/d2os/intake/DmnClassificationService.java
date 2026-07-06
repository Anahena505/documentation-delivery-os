package com.d2os.intake;

import org.flowable.dmn.api.DmnDecisionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * DMN-backed classification (T024, AD-2). Evaluates the {@code submissionClassification} decision
 * table (deployed from {@code dmn/submission-classification.dmn}) over fields lifted from the
 * submission form. Confidence below the configured threshold flags the submission for a human
 * confirm step (FR-002).
 *
 * <p>Resilient by design: if the decision engine returns no row (e.g. an unmapped request type),
 * it falls back to the sole Phase-1 case type ({@code initiation}) at low confidence, which forces
 * human confirmation rather than silently guessing.
 */
@Service
public class DmnClassificationService implements ClassificationService {

    private static final String DECISION_KEY = "submissionClassification";
    private static final String DEFAULT_CASE_TYPE = "initiation";

    private final DmnDecisionService dmnDecisionService;
    private final double confidenceThreshold;

    public DmnClassificationService(DmnDecisionService dmnDecisionService,
                                    @Value("${d2os.classification.confidence-threshold:0.80}") double confidenceThreshold) {
        this.dmnDecisionService = dmnDecisionService;
        this.confidenceThreshold = confidenceThreshold;
    }

    @Override
    public ClassificationResult classify(Map<String, Object> formData) {
        String requestType = String.valueOf(formData.getOrDefault("category", "unknown"));

        Map<String, Object> outputs;
        try {
            outputs = dmnDecisionService.createExecuteDecisionBuilder()
                    .decisionKey(DECISION_KEY)
                    .variable("requestType", requestType)
                    .executeWithSingleResult();
        } catch (RuntimeException e) {
            // Decision not deployed / engine unavailable → fall back to human-confirm default
            // rather than fail the submission (FR-002 keeps a human in the loop regardless).
            return new ClassificationResult(DEFAULT_CASE_TYPE, 0.50, true);
        }

        if (outputs == null || outputs.get("caseType") == null) {
            return new ClassificationResult(DEFAULT_CASE_TYPE, 0.50, true);
        }

        String caseType = String.valueOf(outputs.get("caseType"));
        double confidence = outputs.get("confidence") instanceof Number n ? n.doubleValue() : 0.50;
        return new ClassificationResult(caseType, confidence, confidence < confidenceThreshold);
    }
}
