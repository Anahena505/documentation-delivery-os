package com.d2os.governance;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Gate lifecycle states and their permitted transitions (data-model.md GateInstance state machine,
 * Phase 5 US1/US2/US3).
 *
 * <pre>
 * OPEN ──APPROVE──▶ APPROVED ──(upstream revision → DMN candidate)──▶ REOPEN_CANDIDATE
 * OPEN ──REJECT──▶ REJECTED
 * OPEN ──REQUEST_CHANGES──▶ REGENERATING ──(new revision + delta)──▶ OPEN
 * REOPEN_CANDIDATE ──ReopenService.reopen (409 without impact_assessment)──▶ REOPENED ──▶ OPEN
 * </pre>
 *
 * {@code decide()} (Phase 3, T014, {@code GateService}) accepts only the three verbs
 * APPROVE/REJECT/REQUEST_CHANGES; the APPROVED → REOPEN_CANDIDATE edge is driven by
 * {@code ReopenCandidateService} (Phase 5, US3) identifying a direct dependent of a revised upstream
 * artifact, never a human decision. REJECTED is terminal — a rejected gate is never reopened.
 */
public enum GateStatus {
    OPEN,
    APPROVED,
    REJECTED,
    REGENERATING,
    REOPEN_CANDIDATE,
    REOPENED;

    private static final Map<GateStatus, Set<GateStatus>> ALLOWED = Map.of(
        OPEN,             EnumSet.of(APPROVED, REJECTED, REGENERATING),
        APPROVED,         EnumSet.of(REOPEN_CANDIDATE),
        REJECTED,         EnumSet.noneOf(GateStatus.class),
        REGENERATING,     EnumSet.of(OPEN),
        REOPEN_CANDIDATE, EnumSet.of(REOPENED),
        REOPENED,         EnumSet.of(OPEN)
    );

    /** @return true if this → target is a legal transition. */
    public boolean canTransitionTo(GateStatus target) {
        return ALLOWED.getOrDefault(this, EnumSet.noneOf(GateStatus.class)).contains(target);
    }

    public boolean isTerminal() {
        return this == REJECTED;
    }
}
