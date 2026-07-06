package com.d2os.testsupport;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Module-boundary rules (T005) enforcing constitution invariants at compile-time-adjacent test time:
 *  - Personas never call each other (AD-8 / FR-017).
 *  - Only the persona.gateway package may reach provider SDKs (AS-5, Principle II).
 * Apply from each module's test via {@code ArchitectureRules.personaNoPeerCalls().check(importedClasses)}.
 */
public final class ArchitectureRules {

    private ArchitectureRules() {}

    /** No class under one persona package may depend on another persona's internals (AD-8). */
    public static ArchRule personaNoPeerCalls() {
        return noClasses()
            .that().resideInAPackage("..persona..")
            .should().dependOnClassesThat().resideInAPackage("..persona.impl..")
            .because("personas are stateless and never call one another (AD-8, FR-017)");
    }

    /** Only the AI Gateway may talk to provider SDKs (single choke point). */
    public static ArchRule onlyGatewayCallsProviders() {
        return noClasses()
            .that().resideOutsideOfPackage("..persona.gateway..")
            .should().dependOnClassesThat().resideInAnyPackage("com.anthropic..", "com.openai..")
            .because("the AI Gateway is the sole provider call site (AS-5, Principle II)");
    }

    public static void checkAll(JavaClasses importedClasses) {
        personaNoPeerCalls().check(importedClasses);
        onlyGatewayCallsProviders().check(importedClasses);
    }
}
