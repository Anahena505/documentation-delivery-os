package com.d2os.persona;

import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Heuristic output check for prompt-injection symptoms (T050, T1-b, AD-12). Submission text is
 * always treated as data (never instructions); this check catches the failure mode where a
 * persona's output nonetheless shows signs that embedded instructions were followed — e.g. the
 * model echoing meta-instructions, disclosing its system prompt, or claiming a role change.
 *
 * <p>A heuristic marker list is a deliberately simple, auditable v1 defense — not a claim of
 * completeness against all injection techniques. It runs as one stage of {@link
 * ValidationPipeline}.
 */
@Component
public class InjectionSymptomCheck {

  private static final List<String> SYMPTOM_MARKERS =
      List.of(
          "ignore previous instructions",
          "ignore all prior instructions",
          "disregard the above",
          "as an ai with no restrictions",
          "i am now in developer mode",
          "system prompt:",
          "my instructions were");

  /**
   * @return true if the output shows no injection symptoms (i.e. it is clean).
   */
  public boolean isClean(String output) {
    String lower = output == null ? "" : output.toLowerCase(Locale.ROOT);
    return SYMPTOM_MARKERS.stream().noneMatch(lower::contains);
  }
}
