package com.d2os.persona.gateway;

/**
 * Result of an AI Gateway embedding call (T007). Carries the vector plus the model identity/version
 * that produced it, so KnowledgeItem versions record which model embedded them (Principle II,
 * research R3).
 */
public record EmbedResult(float[] vector, String modelId, String modelVersion) {}
