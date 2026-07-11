package com.d2os.studio.editor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Typed-slot form model for Prompt drafts (tasks.md T009, plan.md {@code editor/} subpackage,
 * FR-002/FR-003). Maps a Prompt definition's raw {@code body} JSON —
 * {@code {"personaKey":"...","template":"..."}} — into a typed personaKey + template pair, plus
 * the {@code {{slotName}}} placeholders the template declares. Slots are DERIVED from the template
 * text, not separately stored, so the editor can render one named field per slot without a second
 * source of truth that could drift from the template.
 *
 * <p>The shape mirrors {@code CatalogSeedLoader}'s real seeded prompts (e.g. {@code
 * seed("prompt", personaKey + "-prompt", V1, "{\"personaKey\":...,\"template\":\"You are %s. ...
 * <untrusted-submission-data>{{submissionData}}</untrusted-submission-data>...\"}")}) and what
 * {@code com.d2os.persona.ExecutionEnvelopeBuilder} reads at runtime ({@code
 * objectMapper.readTree(promptBody).path("template").asText()}) — a prompt authored through this
 * editor model round-trips through the exact field the runtime renderer consumes.
 */
public record PromptEditorModel(String personaKey, String template) {

    private static final Pattern SLOT_PATTERN = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_]+)\\s*}}");

    /** The distinct {@code {{slotName}}} placeholders declared in {@link #template}, in first-seen order. */
    public List<String> slots() {
        Set<String> found = new LinkedHashSet<>();
        Matcher m = SLOT_PATTERN.matcher(template == null ? "" : template);
        while (m.find()) {
            found.add(m.group(1));
        }
        return List.copyOf(found);
    }

    /**
     * Server-side slot validation (FR-003). A prompt needs a personaKey to bind against and a
     * non-blank template; the template must declare at least one {@code {{slot}}} placeholder — a
     * prompt with none has nothing for the untrusted-submission-data convention (T1-a) to bind, a
     * strong sign of a malformed/incomplete draft.
     */
    public void validate() {
        if (personaKey == null || personaKey.isBlank()) {
            throw new IllegalArgumentException("prompt personaKey must not be blank");
        }
        if (template == null || template.isBlank()) {
            throw new IllegalArgumentException("prompt template must not be blank");
        }
        if (slots().isEmpty()) {
            throw new IllegalArgumentException(
                    "prompt template must declare at least one {{slot}} placeholder");
        }
    }

    /** Serialize to the raw JSON string {@code DefinitionAsset#getBody()} stores. */
    public String toBodyJson(ObjectMapper mapper) {
        ObjectNode root = mapper.createObjectNode();
        root.put("personaKey", personaKey);
        root.put("template", template);
        return root.toString();
    }

    /** Parse an existing prompt draft's body JSON back into typed fields (reopen-and-edit). */
    public static PromptEditorModel fromBodyJson(String bodyJson, ObjectMapper mapper) throws Exception {
        JsonNode root = mapper.readTree(bodyJson);
        return new PromptEditorModel(root.path("personaKey").asText(null), root.path("template").asText(null));
    }
}
