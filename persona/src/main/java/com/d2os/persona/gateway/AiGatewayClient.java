package com.d2os.persona.gateway;

/**
 * The sole provider-call choke point (T007/T030, AS-5, Principle II). No other class in the
 * system may call a model provider directly — enforced by {@code ArchitectureRules.onlyGatewayCallsProviders}.
 * Every implementation MUST log the call (T5-a) before returning.
 */
public interface AiGatewayClient {
    AiCallResult call(AiCallRequest request);

    /**
     * Embed text into a vector for KnowledgeItem indexing/retrieval (T007, Phase 3, research R3).
     * Provider-agnostic like {@link #call}; the model identity/version is returned for the item snapshot
     * (Principle II). Integration tests use the deterministic StubAiGatewayClient override, not a provider.
     */
    EmbedResult embed(EmbedRequest request);
}
