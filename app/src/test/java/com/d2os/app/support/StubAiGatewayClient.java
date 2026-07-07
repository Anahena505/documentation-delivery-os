package com.d2os.app.support;

import com.d2os.persona.gateway.AiCallRequest;
import com.d2os.persona.gateway.AiCallResult;
import com.d2os.persona.gateway.AiGatewayClient;
import com.d2os.persona.gateway.EmbedRequest;
import com.d2os.persona.gateway.EmbedResult;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Deterministic AI Gateway double for integration tests (T040) — no real provider key is available
 * in this environment. Returns content long enough to pass {@code ValidationPipeline}'s structural
 * and quality heuristics, with no injection-symptom markers, so the happy-path pipeline can be
 * proven end to end without a live model call.
 *
 * <p>Phase 2 (T004): the returned bean is a {@link LatencyControllableGateway} whose per-call delay
 * is configurable at runtime. Overlap (SC-003) and progress-cadence (SC-006) assertions need calls
 * to take measurable, controllable wall-clock time; the default profile is zero delay so ordinary
 * ITs stay fast, while ParallelExecutionIT/LoadPostureIT dial it up.
 */
@TestConfiguration
public class StubAiGatewayClient {

    /** Test-visible so suites can vary latency and inspect the prompts personas were actually sent. */
    public static final class LatencyControllableGateway implements AiGatewayClient {

        private volatile long minDelayMillis = 0L;
        private volatile long maxDelayMillis = 0L;
        private final AtomicLong callCount = new AtomicLong();
        // Deterministic pseudo-variation without Math.random (kept reproducible across replay).
        private final AtomicLong ripple = new AtomicLong(1);
        // Prompts containing any of these markers get a too-short output that fails structural
        // validation, forcing that persona to exhaust its revise loop and escalate (US2 test).
        private final Set<String> failMarkers = ConcurrentHashMap.newKeySet();
        // Prompt-marker -> exact output to return, so a test can seed a specific artifact body (e.g. an
        // index block with a dangling reference or a contradictory attribute) for the consistency check.
        private final java.util.Map<String, String> responses = new ConcurrentHashMap<>();

        /** Set a per-call delay window; each call sleeps a deterministic value within [min,max]. */
        public LatencyControllableGateway withLatency(long minMillis, long maxMillis) {
            this.minDelayMillis = Math.max(0, minMillis);
            this.maxDelayMillis = Math.max(this.minDelayMillis, maxMillis);
            return this;
        }

        /** Make any call whose rendered prompt contains {@code marker} fail validation (escalate). */
        public LatencyControllableGateway failFor(String marker) {
            failMarkers.add(marker);
            return this;
        }

        /** Return {@code content} verbatim for any call whose rendered prompt contains {@code marker}. */
        public LatencyControllableGateway respondWith(String marker, String content) {
            responses.put(marker, content);
            return this;
        }

        /** Reset latency + failure + response config between tests sharing the Spring context. */
        public LatencyControllableGateway reset() {
            minDelayMillis = 0L;
            maxDelayMillis = 0L;
            failMarkers.clear();
            responses.clear();
            return this;
        }

        public long callCount() {
            return callCount.get();
        }

        @Override
        public AiCallResult call(AiCallRequest request) {
            callCount.incrementAndGet();
            sleepWithinProfile();
            String prompt = request.renderedPrompt();
            // Custom seeded output takes precedence (consistency-check fixtures).
            if (prompt != null) {
                for (var e : responses.entrySet()) {
                    if (prompt.contains(e.getKey())) {
                        return new AiCallResult(e.getValue(), "stub-provider", "stub-model-1.0", 128L);
                    }
                }
            }
            for (String marker : failMarkers) {
                if (prompt != null && prompt.contains(marker)) {
                    // Below MIN_STRUCTURAL_LENGTH → structural_completeness (critical) fails.
                    return new AiCallResult("no", "stub-provider", "stub-model-1.0", 8L);
                }
            }
            return new AiCallResult(
                    "This is a deterministic stub artifact produced for integration testing. ".repeat(6),
                    "stub-provider", "stub-model-1.0", 128L);
        }

        private void sleepWithinProfile() {
            long span = maxDelayMillis - minDelayMillis;
            long delay = minDelayMillis;
            if (span > 0) {
                // Deterministic ripple across the [min,max] window — reproducible, no RNG.
                long r = ripple.updateAndGet(v -> (v * 1103515245L + 12345L) & 0x7fffffffL);
                delay += r % (span + 1);
            }
            if (delay > 0) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        /**
         * Vector dimensionality of the deterministic test embeddings. Phase 2 (T004) sizes the
         * {@code knowledge_item.embedding vector(N)} column and T007's real {@code embed(...)} to
         * this same constant so stub-seeded fixtures and the pgvector column agree.
         */
        public static final int EMBEDDING_DIMENSIONS = 384;

        /**
         * Deterministic, provider-free embedding for integration tests (T003, research R3): the
         * same content always yields the same L2-normalized vector, so KnowledgeRetrievalIT,
         * KnowledgeReplayIT, and KnowledgeLeakageIT are reproducible with no model call and replay
         * stays byte-identical. Seeded from a SHA-256 of the content expanded through a pure LCG —
         * no {@code Math.random}, matching this class's "reproducible across replay" contract.
         *
         * <p>T007 adds {@code embed(EmbedRequest) → EmbedResult} to {@link AiGatewayClient}; its
         * stub override will delegate here (recording model id {@code stub-embed-1.0}). Kept as a
         * standalone helper for now so this Setup task carries no dependency on the Phase 2 gateway
         * types.
         */
        public float[] deterministicEmbedding(String content) {
            return deterministicEmbedding(content, EMBEDDING_DIMENSIONS);
        }

        /** {@link #deterministicEmbedding(String)} with an explicit dimensionality. */
        public float[] deterministicEmbedding(String content, int dimensions) {
            byte[] seedBytes;
            try {
                seedBytes = MessageDigest.getInstance("SHA-256")
                        .digest((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 unavailable", e); // never on a standard JRE
            }
            // Fold the 32 hash bytes into a 64-bit LCG seed, then generate a stable pseudo-vector.
            long state = 0x9E3779B97F4A7C15L;
            for (byte b : seedBytes) {
                state = (state ^ (b & 0xFFL)) * 1099511628211L; // FNV-style mix, fully deterministic
            }
            float[] vector = new float[dimensions];
            double sumSquares = 0.0;
            for (int i = 0; i < dimensions; i++) {
                state = state * 6364136223846793005L + 1442695040888963407L; // 64-bit LCG (PCG constants)
                // Map the high bits to [-1, 1] deterministically.
                double v = ((state >>> 11) / (double) (1L << 53)) * 2.0 - 1.0;
                vector[i] = (float) v;
                sumSquares += v * v;
            }
            // L2-normalize so cosine similarity over these vectors is well-behaved.
            double norm = Math.sqrt(sumSquares);
            if (norm > 0) {
                for (int i = 0; i < dimensions; i++) {
                    vector[i] = (float) (vector[i] / norm);
                }
            }
            return vector;
        }

        /**
         * T007 override: deterministic, provider-free embedding delegating to
         * {@link #deterministicEmbedding(String)} — no model call, reproducible across replay.
         */
        @Override
        public EmbedResult embed(EmbedRequest request) {
            String text = request == null ? "" : request.text();
            return new EmbedResult(deterministicEmbedding(text), "stub-provider", "stub-embed-1.0");
        }
    }

    @Bean
    @Primary
    public LatencyControllableGateway stubAiGatewayClient() {
        return new LatencyControllableGateway();
    }
}
