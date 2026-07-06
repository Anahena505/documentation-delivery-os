package com.d2os.persona.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

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
    private final AiGatewayProperties properties;

    public HttpAiGatewayClient(AiGatewayProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultHeader("x-api-key", properties.apiKey() == null ? "" : properties.apiKey())
                .defaultHeader("anthropic-version", "2023-06-01")
                .build();
    }

    @Override
    public AiCallResult call(AiCallRequest request) {
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
