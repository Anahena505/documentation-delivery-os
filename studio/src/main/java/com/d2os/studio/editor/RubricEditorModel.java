package com.d2os.studio.editor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Typed-slot form model for Rubric drafts (tasks.md T009, plan.md {@code editor/} subpackage,
 * FR-003). Maps a Rubric definition's raw {@code body} JSON —
 * {@code {"personaKey":"...","criteria":[{"name":...,"weight":...,"critical":...}, ...]}} — into
 * named, typed fields the studio editor form binds to, instead of a free-form JSON blob.
 *
 * <p>The shape mirrors what {@code CatalogSeedLoader} actually seeds (e.g. {@code
 * seed("rubric", personaKey + "-rubric", V1, "{\"personaKey\":...,\"criteria\":[{\"name\":
 * \"structural_completeness\",\"weight\":0.5,\"critical\":true}, ...]}")}) and what {@code
 * com.d2os.persona.ValidationPipeline} reads at scoring time ({@code criterion.path("name")},
 * {@code "weight"}, {@code "critical")}) — so a rubric authored through this editor model is
 * runtime-compatible with the existing scoring pipeline the moment it is published (a later
 * phase's job, not this one's).
 */
public record RubricEditorModel(String personaKey, List<RubricCriterionField> criteria) {

    private static final double WEIGHT_SUM_TOLERANCE = 0.001;

    /**
     * Server-side slot validation (FR-003: "structured fields validated before save, not free-form
     * blobs"). Mirrors the shape every seeded Phase 1-5 rubric already satisfies: a non-blank
     * personaKey, at least one named criterion, each weight in (0,1], and weights summing to 1.0.
     */
    public void validate() {
        if (personaKey == null || personaKey.isBlank()) {
            throw new IllegalArgumentException("rubric personaKey must not be blank");
        }
        if (criteria == null || criteria.isEmpty()) {
            throw new IllegalArgumentException("rubric must declare at least one criterion");
        }
        double weightSum = 0;
        for (RubricCriterionField c : criteria) {
            if (c.name() == null || c.name().isBlank()) {
                throw new IllegalArgumentException("every rubric criterion needs a non-blank name");
            }
            if (c.weight() <= 0 || c.weight() > 1) {
                throw new IllegalArgumentException(
                        "criterion '" + c.name() + "' weight must be in (0,1], was " + c.weight());
            }
            weightSum += c.weight();
        }
        if (Math.abs(weightSum - 1.0) > WEIGHT_SUM_TOLERANCE) {
            throw new IllegalArgumentException(
                    "rubric criteria weights must sum to 1.0, summed to " + weightSum);
        }
    }

    /** Serialize to the raw JSON string {@code DefinitionAsset#getBody()} stores. */
    public String toBodyJson(ObjectMapper mapper) {
        ObjectNode root = mapper.createObjectNode();
        root.put("personaKey", personaKey);
        ArrayNode criteriaNode = root.putArray("criteria");
        for (RubricCriterionField c : criteria) {
            ObjectNode cn = criteriaNode.addObject();
            cn.put("name", c.name());
            cn.put("weight", c.weight());
            cn.put("critical", c.critical());
        }
        return root.toString();
    }

    /** Parse an existing rubric draft's body JSON back into typed fields (reopen-and-edit). */
    public static RubricEditorModel fromBodyJson(String bodyJson, ObjectMapper mapper) throws Exception {
        JsonNode root = mapper.readTree(bodyJson);
        String personaKey = root.path("personaKey").asText(null);
        List<RubricCriterionField> criteria = new ArrayList<>();
        for (JsonNode c : root.path("criteria")) {
            criteria.add(new RubricCriterionField(
                    c.path("name").asText(null), c.path("weight").asDouble(0.0), c.path("critical").asBoolean(false)));
        }
        return new RubricEditorModel(personaKey, criteria);
    }
}
