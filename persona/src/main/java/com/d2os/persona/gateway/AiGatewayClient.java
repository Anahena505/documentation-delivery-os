package com.d2os.persona.gateway;

/**
 * The sole provider-call choke point (T007/T030, AS-5, Principle II). No other class in the
 * system may call a model provider directly — enforced by {@code ArchitectureRules.onlyGatewayCallsProviders}.
 * Every implementation MUST log the call (T5-a) before returning.
 */
public interface AiGatewayClient {
    AiCallResult call(AiCallRequest request);
}
