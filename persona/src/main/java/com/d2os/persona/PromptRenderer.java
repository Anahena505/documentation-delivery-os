package com.d2os.persona;

import org.springframework.stereotype.Component;

/**
 * Renders a PromptDefinition template against the submission's form data (T032). Substitution is
 * literal {@code {{var}}} replacement — the rendered submission data is inserted only inside the
 * template's own untrusted-data delimiters (T1-a), never into the instruction portion, since the
 * template author (not the renderer) controls where {@code {{submissionData}}} appears.
 */
@Component
public class PromptRenderer {

    public String render(PersonaEnvelope envelope) {
        return envelope.promptTemplate().replace("{{submissionData}}", envelope.submissionFormDataJson());
    }
}
