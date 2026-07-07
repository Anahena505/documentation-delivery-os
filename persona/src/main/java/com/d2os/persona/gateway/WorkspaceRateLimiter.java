package com.d2os.persona.gateway;

import com.d2os.tenancy.WorkspaceContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Per-workspace request-rate smoothing in front of provider calls (T037, T5-b). A sliding one-minute
 * window per workspace: when a workspace exceeds its requests-per-minute limit, the caller is paced
 * (a short block) rather than failed — the engine job pool still holds the Case, so nothing is
 * dropped, just slowed. In-memory is appropriate for the single deployable (PD-1); the interface
 * isolates it so a distributed limiter could replace it if the monolith is ever split.
 *
 * <p>Only the real {@link HttpAiGatewayClient} consults this — integration tests use the stub gateway,
 * so provider pacing never interferes with test timing.
 */
@Component
public class WorkspaceRateLimiter {

    private static final long WINDOW_MILLIS = 60_000;

    private final int defaultPerMinute;
    private final Map<UUID, ConcurrentLinkedDeque<Long>> windows = new ConcurrentHashMap<>();

    public WorkspaceRateLimiter(@Value("${d2os.ai-gateway.default-rate-per-minute:600}") int defaultPerMinute) {
        this.defaultPerMinute = defaultPerMinute;
    }

    /** Records a request for the current workspace, pacing (blocking briefly) if over the limit. */
    public void acquire() {
        long nowMillis = System.currentTimeMillis();
        UUID workspaceId = WorkspaceContext.currentOrNull();
        if (workspaceId == null || defaultPerMinute <= 0) return;
        ConcurrentLinkedDeque<Long> window = windows.computeIfAbsent(workspaceId, k -> new ConcurrentLinkedDeque<>());
        prune(window, nowMillis);
        if (window.size() >= defaultPerMinute) {
            // Over the limit: pace by a small fixed delay, then record. Keeps throughput bounded
            // without failing the Case.
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        window.addLast(nowMillis);
    }

    private void prune(ConcurrentLinkedDeque<Long> window, long nowMillis) {
        Long head;
        while ((head = window.peekFirst()) != null && nowMillis - head > WINDOW_MILLIS) {
            window.pollFirst();
        }
    }
}
