package com.d2os.intake;

import java.util.Map;
import org.flowable.dmn.api.DmnDecisionService;
import org.springframework.stereotype.Service;

/**
 * Phase 4 case-type classification (T010, US1, research R5, FR-002/004). Runs the {@code
 * caseTypeClassification} decision table (deployed from {@code dmn/case-type-classification.dmn},
 * T008) over structured submission attributes and proposes one of {@code INITIATION}, {@code
 * ASSESSMENT}, {@code ENHANCEMENT}, or {@code UNDETERMINED}. The proposal is advisory only (§9-D2
 * rules route) — a human confirm/override step (T011) is the authority of record for every
 * submission, including UNDETERMINED ones.
 *
 * <p>Follows {@link DmnClassificationService}'s resilience style exactly: the DMN table's UNIQUE
 * hit policy has no catch-all rule (see case-type-classification.dmn's header comment for why one
 * can't be added without violating UNIQUE), so "no rule matched" is the expected way ambiguous
 * submissions surface — never a thrown exception that fails the submission. Any {@link
 * RuntimeException} from the decision engine (not deployed, engine unavailable, no/multiple
 * matching rows) is caught here and mapped to the same {@code UNDETERMINED} outcome, so a
 * DMN/engine hiccup degrades to "needs human choice" rather than failing intake (FR-002 keeps a
 * human in the loop regardless).
 */
@Service
public class CaseTypeClassificationService {

  /**
   * Contract enum value (contracts/api.yaml CaseTypeClassification#proposedCaseType) for no-match.
   */
  public static final String UNDETERMINED = "UNDETERMINED";

  private static final String DECISION_KEY = "caseTypeClassification";

  private final DmnDecisionService dmnDecisionService;

  public CaseTypeClassificationService(DmnDecisionService dmnDecisionService) {
    this.dmnDecisionService = dmnDecisionService;
  }

  /**
   * Classify a submission's structured form data into a proposed case type. Reads three fields off
   * the (opaque, never-instructions, AD-12) form data: {@code subjectExists} (boolean), {@code
   * hasDeliveredBaseline} (boolean), {@code requestIntent} (string, e.g. {@code "new"} / {@code
   * "evaluate"} / {@code "change"}) — mapped onto the DMN's {@code subject_exists} / {@code
   * has_delivered_baseline} / {@code request_intent} inputs. Missing fields default to {@code
   * false} / empty, which (by design, per the DMN's rule set) yields UNDETERMINED unless the "no
   * subject" Initiation rule matches.
   */
  public String classify(Map<String, Object> formData) {
    boolean subjectExists = truthy(formData.get("subjectExists"));
    boolean hasDeliveredBaseline = truthy(formData.get("hasDeliveredBaseline"));
    String requestIntent = String.valueOf(formData.getOrDefault("requestIntent", ""));

    Map<String, Object> outputs;
    try {
      outputs =
          dmnDecisionService
              .createExecuteDecisionBuilder()
              .decisionKey(DECISION_KEY)
              .variable("subject_exists", subjectExists)
              .variable("has_delivered_baseline", hasDeliveredBaseline)
              .variable("request_intent", requestIntent)
              .executeWithSingleResult();
    } catch (RuntimeException e) {
      // No rule matched (ambiguous combination), the decision isn't deployed yet, or the engine
      // is unavailable — all resolve to UNDETERMINED rather than failing the submission.
      return UNDETERMINED;
    }

    if (outputs == null || outputs.get("caseType") == null) {
      return UNDETERMINED;
    }
    return String.valueOf(outputs.get("caseType"));
  }

  private boolean truthy(Object value) {
    if (value instanceof Boolean b) {
      return b;
    }
    return Boolean.parseBoolean(String.valueOf(value));
  }
}
