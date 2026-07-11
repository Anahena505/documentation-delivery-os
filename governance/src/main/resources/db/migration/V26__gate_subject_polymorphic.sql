-- V26 Polymorphic gate subject + delta_report definition-diff support (Phase 6, Catalog Studio,
-- governance — data-model.md, tasks.md T005, research R3).
--
-- NUMBERING NOTE: see catalog's V25__studio_lifecycle_subscriptions.sql header for the full
-- reasoning — data-model.md/plan.md/tasks.md name this V22, but V21-V24 were already taken by
-- Phase 5's own renumbering (see V20's header) plus catalog's V24. This is the actual next-free
-- integer: V26 (governance), immediately after catalog's V25.

-- ---------------------------------------------------------------------------------------------
-- gate_instance: polymorphic subject (research R3, T4-b, data-model.md "Modified Entities"). A
-- studio publish gate's subject is a DefinitionAsset version, not an ArtifactRevision — subject_id
-- + subject_type generalize what subject_artifact_revision_id could only say one way. The old
-- column is KEPT as a deprecated read alias (not dropped): GateInstance.java/GateService.java
-- still populate it for ARTIFACT_REVISION-subject gates (T006), and every existing caller
-- (GateTaskBridge) keeps working unchanged.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE gate_instance
    ADD COLUMN subject_type text NOT NULL DEFAULT 'ARTIFACT_REVISION',
    ADD COLUMN subject_id   uuid NULL;

-- Backfill subject_id from the existing artifact-revision column for every row that has one.
-- subject_type keeps its DEFAULT 'ARTIFACT_REVISION' for all existing rows (lossless — R3: "the
-- polymorphic subject is the minimal generalization, two columns + backfill, lossless").
UPDATE gate_instance SET subject_id = subject_artifact_revision_id
    WHERE subject_artifact_revision_id IS NOT NULL;

-- ---------------------------------------------------------------------------------------------
-- delta_report: widen beyond artifact-revision diffs so it can also diff two definition_asset
-- versions (prompt/content text). This is an ADDITION beyond T005's literal scope (data-model.md
-- only calls out gate_instance for V22/V26) — made here, in the same governance migration that is
-- already touching gate_instance's polymorphic subject, because Phase 6's later T014
-- ("reuse DeltaReportService to diff prompt text ... for Prompt/Persona ... attached as the gate's
-- inputs_ref") needs delta_report rows for definition_asset pairs, which have no
-- artifact/artifact_revision backing (their content lives in definition_asset.body). Doing the
-- widening now avoids a second, otherwise-unnecessary governance migration solely for T014.
--
-- The V20 columns (artifact_id, from_revision_id, to_revision_id) were NOT NULL; a definition-pair
-- delta_report row cannot populate them, so they must become nullable. The new from/to
-- definition_id columns are the definition-diff counterpart. A CHECK constraint enforces that
-- every row is unambiguously EITHER an artifact-revision diff OR a definition-version diff, never
-- both and never neither.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE delta_report
    ALTER COLUMN artifact_id      DROP NOT NULL,
    ALTER COLUMN from_revision_id DROP NOT NULL,
    ALTER COLUMN to_revision_id   DROP NOT NULL;

ALTER TABLE delta_report
    ADD COLUMN from_definition_id uuid NULL REFERENCES definition_asset(id),
    ADD COLUMN to_definition_id   uuid NULL REFERENCES definition_asset(id);

-- Cross-module FK (governance -> catalog's definition_asset): consistent with existing practice —
-- V20 (governance) already FKs to workspace (tenancy) and case_instance (casecore) from this same
-- migration stream; Flyway manages one global schema, so a cross-module FK is not a new pattern.
ALTER TABLE delta_report ADD CONSTRAINT chk_delta_report_subject_shape CHECK (
    (artifact_id IS NOT NULL AND from_revision_id IS NOT NULL AND to_revision_id IS NOT NULL
        AND from_definition_id IS NULL AND to_definition_id IS NULL)
    OR
    (artifact_id IS NULL AND from_revision_id IS NULL AND to_revision_id IS NULL
        AND from_definition_id IS NOT NULL AND to_definition_id IS NOT NULL)
);
