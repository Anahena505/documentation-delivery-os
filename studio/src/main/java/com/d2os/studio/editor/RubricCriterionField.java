package com.d2os.studio.editor;

/**
 * One rubric scoring criterion (tasks.md T009, FR-003). Field names/shape match the real seeded
 * rubric criteria {@code CatalogSeedLoader.seed("rubric", ...)} writes — {@code
 * {"name":...,"weight":...,"critical":...}} — and that {@code
 * com.d2os.persona.ValidationPipeline#readCriteria} reads back out at persona-output scoring time,
 * so an authored draft is structurally compatible with the runtime reader from day one.
 */
public record RubricCriterionField(String name, double weight, boolean critical) {}
