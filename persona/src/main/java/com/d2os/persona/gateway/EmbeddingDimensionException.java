package com.d2os.persona.gateway;

/**
 * Thrown when an embeddings provider returns a vector whose dimensionality does not match the
 * configured {@code d2os.ai-gateway.embed-dimensions} (which MUST equal the {@code knowledge_item.embedding
 * VECTOR(n)} column, T003/T013). This converts a silent, production-fatal mismatch — a real provider
 * emitting e.g. 1024-dim vectors against a {@code VECTOR(384)} column, which would otherwise surface only
 * as a cryptic Postgres "expected N dimensions" error on the first insert or query — into an immediate,
 * self-explaining failure at the gateway choke point (Principle II). Integration tests never hit this:
 * {@code StubAiGatewayClient} produces exactly {@code EMBEDDING_DIMENSIONS} floats by construction.
 */
public class EmbeddingDimensionException extends RuntimeException {
    public EmbeddingDimensionException(String model, int expected, int actual) {
        super("Embedding model '" + model + "' returned " + actual + " dimensions but "
                + "d2os.ai-gateway.embed-dimensions is " + expected + "; the model's output dimension, "
                + "d2os.ai-gateway.embed-dimensions, and the knowledge_item.embedding VECTOR(n) column MUST "
                + "all agree. Pick an embed-model whose output dimension is " + expected
                + " (or set embed-dimensions + the DB column to the model's actual dimension).");
    }
}
