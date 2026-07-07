package com.d2os.knowledge.influence;

import com.d2os.casecore.CaseInstance;
import com.d2os.casecore.CaseInstanceRepository;
import com.d2os.knowledge.KnowledgeItem;
import com.d2os.knowledge.KnowledgeItemRepository;
import com.d2os.observability.KpiEmitter;
import com.d2os.persona.ExecutionEnvelopeBuilder;
import com.d2os.persona.OperationExecution;
import com.d2os.persona.OperationExecutionRecorder;
import com.d2os.persona.PersonaEnvelope;
import com.d2os.persona.PersonaInvocation;
import com.d2os.persona.PersonaInvocationRepository;
import com.d2os.persona.PromptRenderer;
import com.d2os.persona.ValidationPipeline;
import com.d2os.persona.ValidationResult;
import com.d2os.persona.gateway.AiCallRequest;
import com.d2os.persona.gateway.AiCallResult;
import com.d2os.persona.gateway.AiGatewayClient;
import com.d2os.persona.spi.KnowledgeProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Measures a KnowledgeItem version's rubric-score contribution via a paired with/without evaluation
 * (US4, T032, FR-017, research R9). Against a chosen case + persona (whose pinned rubric supplies the
 * scoring criteria), it runs the persona step twice under the SAME rubric version:
 *
 * <ol>
 *   <li><b>with</b> — the target item force-included as the sole injected knowledge;</li>
 *   <li><b>without</b> — no injected knowledge.</li>
 * </ol>
 *
 * The influence value is the measured {@code withScore - withoutScore} rubric-score delta, emitted on
 * the existing {@code kpi_sample} stream tagged with the item's {@code (key, version)}. Both runs are
 * recorded with {@code evaluation=true} so they never feed delivery (and, being operation_execution
 * rows with no produced artifact, cannot reach a package regardless).
 *
 * <p><b>Why not go through {@code PersonaExecutionService}?</b> That path auto-retrieves knowledge from
 * the persona profile and drives the bounded revise loop — it offers no way to force a specific item in
 * or out, nor to flag the run as an evaluation. This service therefore reuses the same building blocks
 * (envelope builder, prompt renderer, gateway, validation pipeline, recorder) directly for a single
 * controlled generation per side. It intentionally does NOT charge the case token budget (an evaluation
 * is diagnostic and must not deny a live case its budget).
 */
@Service
public class InfluenceEvaluationService {

    private final KnowledgeItemRepository itemRepository;
    private final CaseInstanceRepository caseRepository;
    private final ExecutionEnvelopeBuilder envelopeBuilder;
    private final PromptRenderer promptRenderer;
    private final AiGatewayClient aiGatewayClient;
    private final ValidationPipeline validationPipeline;
    private final OperationExecutionRecorder operationExecutionRecorder;
    private final PersonaInvocationRepository personaInvocationRepository;
    private final KpiEmitter kpiEmitter;

    public InfluenceEvaluationService(KnowledgeItemRepository itemRepository,
                                      CaseInstanceRepository caseRepository,
                                      ExecutionEnvelopeBuilder envelopeBuilder,
                                      PromptRenderer promptRenderer,
                                      AiGatewayClient aiGatewayClient,
                                      ValidationPipeline validationPipeline,
                                      OperationExecutionRecorder operationExecutionRecorder,
                                      PersonaInvocationRepository personaInvocationRepository,
                                      KpiEmitter kpiEmitter) {
        this.itemRepository = itemRepository;
        this.caseRepository = caseRepository;
        this.envelopeBuilder = envelopeBuilder;
        this.promptRenderer = promptRenderer;
        this.aiGatewayClient = aiGatewayClient;
        this.validationPipeline = validationPipeline;
        this.operationExecutionRecorder = operationExecutionRecorder;
        this.personaInvocationRepository = personaInvocationRepository;
        this.kpiEmitter = kpiEmitter;
    }

    /** The paired-evaluation outcome: both execution ids, both rubric scores, and the emitted delta. */
    public record InfluenceResult(UUID itemId, String key, int version, double withScore,
                                  double withoutScore, double delta,
                                  UUID withExecutionId, UUID withoutExecutionId) {}

    /**
     * Run the paired influence evaluation of {@code itemId} against {@code caseId}'s pinned rubric for
     * {@code personaKey}, and emit the delta.
     *
     * @throws NoSuchElementException the item or case does not exist
     */
    @Transactional
    public InfluenceResult evaluate(UUID itemId, UUID caseId, String personaKey) {
        KnowledgeItem item = itemRepository.findById(itemId)
                .orElseThrow(() -> new NoSuchElementException("knowledge item " + itemId));
        CaseInstance kase = caseRepository.findById(caseId)
                .orElseThrow(() -> new NoSuchElementException("case " + caseId));

        PersonaEnvelope base = envelopeBuilder.build(caseId, personaKey);
        KnowledgeProvider.InjectedItem target = new KnowledgeProvider.InjectedItem(
                item.getWorkspaceId(), item.getId(), item.getKey(), item.getVersion(),
                item.getContent(), item.getContentHash());

        RunResult with = runOnce(kase, personaKey, variant(base, List.of(target)));
        RunResult without = runOnce(kase, personaKey, variant(base, List.of()));

        double delta = with.score() - without.score();
        kpiEmitter.emitKnowledgeInfluence(kase.getWorkspaceId(), caseId, delta, item.getKey(), item.getVersion());

        return new InfluenceResult(itemId, item.getKey(), item.getVersion(),
                with.score(), without.score(), delta, with.executionId(), without.executionId());
    }

    private RunResult runOnce(CaseInstance kase, String personaKey, PersonaEnvelope env) {
        String prompt = promptRenderer.render(env);
        AiCallResult result = aiGatewayClient.call(new AiCallRequest(prompt, 2048));
        ValidationResult validation = validationPipeline.validate(result.outputText(), env.rubricJson());

        PersonaInvocation invocation = new PersonaInvocation(
                UUID.randomUUID(), kase.getWorkspaceId(), kase.getId(),
                env.personaDefinitionId(), env.personaDefinitionVersion(), nextSequenceNo(kase.getId()));
        invocation.setPersonaKey(personaKey);
        invocation.markStatus(PersonaInvocation.Status.validated);
        personaInvocationRepository.save(invocation);

        OperationExecution execution = operationExecutionRecorder.record(
                kase.getWorkspaceId(), invocation.getId(), env, prompt,
                result.modelId(), result.modelVersion(), result.outputText(), result.tokensUsed(),
                1, validation, true);   // evaluation=true
        return new RunResult(execution.getId(), validation.weightedScore());
    }

    /** Copy the base envelope, swapping only the injected knowledge (and its rough token estimate). */
    private PersonaEnvelope variant(PersonaEnvelope base, List<KnowledgeProvider.InjectedItem> injected) {
        int tokens = injected.stream()
                .mapToInt(i -> i.content() == null ? 0 : i.content().length() / 4)
                .sum();
        return new PersonaEnvelope(
                base.caseId(), base.personaKey(), base.personaDefinitionId(), base.personaDefinitionVersion(),
                base.promptDefinitionId(), base.promptDefinitionVersion(), base.promptTemplate(),
                base.rubricDefinitionId(), base.rubricDefinitionVersion(), base.rubricJson(),
                base.submissionFormDataJson(), injected, tokens, base.attachmentSummaries());
    }

    private int nextSequenceNo(UUID caseId) {
        return personaInvocationRepository.findByCaseInstanceIdOrderBySequenceNoAsc(caseId).size() + 1;
    }

    private record RunResult(UUID executionId, double score) {}
}
