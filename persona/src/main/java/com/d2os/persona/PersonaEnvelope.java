package com.d2os.persona;

import com.d2os.persona.spi.KnowledgeProvider;

import java.util.List;
import java.util.UUID;

/**
 * The fully-resolved, stateless execution envelope for one persona step (T029, AD-8). Built fresh
 * per invocation from the Case's pinned {@code CaseDefinitionSnapshot} — never carries state from a
 * prior persona, and provides no path to invoke another persona (enforced structurally: this record
 * has no reference to any other PersonaEnvelope or persona-execution API).
 *
 * <p>Phase 3 (T013): the envelope additionally carries the governed knowledge resolved for injection
 * ({@code injectedKnowledge}, already scope/tag/profile filtered and workspace-guarded) plus a rough
 * {@code estimatedInjectedTokens} count so the token-budget gate can charge injected content against
 * the per-case budget (NFR-7). Both are empty/zero when no knowledge is injected, keeping pre-Phase-3
 * behavior byte-identical.
 *
 * <p>Phase 2 US5 (T044, FR-015): {@code attachmentSummaries} carries the sanitized summaries of the
 * submission's attachments — the ONLY attachment-derived text a persona may consume. The builder reads
 * these through {@code AttachmentSummaryPort}, never the raw object-store bytes, and the renderer places
 * them inside untrusted-data delimiters. Empty when the submission has no summarized attachments.
 *
 * <p>Phase 5 (T019, research R2, Q4): {@code regenerationComments} carries a gate reviewer's
 * REQUEST_CHANGES comments when this envelope is built for a comment-and-regenerate re-entry
 * ({@code RegenerationDelegate}, orchestration) — null/blank for every ordinary (non-regeneration)
 * persona step, keeping pre-Phase-5 behavior byte-identical. Like attachment summaries, the renderer
 * places this text inside its own untrusted-data delimiters (T1-a) — a reviewer's comment can never
 * be read as an instruction by the persona.
 */
public record PersonaEnvelope(
        UUID caseId,
        String personaKey,
        UUID personaDefinitionId,
        String personaDefinitionVersion,
        UUID promptDefinitionId,
        String promptDefinitionVersion,
        String promptTemplate,
        UUID rubricDefinitionId,
        String rubricDefinitionVersion,
        String rubricJson,
        String submissionFormDataJson,
        List<KnowledgeProvider.InjectedItem> injectedKnowledge,
        int estimatedInjectedTokens,
        List<String> attachmentSummaries,
        String regenerationComments
) {
}
