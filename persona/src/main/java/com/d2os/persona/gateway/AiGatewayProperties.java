package com.d2os.persona.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds {@code d2os.ai-gateway.*} (provider-agnostic per AS-5). */
@ConfigurationProperties(prefix = "d2os.ai-gateway")
public record AiGatewayProperties(
        String provider,
        String model,
        String baseUrl,
        String apiKey
) {
    public AiGatewayProperties {
        if (baseUrl == null) baseUrl = "https://api.anthropic.com/v1/messages";
    }
}
