package com.d2os.persona.gateway;

/** A single AI Gateway call (T030). {@code renderedPrompt} is fully rendered — the gateway never templates. */
public record AiCallRequest(String renderedPrompt, int maxTokens) {
}
