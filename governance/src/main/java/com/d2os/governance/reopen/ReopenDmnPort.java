package com.d2os.governance.reopen;

/**
 * SPI {@code ReopenCandidateService} (T025) uses to evaluate the {@code reopenDirectDependents}
 * decision table without a compile-time Flowable-DMN dependency — same seam as {@code
 * EngineGateReleasePort}: the interface lives here in {@code governance} so this module stays
 * engine-free, and {@code orchestration} (which already carries the Flowable-DMN engine for every
 * other DMN in this codebase) supplies the implementation, wired purely via Spring DI.
 */
public interface ReopenDmnPort {

    /** True if a trace_link edge of {@code edgeKind} (e.g. {@code DERIVES_FROM}) triggers a reopen. */
    boolean triggersReopen(String edgeKind);
}
