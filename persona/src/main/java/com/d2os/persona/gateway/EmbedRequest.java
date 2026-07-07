package com.d2os.persona.gateway;

/** A single embedding request (T007, Phase 3). {@code text} is the content to embed (item body or query framing). */
public record EmbedRequest(String text) {
}
