-- V19 Relax decision.case_instance_id to nullable (Phase 4, T011, US1). Column-only migration — no
-- new table; decision's existing RLS policy/grants (V4) apply unchanged.
--
-- Phase 4's case-type confirm/override step (POST /submissions/{id}/case-type/confirm) writes a D4
-- Decision in the same transaction as the confirm (data-model.md invariant, contracts/api.yaml) — but
-- that confirm necessarily happens BEFORE a Case exists, since Case creation is itself gated on the
-- submission being CONFIRMED (T012, contracts `/cases` 412). The FK to case_instance(id) is kept (a
-- non-null value must still reference a real case), only the NOT NULL constraint is dropped; these
-- pre-case decisions are looked up by `inputs_ref` (the submission id) instead of `case_instance_id`
-- (see DecisionRepository.findFirstByInputsRefAndDecisionTypeOrderByCreatedAtDesc).

ALTER TABLE decision
    ALTER COLUMN case_instance_id DROP NOT NULL;
