package com.d2os.persona.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds {@code d2os.ai-gateway.*} (provider-agnostic per AS-5). */
@ConfigurationProperties(prefix = "d2os.ai-gateway")
public record AiGatewayProperties(
    String provider,
    String model,
    String baseUrl,
    String apiKey,
    // Caps simultaneous provider calls independently of the engine worker pool (T021, research R2,
    // FR-003) so concurrency tuning can't blow the provider rate limit. 0/absent = uncapped.
    int maxConcurrentCalls,
    // Embeddings model + endpoint (T007, Phase 3, research R3). Provider-agnostic (AS-5); the
    // embeddings API is a separate endpoint from messages, so it has its own base URL.
    String embedModel,
    String embedBaseUrl,
    // Output dimensionality requested from the embeddings provider and enforced on the returned
    // vector (T013). This MUST equal the knowledge_item.embedding VECTOR(n) column (V13) and the
    // model's actual output dimension; HttpAiGatewayClient sends it to the provider (Matryoshka
    // reduction) and throws EmbeddingDimensionException on any mismatch. Default 384 matches the
    // VECTOR(384) column and StubAiGatewayClient.EMBEDDING_DIMENSIONS.
    int embedDimensions) {
  public AiGatewayProperties {
    if (baseUrl == null) baseUrl = "https://api.anthropic.com/v1/messages";
    // Default embeddings config is coherent at 384 dims: text-embedding-3-small supports exact
    // Matryoshka reduction to any size via the `dimensions` request field, so the shipped default
    // produces vectors that fit the VECTOR(384) column out of the box. (voyage-3 emits 1024 and
    // would not — swapping embed-model requires matching embed-dimensions AND the DB column.)
    if (embedModel == null) embedModel = "text-embedding-3-small";
    if (embedBaseUrl == null) embedBaseUrl = "https://api.openai.com/v1/embeddings";
    if (embedDimensions <= 0) embedDimensions = 384;
  }
}
