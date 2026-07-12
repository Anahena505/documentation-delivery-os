package com.d2os.governance.escalation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.d2os.governance.escalation.EscalationPolicyResolver.Policy;
import com.d2os.governance.escalation.EscalationPolicyResolver.Step;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 008 T009 — fast, pure unit test of the {@link Policy} value object's step-resolution logic (its
 * {@code step(int)} stream lookup). No Spring, no DB, no {@code DefinitionResolutionService}: this
 * exercises exactly the branch {@code TimerFiredHandler} relies on when it does {@code
 * policy.step(stepIndex).map(...).orElse(default)}.
 *
 * <p>Cases: (1) when the policy defines the step, its configured duration wins; (2) when the policy
 * omits the step, the lookup is empty so the caller's configured default is used. The parsing of
 * the pinned {@code ESCALATION_POLICY} DefinitionAsset (which requires JDBC/Jackson) is
 * deliberately out of scope — the resolvable, pure decision is the step lookup tested here.
 */
class EscalationPolicyResolverTest {

  private static final String CONFIGURED_DEFAULT =
      "PT24H"; // d2os.governance.sla.default-durations fallback

  private static Policy policyWithStep0() {
    return new Policy(
        "escalation-policy.default",
        "1.0.0",
        List.of(new Step(0, "reviewer", "PT4H"), new Step(1, "lead", "PT8H")));
  }

  @Test
  void policyProvidedStepDuration_wins() {
    Policy policy = policyWithStep0();

    Optional<Step> step0 = policy.step(0);
    assertTrue(step0.isPresent(), "the policy defines step 0");
    // The policy-provided duration is used, not the configured default.
    String resolved = step0.map(Step::durationIso8601).orElse(CONFIGURED_DEFAULT);
    assertEquals("PT4H", resolved);
    assertEquals("reviewer", step0.get().role());
  }

  @Test
  void configuredDefault_usedWhenPolicyOmitsStep() {
    Policy policy = policyWithStep0(); // defines steps 0 and 1 only

    Optional<Step> missing = policy.step(5);
    assertTrue(missing.isEmpty(), "the policy does not define step 5");
    // With no policy step, the caller falls back to the configured default duration.
    String resolved = missing.map(Step::durationIso8601).orElse(CONFIGURED_DEFAULT);
    assertEquals(CONFIGURED_DEFAULT, resolved);
  }
}
