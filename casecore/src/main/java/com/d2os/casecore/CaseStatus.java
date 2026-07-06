package com.d2os.casecore;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Case lifecycle states and their permitted transitions (data-model.md §4, FR-003/005/012).
 *
 * <pre>
 * Submitted → Classified → Planned → Running ⇄ Waiting → Delivered
 *                                     Running → Suspended  (token budget breach, FR-012)
 *                                     Running → Escalated  (3rd generation failed, FR-005)
 *   Suspended/Escalated → Running     (explicit governance resume / escalation resolve)
 *   Escalated → Cancelled             (escalation resolved as cancel)
 * </pre>
 *
 * Snapshot pinning happens exactly on the {@code Classified → Planned} transition (FR-003).
 */
public enum CaseStatus {
    Submitted,
    Classified,
    Planned,
    Running,
    Waiting,
    Suspended,
    Escalated,
    Delivered,
    Cancelled;

    private static final Map<CaseStatus, Set<CaseStatus>> ALLOWED = Map.of(
        Submitted,  EnumSet.of(Classified, Cancelled),
        Classified, EnumSet.of(Planned, Cancelled),
        Planned,    EnumSet.of(Running, Cancelled),
        Running,    EnumSet.of(Waiting, Suspended, Escalated, Delivered),
        Waiting,    EnumSet.of(Running, Suspended, Cancelled),
        Suspended,  EnumSet.of(Running, Cancelled),
        Escalated,  EnumSet.of(Running, Cancelled),
        Delivered,  EnumSet.noneOf(CaseStatus.class),
        Cancelled,  EnumSet.noneOf(CaseStatus.class)
    );

    /** @return true if this → target is a legal transition. */
    public boolean canTransitionTo(CaseStatus target) {
        return ALLOWED.getOrDefault(this, EnumSet.noneOf(CaseStatus.class)).contains(target);
    }

    public boolean isTerminal() {
        return this == Delivered || this == Cancelled;
    }
}
