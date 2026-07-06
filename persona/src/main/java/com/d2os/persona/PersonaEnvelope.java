package com.d2os.persona;

import java.util.UUID;

/**
 * The fully-resolved, stateless execution envelope for one persona step (T029, AD-8). Built fresh
 * per invocation from the Case's pinned {@code CaseDefinitionSnapshot} — never carries state from a
 * prior persona, and provides no path to invoke another persona (enforced structurally: this record
 * has no reference to any other PersonaEnvelope or persona-execution API).
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
        String submissionFormDataJson
) {
}
