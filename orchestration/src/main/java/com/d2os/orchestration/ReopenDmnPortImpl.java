package com.d2os.orchestration;

import com.d2os.governance.reopen.ReopenDmnPort;
import org.flowable.dmn.api.DmnDecisionService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Orchestration-side implementation of {@code ReopenDmnPort} (T025): evaluates {@code
 * dmn/reopen-direct-dependents.dmn} (T024) — same resilience convention as every other DMN evaluator
 * in this codebase (e.g. {@code CaseTypeClassificationService}): an unmatched row, undeployed
 * decision, or engine hiccup degrades to "does not trigger" rather than failing the caller.
 */
@Component
public class ReopenDmnPortImpl implements ReopenDmnPort {

    private static final String DECISION_KEY = "reopenDirectDependents";

    private final DmnDecisionService dmnDecisionService;

    public ReopenDmnPortImpl(DmnDecisionService dmnDecisionService) {
        this.dmnDecisionService = dmnDecisionService;
    }

    @Override
    public boolean triggersReopen(String edgeKind) {
        List<Map<String, Object>> rows;
        try {
            rows = dmnDecisionService.createExecuteDecisionBuilder()
                    .decisionKey(DECISION_KEY)
                    .variable("edge_kind", edgeKind)
                    .executeDecision();
        } catch (RuntimeException e) {
            return false;
        }
        return rows != null && rows.stream().anyMatch(r -> Boolean.TRUE.equals(r.get("triggersReopen")));
    }
}
