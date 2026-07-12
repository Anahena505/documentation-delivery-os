package com.d2os.persona;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Fast, plain-JUnit calibration test for the T066 rubric scorer. The only two criteria every seeded
 * Phase 1/2 rubric uses are {@code structural_completeness} (weight 0.5, critical) and {@code
 * content_quality} (weight 0.5, non-critical); this pins the scorer against the exact fixture text
 * every existing integration test depends on ({@code StubAiGatewayClient}'s default response and
 * the escalation fail-marker's "no"), so a future scoring change cannot silently regress ~30 ITs
 * without this test catching it fast (no Spring context, no containers).
 */
class ValidationPipelineTest {

  private static final String RUBRIC =
      """
            {"personaKey":"test-persona","criteria":[
              {"name":"structural_completeness","weight":0.5,"critical":true},
              {"name":"content_quality","weight":0.5,"critical":false}
            ]}""";

  private final ValidationPipeline pipeline =
      new ValidationPipeline(new InjectionSymptomCheck(), new ObjectMapper());

  @Test
  void stubGatewayDefaultOutputPasses() {
    // Exactly StubAiGatewayClient.LatencyControllableGateway's happy-path response body.
    String output =
        "This is a deterministic stub artifact produced for integration testing. ".repeat(6);
    ValidationResult result = pipeline.validate(output, RUBRIC);
    assertTrue(
        result.passed(),
        () ->
            "stub default output must pass (weighted="
                + result.weightedScore()
                + ", criticalFailures="
                + result.criticalFailures()
                + ")");
  }

  @Test
  void consistencyCheckFixtureTextPasses() {
    // ConsistencyCheckIT's seeded specialist body: shorter repeat count + a trailing attribute
    // line.
    String output =
        "Specialist analysis produced for the consistency integration test. ".repeat(3)
            + "\nattr: region=us-east";
    ValidationResult result = pipeline.validate(output, RUBRIC);
    assertTrue(
        result.passed(),
        () ->
            "consistency-check fixture text must pass (weighted="
                + result.weightedScore()
                + ", criticalFailures="
                + result.criticalFailures()
                + ")");
  }

  @Test
  void failMarkerShortOutputFailsStructurally() {
    // Exactly what LatencyControllableGateway.failFor(...) returns to force an escalation.
    ValidationResult result = pipeline.validate("no", RUBRIC);
    assertFalse(result.passed(), "a 2-character output must fail structural_completeness");
    assertTrue(result.criticalFailures().contains("structural_completeness"));
  }

  @Test
  void degenerateRepeatedCharacterFailsDespiteSufficientLength() {
    // The real gap T066 closes: the old length-only heuristic would have PASSED this (>= 20 chars).
    String garbage = "a".repeat(500);
    ValidationResult result = pipeline.validate(garbage, RUBRIC);
    assertFalse(result.passed(), "500 repeated characters must not pass as substantive content");
    assertTrue(
        result.criticalFailures().contains("structural_completeness"),
        "degenerate single-character filler must fail the structural criterion");
  }

  @Test
  void genuineVariedProseScoresWell() {
    String output =
        "The requester needs a documentation package covering scope, stakeholders, and acceptance "
            + "criteria for the initiative. This section captures the primary business goals. The next section "
            + "enumerates constraints, dependencies on other teams, and open risks that require follow-up before "
            + "delivery can be considered complete and ready for stakeholder review.";
    ValidationResult result = pipeline.validate(output, RUBRIC);
    assertTrue(
        result.passed(),
        () ->
            "varied genuine prose must pass comfortably (weighted=" + result.weightedScore() + ")");
    assertTrue(
        result.weightedScore() >= 0.9,
        "rich, diverse prose should score near the top of the range");
  }
}
