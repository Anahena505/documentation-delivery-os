package com.d2os.persona;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Validates one persona output attempt (T033, T066, T050, FR-005): a structural check, weighted
 * rubric scoring from the pinned RubricDefinition's criteria, and the injection-symptom gate (T1-b)
 * — any one of which can produce a critical failure. Passes only when the weighted score is ≥80%
 * AND no criterion (including the injection gate) is a critical failure.
 *
 * <p><b>T066 scoring design (accepted approach, not an AI-judge):</b> FR-005 sanctions "AI-judge OR
 * equivalent measurable rubric scoring." A live AI-judge is deliberately NOT used: this environment
 * has no live provider wired ({@code StubAiGatewayClient} is the only Gateway in every test), so a
 * judge call would score synthetic, deterministic filler text — no real signal — while adding a
 * second Gateway call per attempt (cost/latency) and its own reproducibility snapshot (Principle
 * II) that nothing here yet needs. Instead, {@code structural_completeness} and {@code
 * content_quality} — the two criteria every seeded Phase 1/2 rubric uses — are scored from real,
 * deterministic multi-signal analysis (sentence structure, degenerate-content detection, vocabulary
 * diversity), not merely character count: a heuristic based on length alone would (and previously
 * did) pass garbage like 20 repeated characters just for being "long enough." Deterministic scoring
 * also keeps the dozens of existing integration tests — which all depend on {@code
 * StubAiGatewayClient}'s fixed output passing validation — reproducible; an AI-judge would risk
 * flaking them. Any other rubric criterion name (e.g. Phase 3's {@code sensitivity_excluded}) keeps
 * its original non-empty-output fallback, unchanged by this task's scope.
 */
@Component
public class ValidationPipeline {

  private static final int MIN_STRUCTURAL_LENGTH = 20;
  private static final int QUALITY_LENGTH_TARGET = 300;

  /** Sentence count at which the content-quality sentence factor reaches full credit. */
  private static final int TARGET_SENTENCE_COUNT = 3;

  /**
   * Below this distinct-non-whitespace-character ratio, content is treated as degenerate filler.
   */
  private static final double DEGENERATE_CHAR_DIVERSITY_FLOOR = 0.03;

  private static final double LENGTH_WEIGHT = 0.4;
  private static final double SENTENCE_WEIGHT = 0.3;
  private static final double DIVERSITY_WEIGHT = 0.3;

  private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("[.!?]");
  private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");

  private final InjectionSymptomCheck injectionSymptomCheck;
  private final ObjectMapper objectMapper;

  public ValidationPipeline(
      InjectionSymptomCheck injectionSymptomCheck, ObjectMapper objectMapper) {
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
    return switch (name) {
      case "structural_completeness" -> scoreStructuralCompleteness(output);
      case "content_quality" -> scoreContentQuality(output);
        // Criteria outside T066's scope (e.g. Phase 3's sensitivity_excluded) keep the original
        // non-empty-output fallback — unchanged from before this task.
      default -> (output != null && !output.isBlank()) ? 1.0 : 0.0;
    };
  }

  /**
   * Real structural signal, not length alone: adequate length AND at least one recognizable
   * sentence AND not degenerate filler (e.g. one character repeated to hit a length floor — the old
   * pure-length heuristic would have wrongly passed that).
   */
  private double scoreStructuralCompleteness(String output) {
    String trimmed = output == null ? "" : output.trim();
    if (trimmed.isEmpty()) {
      return 0.0;
    }
    boolean lengthOk = trimmed.length() >= MIN_STRUCTURAL_LENGTH;
    boolean hasSentenceStructure = countSentences(trimmed) >= 1;
    boolean notDegenerate = !isDegenerate(trimmed);
    return (lengthOk && hasSentenceStructure && notDegenerate) ? 1.0 : 0.0;
  }

  /**
   * Blends three independent, deterministic signals so no single one dominates: length adequacy
   * (the old heuristic's only signal), sentence-count adequacy (real multi-sentence structure, not
   * just a long blob), and vocabulary diversity (distinct/total word ratio — repetitive filler
   * scores low here even when it is long and well-punctuated).
   */
  private double scoreContentQuality(String output) {
    String trimmed = output == null ? "" : output.trim();
    if (trimmed.isEmpty()) {
      return 0.0;
    }
    double lengthFactor = Math.min(1.0, trimmed.length() / (double) QUALITY_LENGTH_TARGET);
    double sentenceFactor = Math.min(1.0, countSentences(trimmed) / (double) TARGET_SENTENCE_COUNT);
    double diversityFactor = distinctWordRatio(trimmed);
    return LENGTH_WEIGHT * lengthFactor
        + SENTENCE_WEIGHT * sentenceFactor
        + DIVERSITY_WEIGHT * diversityFactor;
  }

  private int countSentences(String text) {
    return (int) SENTENCE_BOUNDARY.matcher(text).results().count();
  }

  /** Distinct-word / total-word ratio (case-insensitive), 0.0 for content with no word tokens. */
  private double distinctWordRatio(String text) {
    List<String> words = new ArrayList<>();
    var matcher = WORD.matcher(text.toLowerCase(Locale.ROOT));
    while (matcher.find()) {
      words.add(matcher.group());
    }
    if (words.isEmpty()) {
      return 0.0;
    }
    Set<String> distinct = new HashSet<>(words);
    return distinct.size() / (double) words.size();
  }

  /**
   * True when non-whitespace content is overwhelmingly a handful of repeated characters (e.g.
   * "aaaaaaaaaa...") — content long enough to clear a naive length floor while carrying no real
   * information. Genuine prose, even short, clears this easily (dozens of distinct characters).
   */
  private boolean isDegenerate(String text) {
    String nonWhitespace = text.replaceAll("\\s+", "");
    if (nonWhitespace.isEmpty()) {
      return true;
    }
    Set<Character> distinctChars = new HashSet<>();
    for (char c : nonWhitespace.toCharArray()) {
      distinctChars.add(Character.toLowerCase(c));
    }
    return (distinctChars.size() / (double) nonWhitespace.length())
        < DEGENERATE_CHAR_DIVERSITY_FLOOR;
  }

  private JsonNode readCriteria(String rubricJson) {
    try {
      return objectMapper.readTree(rubricJson).path("criteria");
    } catch (Exception e) {
      return objectMapper.createArrayNode();
    }
  }
}
