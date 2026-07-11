-- V24 Widen definition_asset.type for Phase 5 governance content (data-model.md "Modified Entities"
-- — new type values SUBPROCESS and ESCALATION_POLICY; content-level, not a structural schema change).
--
-- V3's inline CHECK on `type` restricts it to the Phase 1-4 definition kinds; without widening it,
-- CatalogSeedLoader.seedPhase5() (T011) seeding the two gate SUBPROCESS DefinitionAssets and the
-- ESCALATION_POLICY DefinitionAsset would violate the constraint. Discovered while implementing T011
-- (not called out explicitly in tasks.md); catalog owns definition_asset, so this is catalog's own
-- next-free migration — same "owning module's migration alters that table" convention as V21/V15.
--
-- Numbering: next-free after governance's V20/casecore's V21/artifacts' V22/tenancy's V23.

ALTER TABLE definition_asset DROP CONSTRAINT IF EXISTS definition_asset_type_check;
ALTER TABLE definition_asset ADD CONSTRAINT definition_asset_type_check CHECK (type IN
    ('case_type','workflow','persona','playbook',
     'template','rule','rubric','prompt',
     'SUBPROCESS','ESCALATION_POLICY'));
