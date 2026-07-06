package com.d2os.app.support;

import com.d2os.persona.gateway.AiCallRequest;
import com.d2os.persona.gateway.AiCallResult;
import com.d2os.persona.gateway.AiGatewayClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Deterministic AI Gateway double for integration tests (T040) — no real provider key is available
 * in this environment. Returns content long enough to pass {@code ValidationPipeline}'s structural
 * and quality heuristics, with no injection-symptom markers, so the happy-path pipeline can be
 * proven end to end without a live model call.
 */
@TestConfiguration
public class StubAiGatewayClient {

    @Bean
    @Primary
    public AiGatewayClient stubAiGatewayClient() {
        return request -> new AiCallResult(
                "This is a deterministic stub artifact produced for integration testing. ".repeat(6),
                "stub-provider", "stub-model-1.0", 128L);
    }
}
