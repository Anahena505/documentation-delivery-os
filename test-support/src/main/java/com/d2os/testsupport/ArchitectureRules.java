package com.d2os.testsupport;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.properties.CanBeAnnotated;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.stereotype.Component;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Module-boundary rules (T005; Phase 3 T039) enforcing constitution invariants at
 * compile-time-adjacent test time:
 *  - Personas never call each other (AD-8 / FR-017).
 *  - Only the persona.gateway package may reach provider SDKs (AS-5, Principle II).
 *  - persona never depends on knowledge — retrieval flows only through the persona-owned
 *    {@code KnowledgeProvider} SPI, keeping the module graph acyclic (research R1).
 *  - persona execution beans hold no mutable instance state, and the execution machinery never
 *    recurses into another persona's execution (Phase 2 T051, FR-018).
 * Applied over the full {@code com.d2os} classpath by {@code ArchitectureRulesTest} in the app module.
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

    /**
     * The persona → knowledge arrow is forbidden (Phase 3, T039, research R1): {@code knowledge}
     * implements persona's {@code KnowledgeProvider} SPI, so the dependency must stay one-way
     * (knowledge → persona). A persona class reaching into {@code com.d2os.knowledge} would create a
     * module cycle and let the hot path bypass the SPI seam.
     */
    public static ArchRule personaDoesNotDependOnKnowledge() {
        return noClasses()
            .that().resideInAPackage("com.d2os.persona..")
            .should().dependOnClassesThat().resideInAPackage("com.d2os.knowledge..")
            .because("retrieval flows only through the KnowledgeProvider SPI; "
                    + "the module graph is acyclic: knowledge -> persona, never back (research R1)");
    }

    /**
     * The persona → intake (attachment raw-storage) arrow is forbidden (Phase 2 US5, T050, FR-015).
     * Persona receives attachment context only as sanitized summaries through the persona-owned
     * {@code AttachmentSummaryPort} SPI (implemented by intake); it must never reach into the intake
     * attachment package, which owns the raw uploaded bytes and the extraction pipeline. A persona
     * class touching {@code com.d2os.intake} would let a persona read raw attachment content, defeating
     * the sandbox boundary, and would invert the dependency (intake → persona, never back).
     */
    public static ArchRule personaDoesNotDependOnAttachmentStorage() {
        return noClasses()
            .that().resideInAPackage("com.d2os.persona..")
            .should().dependOnClassesThat().resideInAPackage("com.d2os.intake..")
            .because("attachment context reaches a persona only as sanitized summaries via the "
                    + "AttachmentSummaryPort SPI; persona never touches the raw-storage path (FR-015)");
    }

    /**
     * Persona execution beans hold no mutable instance state (Phase 2, T051, FR-018): a Spring-managed
     * class ({@code @Component}/{@code @Service}) in the persona module must not carry a reassignable
     * field, because case-specific state living across invocations would let one Case's execution
     * silently leak into another's — every {@code PersonaEnvelope}-adjacent class is built fresh per
     * invocation instead (T029, AD-8). {@code beFinal()} permits a final reference to a thread-safe,
     * internally-mutable structure (e.g. {@code WorkspaceRateLimiter}'s per-workspace rate windows) —
     * that is a bounded cross-cutting guard, not persona execution state — while forbidding the field
     * itself from ever being reassigned. JPA entities (the runtime rows, which legitimately mutate) are
     * a different concern entirely and are not Spring-stereotype beans, so they fall outside this rule.
     */
    public static ArchRule personaExecutionBeansHoldNoMutableState() {
        // metaAnnotatedWith (not annotatedWith) so @Service resolves too: Spring's @Service is itself
        // meta-annotated with @Component, and this single predicate catches any current or future
        // stereotype (@Repository, @Controller, ...) without needing to OR every one by hand.
        DescribedPredicate<JavaClass> springBeanInPersonaModule =
                resideInAPackage("com.d2os.persona..")
                        .and(CanBeAnnotated.Predicates.metaAnnotatedWith(Component.class));
        return fields()
            .that().areDeclaredInClassesThat(springBeanInPersonaModule)
            .should().beFinal()
            .because("persona execution beans are stateless — every field is set once at construction "
                    + "and never reassigned, so no Case's execution can leak state into another's (AD-8, FR-018)");
    }

    /**
     * The persona execution machinery never recurses into another persona's execution (Phase 2, T051,
     * AD-8, FR-018): none of the classes that make up one persona step's call graph — envelope build
     * ({@code com.d2os.persona}) through the gateway call ({@code com.d2os.persona.gateway}) — may
     * themselves call back into {@code PersonaExecutionService}. Only code OUTSIDE that call graph may
     * trigger a persona's execution: the orchestration module's BPMN delegates (the normal case), and
     * top-level gate services like {@code ConsistencyService} (a distinct pipeline step that happens to
     * run the {@code consistency-reviewer} persona as one of its two tiers — it is invoked BY
     * orchestration, not FROM WITHIN another persona's execution, so it is intentionally outside the
     * scoped packages here rather than excluded by name).
     */
    public static ArchRule personaExecutionMachineryNeverRecursesIntoAnotherPersona() {
        DescribedPredicate<JavaClass> executionMachinery =
                (resideInAPackage("com.d2os.persona").or(resideInAPackage("com.d2os.persona.gateway")))
                        .and(DescribedPredicate.not(JavaClass.Predicates.simpleName("PersonaExecutionService")));
        return noClasses()
            .that(executionMachinery)
            .should().dependOnClassesThat().haveSimpleName("PersonaExecutionService")
            .because("a persona step's own execution machinery (envelope build -> render -> gateway "
                    + "call -> validate -> record) must never itself trigger another persona's execution "
                    + "(AD-8, FR-018) — only orchestration delegates and top-level gate services may");
    }

    public static void checkAll(JavaClasses importedClasses) {
        personaNoPeerCalls().check(importedClasses);
        onlyGatewayCallsProviders().check(importedClasses);
        personaDoesNotDependOnKnowledge().check(importedClasses);
        personaDoesNotDependOnAttachmentStorage().check(importedClasses);
        personaExecutionBeansHoldNoMutableState().check(importedClasses);
        personaExecutionMachineryNeverRecursesIntoAnotherPersona().check(importedClasses);
    }
}
