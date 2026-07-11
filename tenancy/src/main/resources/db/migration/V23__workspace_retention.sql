-- V23 Workspace retention policy columns (Phase 5, US5, NFR-5, research R6).
-- Column-only migration — no new table; the workspace table already carries RLS policy/grants from
-- V2/V8, which apply unchanged to these additions.
--
-- Numbering: see governance's V20 migration header — this repo's V17/V18/V19 were already taken by
-- Phase 4 migrations by the time Phase 5 was implemented, so this is V23, not the V20 the design
-- docs name.
--
-- 7-year floor, configurable longer, never shorter (RetentionVerificationJob, Phase 7, US5, enforces
-- the floor and never auto-deletes in v1 — disposal remains an explicit governed action).

ALTER TABLE workspace
    ADD COLUMN retention_years int NOT NULL DEFAULT 7,
    ADD COLUMN retention_policy_notes text NULL;
