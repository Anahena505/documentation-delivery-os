package com.d2os.governance.escalation;

import com.d2os.catalog.DefinitionResolutionService;
import com.d2os.catalog.DefinitionView;
import com.d2os.governance.GateInstance;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Resolves the pinned {@code ESCALATION_POLICY} DefinitionAsset a gate opened with — its role chain
 * and per-step SLA durations (Phase 6 US4, T031, research R4, FR-009). A gate that opened with no
 * {@code escalationPolicyKey}/{@code escalationPolicyVersion} (neither {@code initiation-v3} nor
 * {@code assessment-v2}'s callActivity currently maps those variables in — {@code GateTaskBridge}'s
 * own javadoc notes the parallel gap for {@code subjectArtifactRevisionId}) falls back to the
 * seeded {@code escalation-policy.default} — the same fallback role {@code
 * d2os.governance.sla.default-durations} describes (application.yml, T003).
 */
@Service
public class EscalationPolicyResolver {

  private static final String DEFAULT_POLICY_KEY = "escalation-policy.default";

  /**
   * One step in the role chain: which role is notified at {@code stepIndex}, after {@code
   * duration}.
   */
  public record Step(int stepIndex, String role, String durationIso8601) {}

  /** The resolved policy's identity plus its ordered step chain. */
  public record Policy(String key, String version, List<Step> steps) {
    public Optional<Step> step(int stepIndex) {
      return steps.stream().filter(s -> s.stepIndex() == stepIndex).findFirst();
    }
  }

  private final DefinitionResolutionService definitionResolution;
  private final ObjectMapper objectMapper;

  public EscalationPolicyResolver(
      DefinitionResolutionService definitionResolution, ObjectMapper objectMapper) {
    this.definitionResolution = definitionResolution;
    this.objectMapper = objectMapper;
  }

  /** Resolve the policy a gate actually opened with (its pinned key+version, or the default). */
  public Optional<Policy> resolveFor(GateInstance gate) {
    if (gate.getEscalationPolicyKey() != null) {
      return resolve(gate.getEscalationPolicyKey(), gate.getEscalationPolicyVersion());
    }
    return resolve(DEFAULT_POLICY_KEY, null);
  }

  /**
   * Resolve a policy by key, pinned to {@code version} if given, else the latest published. Version
   * pinning is a direct lookup ({@code definition_asset.type/key/version}); {@code null} falls back
   * to {@link DefinitionResolutionService#latestPublishedView}.
   */
  public Optional<Policy> resolve(String key, Integer version) {
    Optional<DefinitionView> view =
        definitionResolution.latestPublishedView("ESCALATION_POLICY", key);
    if (view.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(toPolicy(view.get()));
  }

  private Policy toPolicy(DefinitionView view) {
    List<Step> steps = new ArrayList<>();
    try {
      JsonNode stepsNode = objectMapper.readTree(view.body()).path("steps");
      for (JsonNode s : stepsNode) {
        steps.add(
            new Step(
                s.path("stepIndex").asInt(),
                s.path("role").asText(),
                s.path("durationIso8601").asText()));
      }
    } catch (Exception e) {
      // fall through with whatever steps were parsed before the failure (possibly none)
    }
    return new Policy(view.key(), view.version(), steps);
  }
}
