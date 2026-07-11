package com.d2os.persona;

import com.d2os.casecore.CaseInstance;
import com.d2os.casecore.CaseInstanceRepository;
import com.d2os.casecore.CaseService;
import com.d2os.casecore.progress.ProgressEmitter;
import com.d2os.casecore.progress.ProgressEvent;
import com.d2os.observability.KpiEmitter;
import com.d2os.persona.gateway.AiCallRequest;
import com.d2os.persona.gateway.AiCallResult;
import com.d2os.persona.gateway.AiGatewayClient;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;

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
  private final ProgressEmitter progressEmitter;

  public PersonaExecutionService(
      ExecutionEnvelopeBuilder envelopeBuilder,
      PromptRenderer promptRenderer,
      TokenBudgetGuard tokenBudgetGuard,
      AiGatewayClient aiGatewayClient,
      ValidationPipeline validationPipeline,
      OperationExecutionRecorder operationExecutionRecorder,
      PersonaInvocationRepository personaInvocationRepository,
      CaseInstanceRepository caseInstanceRepository,
      CaseService caseService,
      KpiEmitter kpiEmitter,
      ProgressEmitter progressEmitter) {
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
    this.progressEmitter = progressEmitter;
  }

  /** Sequential-step convenience overload (no parallel branch). */
  public boolean executePersona(UUID caseId, String personaKey) {
    return executePersona(caseId, personaKey, null);
  }

  /**
   * Runs one persona step for a case, driving the bounded revise loop to a terminal outcome.
   * Returns {@code true} if the output validated, {@code false} if the step exhausted its revise
   * loop and escalated. The caller (the BPMN delegate) surfaces that outcome to the process so a
   * parallel branch can route to its escalation wait state (US2, FR-005) instead of silently
   * advancing. {@code branchId} is the parallel-branch execution id (null for sequential steps).
   */
  public boolean executePersona(UUID caseId, String personaKey, String branchId) {
    return executePersona(caseId, personaKey, branchId, null);
  }

  /**
   * Comment-and-regenerate re-entry overload (Phase 5, T019, research R2): {@code
   * regenerationComments} is the gate reviewer's REQUEST_CHANGES text, threaded straight through to
   * {@link ExecutionEnvelopeBuilder#build(UUID, String, String)} so {@link PromptRenderer} injects
   * it as delimited untrusted data (T1-a). Everything else about the bounded revise loop is
   * identical to the ordinary path — {@code RegenerationDelegate} (orchestration) is the only
   * caller that passes a non-null value here.
   */
  public boolean executePersona(
      UUID caseId, String personaKey, String branchId, String regenerationComments) {
    CaseInstance kase =
        caseInstanceRepository
            .findById(caseId)
            .orElseThrow(() -> new NoSuchElementException("case " + caseId));

    PersonaEnvelope envelope = envelopeBuilder.build(caseId, personaKey, regenerationComments);
    String renderedPrompt = promptRenderer.render(envelope);

    PersonaInvocation invocation =
        new PersonaInvocation(
            UUID.randomUUID(),
            kase.getWorkspaceId(),
            caseId,
            envelope.personaDefinitionId(),
            envelope.personaDefinitionVersion(),
            nextSequenceNo(caseId));
    invocation.setPersonaKey(personaKey);
    invocation.setBranchId(branchId);
    invocation.markStatus(PersonaInvocation.Status.running);
    personaInvocationRepository.save(invocation);
    progressEmitter.emit(
        kase.getWorkspaceId(), caseId, ProgressEvent.Kind.STEP_STARTED, personaKey, null);

    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      // Charge injected knowledge tokens against the case budget too (T013/NFR-7).
      tokenBudgetGuard.checkBeforeCall(
          caseId,
          ESTIMATED_TOKENS_PER_CALL
              + envelope.estimatedInjectedTokens()); // throws + suspends on breach

      AiCallResult result = aiGatewayClient.call(new AiCallRequest(renderedPrompt, 2048));
      tokenBudgetGuard.recordUsage(caseId, result.tokensUsed());

      ValidationResult validation =
          validationPipeline.validate(result.outputText(), envelope.rubricJson());
      progressEmitter.emit(
          kase.getWorkspaceId(),
          caseId,
          ProgressEvent.Kind.VALIDATION_ATTEMPT,
          personaKey,
          "{\"attempt\":" + attempt + ",\"passed\":" + validation.passed() + "}");

      operationExecutionRecorder.record(
          kase.getWorkspaceId(),
          invocation.getId(),
          envelope,
          renderedPrompt,
          result.modelId(),
          result.modelVersion(),
          result.outputText(),
          result.tokensUsed(),
          attempt,
          validation);

      if (validation.passed()) {
        invocation.markStatus(PersonaInvocation.Status.validated);
        personaInvocationRepository.save(invocation);
        // First-pass rate: 1.0 if it passed on the original attempt, else 0.0 (FR-015).
        kpiEmitter.emit(
            kase.getWorkspaceId(),
            KpiEmitter.RUBRIC_FIRST_PASS_RATE,
            caseId,
            attempt == 1 ? 1.0 : 0.0);
        progressEmitter.emit(
            kase.getWorkspaceId(), caseId, ProgressEvent.Kind.STEP_COMPLETED, personaKey, null);
        return true;
      }

      if (attempt == MAX_ATTEMPTS) {
        invocation.markStatus(PersonaInvocation.Status.escalated);
        personaInvocationRepository.save(invocation);
        caseService.escalate(
            caseId,
            "persona "
                + personaKey
                + " failed validation after "
                + MAX_ATTEMPTS
                + " generations: "
                + validation.criticalFailures());
        return false;
      }
      // else: loop again — this is the "revise" attempt (same rendered prompt; a future
      // enhancement could append the rubric failure as revision guidance to the prompt).
    }
    return false; // unreachable (loop always returns), but required for definite assignment
  }

  /**
   * Ordering hint only (not a unique key): the count of invocations already recorded for this Case
   * + 1. Works for any persona key — the old {@code persona-N} numbering assumption is gone (T008),
   * so real keys like {@code security-architect} resolve. Under the parallel block two concurrent
   * branches may compute the same value; that is acceptable because sequence_no is a
   * display/ordering aid, not an identity, and branch_id (US2) distinguishes parallel steps.
   */
  private int nextSequenceNo(UUID caseId) {
    return personaInvocationRepository.findByCaseInstanceIdOrderBySequenceNoAsc(caseId).size() + 1;
  }
}
