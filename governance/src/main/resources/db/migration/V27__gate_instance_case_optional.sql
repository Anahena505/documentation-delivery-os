-- V27 Relax gate_instance.case_instance_id to nullable (Phase 6, Catalog Studio, governance --
-- tasks.md T013/T017, research R3).
--
-- The studio's publish-review gates (subject_type='DEFINITION_VERSION', V26) have no owning Case
-- -- they review a catalog definition version, not anything running inside a workflow instance.
-- V20 declared case_instance_id NOT NULL because, before Phase 6, every gate WAS case-bound
-- (subject_type defaulted to 'ARTIFACT_REVISION', always opened from inside a case's workflow by
-- GateTaskBridge). The FK to case_instance(id) is kept (a non-null value must still reference a
-- real case), only the NOT NULL constraint is dropped -- the exact same column-only,
-- FK-preserving relaxation casecore's V19 (decision.case_instance_id: "the case-type
-- confirm/override decision necessarily happens BEFORE a Case exists") already established for
-- the identical problem shape one table over.
--
-- NUMBERING: next free integer after V26 (governance's previous migration, same phase).

ALTER TABLE gate_instance
    ALTER COLUMN case_instance_id DROP NOT NULL;
