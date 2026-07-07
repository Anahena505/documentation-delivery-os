package com.d2os.knowledge.capture;

import java.util.UUID;

/**
 * SPI the {@code knowledge} module exposes so the D4 endpoint can release the parked
 * {@code knowledge-capture} process wait after committing the D4 decision — without {@code knowledge}
 * depending on Flowable/orchestration. The orchestration module provides the implementation
 * ({@code CaptureWaitReleaserImpl}, which triggers the {@code d4-review} receiveTask by the case id
 * business key). In slices with no orchestration bean on the path (services-only ITs) the endpoint uses
 * a no-op, so the authoritative decision still commits.
 */
public interface CaptureWaitReleaser {

    /**
     * Release the {@code knowledge-capture} instance parked at the D4 wait for {@code caseInstanceId},
     * signalling the recorded {@code approved} outcome so the process routes to its published/rejected
     * end. Best-effort/idempotent: if no instance is parked (e.g. a services-only flow), it does nothing.
     */
    void releaseD4Wait(UUID caseInstanceId, boolean approved);
}
