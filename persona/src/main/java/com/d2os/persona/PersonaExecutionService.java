package com.d2os.persona;

import com.d2os.casecore.CaseInstance;
import com.d2os.casecore.CaseInstanceRepository;
import com.d2os.casecore.CaseService;
import com.d2os.observability.KpiEmitter;
import com.d2os.persona.gateway.AiCallRequest;
import com.d2os.persona.gateway.AiCallResult;
import com.d2os.persona.gateway.AiGatewayClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Orchestrates one persona step end to end (T029–T034, T058): build envelope → render prompt →
 * token-budget gate → call the AI Gateway → validate → persist the OperationExecution snapshot.
 * Bounded revise loop (FR-005): original + 2 automated revise attempts (3 generations total); if
 * the 3rd still fails validation, the Case escalates to a human rather than retrying further.
 *
 * <p>Personas never call one another (AD-8) — this service only ever processes the single persona
 * step it was invoked for; the BPMN process (not this service) sequences persona-1 → persona-2 →
 * persona-3.
 */
@Service
public class PersonaExecutionService {

    private static final int MAX_ATTEMPTS = 3;
    private static final long ESTIMATED_TOKENS_PER_CALL = 2_000L;
    private static final Pattern PERSONA_SEQUENCE = Pattern.compile("persona-(\\d+)");

    private final ExecutionEnvelopeBuilder envelopeBuilder;
    private final PromptRenderer promptRenderer;
    private final TokenBudgetGuard tokenBudgetGuard;
    private final AiGatewayClient aiGatewayClient;
    private final ValidationPipeline validationPipeline;
    private final OperationExecutionRecorder operationExecutionRecorder;
    private final PersonaInvocationRepository personaInvocationRepository;
    private final CaseInstanceRepository caseInstanceRepository;
    private final CaseService caseService;
    private final KpiEmitter kpiEmitter;

    public PersonaExecutionService(ExecutionEnvelopeBuilder envelopeBuilder,
                                   PromptRenderer promptRenderer,
                                   TokenBudgetGuard tokenBudgetGuard,
                                   AiGatewayClient aiGatewayClient,
                                   ValidationPipeline validationPipeline,
                                   OperationExecutionRecorder operationExecutionRecorder,
                                   PersonaInvocationRepository personaInvocationRepository,
                                   CaseInstanceRepository caseInstanceRepository,
                                   CaseService caseService,
                                   KpiEmitter kpiEmitter) {
        this.envelopeBuilder = envelopeBuilder;
        this.promptRenderer = promptRenderer;
        this.tokenBudgetGuard = tokenBudgetGuard;
        this.aiGatewayClient = aiGatewayClient;
        this.validationPipeline = validationPipeline;
        this.operationExecutionRecorder = operationExecutionRecorder;
        this.personaInvocationRepository = personaInvocationRepository;
        this.caseInstanceRepository = caseInstanceRepository;
        this.caseService = caseService;
        this.kpiEmitter = kpiEmitter;
    }

    /** Runs one persona step for a case, driving the bounded revise loop to a terminal outcome. */
    public void executePersona(UUID caseId, String personaKey) {
        CaseInstance kase = caseInstanceRepository.findById(caseId)
                .orElseThrow(() -> new NoSuchElementException("case " + caseId));

        PersonaEnvelope envelope = envelopeBuilder.build(caseId, personaKey);
        String renderedPrompt = promptRenderer.render(envelope);

        PersonaInvocation invocation = new PersonaInvocation(
                UUID.randomUUID(), kase.getWorkspaceId(), caseId,
                envelope.personaDefinitionId(), envelope.personaDefinitionVersion(),
                sequenceNumberOf(personaKey));
        invocation.markStatus(PersonaInvocation.Status.running);
        personaInvocationRepository.save(invocation);

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            tokenBudgetGuard.checkBeforeCall(caseId, ESTIMATED_TOKENS_PER_CALL);   // throws + suspends on breach

            AiCallResult result = aiGatewayClient.call(new AiCallRequest(renderedPrompt, 2048));
            tokenBudgetGuard.recordUsage(caseId, result.tokensUsed());

            ValidationResult validation = validationPipeline.validate(result.outputText(), envelope.rubricJson());

            operationExecutionRecorder.record(
                    kase.getWorkspaceId(), invocation.getId(), envelope, renderedPrompt,
                    result.modelId(), result.modelVersion(), result.outputText(), result.tokensUsed(),
                    attempt, validation);

            if (validation.passed()) {
                invocation.markStatus(PersonaInvocation.Status.validated);
                personaInvocationRepository.save(invocation);
                // First-pass rate: 1.0 if it passed on the original attempt, else 0.0 (FR-015).
                kpiEmitter.emit(kase.getWorkspaceId(), KpiEmitter.RUBRIC_FIRST_PASS_RATE, caseId,
                        attempt == 1 ? 1.0 : 0.0);
                return;
            }

            if (attempt == MAX_ATTEMPTS) {
                invocation.markStatus(PersonaInvocation.Status.escalated);
                personaInvocationRepository.save(invocation);
                caseService.escalate(caseId, "persona " + personaKey
                        + " failed validation after " + MAX_ATTEMPTS + " generations: "
                        + validation.criticalFailures());
                return;
            }
            // else: loop again — this is the "revise" attempt (same rendered prompt; a future
            // enhancement could append the rubric failure as revision guidance to the prompt).
        }
    }

    private int sequenceNumberOf(String personaKey) {
        Matcher m = PERSONA_SEQUENCE.matcher(personaKey);
        return m.matches() ? Integer.parseInt(m.group(1)) : 0;
    }
}
