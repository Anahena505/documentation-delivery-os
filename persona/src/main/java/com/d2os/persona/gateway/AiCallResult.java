package com.d2os.persona.gateway;

/** Outcome of an AI Gateway call, including provider/model identity for the OperationExecution snapshot (Principle II). */
public record AiCallResult(String outputText, String modelId, String modelVersion, long tokensUsed) {
}
