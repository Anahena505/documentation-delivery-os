-- US6 (spec 008, FR-014, data-model.md §3): artifact-content provenance.
-- Records which immutable TemplateDefinition version an artifact revision's content was rendered
-- from. Both columns are NULLABLE and additive: pre-existing (placeholder) revisions keep NULL
-- provenance, and any revision whose owning case pinned no renderable TemplateDefinition also
-- stays NULL — only the new deterministic template-rendering path (ArtifactService) populates them.
-- A rendered revision references a PUBLISHED, PINNED template version (never a mutable/latest
-- pointer), so provenance is Principle-I-immutable once written.
ALTER TABLE artifact_revision
    ADD COLUMN source_template_id UUID,   -- definition_asset.id of the template rendered from
    ADD COLUMN template_version   TEXT;   -- the exact pinned semver of that template
