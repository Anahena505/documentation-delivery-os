package com.d2os.persona.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * Provider-agnostic AI Gateway (T030, AS-5). Talks to the Anthropic Messages API shape by default;
 * swapping providers means changing {@code d2os.ai-gateway.*} config and the request/response
 * mapping here — callers (personas) never change. Logs every call (T5-a) with metadata only
 * (never the full rendered prompt, to avoid leaking submission content into general logs — the
 * full prompt is preserved instead in the OperationExecution snapshot, T034).
 */
@Component
@EnableConfigurationProperties(AiGatewayProperties.class)
public class HttpAiGatewayClient implements AiGatewayClient {

    private static final Logger log = LoggerFactory.getLogger(HttpAiGatewayClient.class);

    private final RestClient restClient;
    private final RestClient embedRestClient;
    private final AiGatewayProperties properties;
    /** Null when uncapped (maxConcurrentCalls <= 0). Fair, so branches don't starve under load. */
    private final Semaphore concurrencyLimit;
    private final WorkspaceRateLimiter rateLimiter;

    public HttpAiGatewayClient(AiGatewayProperties properties, WorkspaceRateLimiter rateLimiter) {
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.concurrencyLimit = properties.maxConcurrentCalls() > 0
                ? new Semaphore(properties.maxConcurrentCalls(), true)
                : null;
        String apiKey = properties.apiKey() == null ? "" : properties.apiKey();
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", "2023-06-01")
                .build();
        // Embeddings live on a separate endpoint (T007, research R3); same auth header shape.
        this.embedRestClient = RestClient.builder()
                .baseUrl(properties.embedBaseUrl())
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
    }

    @Override
    public AiCallResult call(AiCallRequest request) {
        // Per-workspace request-rate smoothing (T037) then the global in-flight cap (T021, FR-003).
        // Excess concurrent branches block here — the engine job pool still holds the case, so nothing
        // is dropped, just paced.
        rateLimiter.acquire();
        acquire();
        try {
            return doCall(request);
        } finally {
            release();
        }
    }

    private AiCallResult doCall(AiCallRequest request) {
        long start = System.nanoTime();
        Map<String, Object> body = Map.of(
                "model", properties.model(),
                "max_tokens", request.maxTokens(),
                "messages", List.of(Map.of("role", "user", "content", request.renderedPrompt()))
        );

        Map<?, ?> response = restClient.post()
                .body(body)
                .retrieve()
                .body(Map.class);

        String outputText = extractText(response);
        long tokensUsed = extractTokens(response);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        log.info("AI Gateway call: provider={} model={} tokens={} elapsedMs={} promptChars={}",
                properties.provider(), properties.model(), tokensUsed, elapsedMs,
                request.renderedPrompt().length());

        return new AiCallResult(outputText, properties.provider(), properties.model(), tokensUsed);
    }

    @Override
    public EmbedResult embed(EmbedRequest request) {
        // Embedding shares the same concurrency budget as generation calls (research R2/R3).
        acquire();
        try {
            long start = System.nanoTime();
            int expectedDims = properties.embedDimensions();
            // Request the configured output dimension explicitly so providers that support Matryoshka
            // reduction (OpenAI `dimensions`, Voyage `output_dimension`) return exactly VECTOR(n)-sized
            // vectors. Both keys are sent; a provider ignores the one it doesn't recognise.
            Map<String, Object> body = Map.of(
                    "model", properties.embedModel(),
                    "input", List.of(request.text() == null ? "" : request.text()),
                    "dimensions", expectedDims,
                    "output_dimension", expectedDims
            );
            Map<?, ?> response = embedRestClient.post()
                    .body(body)
                    .retrieve()
                    .body(Map.class);

            float[] vector = extractEmbedding(response);
            // Fail fast and loud at the choke point: a wrong-sized vector would otherwise surface only as
            // a cryptic Postgres dimension error on insert/query (T013). Never store/query a mismatched vector.
            if (vector.length != expectedDims) {
                throw new EmbeddingDimensionException(properties.embedModel(), expectedDims, vector.length);
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            log.info("AI Gateway embed: provider={} model={} dims={} elapsedMs={} inputChars={}",
                    properties.provider(), properties.embedModel(), vector.length, elapsedMs,
                    request.text() == null ? 0 : request.text().length());

            return new EmbedResult(vector, properties.provider(), properties.embedModel());
        } finally {
            release();
        }
    }

    /** Defensive parse for both {data:[{embedding:[...]}]} and {embedding:[...]} response shapes. */
    private float[] extractEmbedding(Map<?, ?> response) {
        if (response == null) return new float[0];
        Object embedding = response.get("embedding");
        Object data = response.get("data");
        if (embedding == null && data instanceof List<?> list && !list.isEmpty()
                && list.get(0) instanceof Map<?, ?> first) {
            embedding = first.get("embedding");
        }
        if (embedding instanceof List<?> nums) {
            float[] vector = new float[nums.size()];
            for (int i = 0; i < nums.size(); i++) {
                vector[i] = nums.get(i) instanceof Number n ? n.floatValue() : 0f;
            }
            return vector;
        }
        return new float[0];
    }

    private void acquire() {
        if (concurrencyLimit == null) return;
        try {
            concurrencyLimit.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted acquiring AI gateway permit", e);
        }
    }

    private void release() {
        if (concurrencyLimit != null) concurrencyLimit.release();
    }

    @SuppressWarnings("unchecked")
    private String extractText(Map<?, ?> response) {
        if (response == null) return "";
        Object content = response.get("content");
        if (content instanceof List<?> blocks && !blocks.isEmpty()
                && blocks.get(0) instanceof Map<?, ?> first) {
            Object text = first.get("text");
            return text == null ? "" : text.toString();
        }
        return "";
    }

    private long extractTokens(Map<?, ?> response) {
        if (response == null) return 0;
        Object usage = response.get("usage");
        if (usage instanceof Map<?, ?> u) {
            long in = toLong(u.get("input_tokens"));
            long out = toLong(u.get("output_tokens"));
            return in + out;
        }
        return 0;
    }

    private long toLong(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }
}
