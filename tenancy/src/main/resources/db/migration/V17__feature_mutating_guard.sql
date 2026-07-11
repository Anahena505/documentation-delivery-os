-- V17 Feature mutating-case guard columns (Phase 4, research R3, FR-012/013).
-- Column-only migration — no new table; the feature table already carries RLS policy/grants from
-- V2/V8, which apply unchanged to these additions.
--
-- These columns back the Q2 optimistic single-active-mutating-case guard: a single guarded UPDATE
-- (`MutatingCaseGuard.acquire`) sets active_mutating_case_id and advances aggregate_version only
-- when the row still matches the caller's expected version AND the slot is free
-- (WHERE aggregate_version = :expected AND active_mutating_case_id IS NULL). Zero rows updated is
-- the conflict signal (409) — no queue, no row lock held across the case lifecycle. Release runs
-- in the same transaction as the case's terminal transition
-- (WHERE active_mutating_case_id = :caseId). Assessment (read-only) case creation never touches
-- these columns (mutating=false in the pinned CaseDefinitionSnapshot capability flag, T007).
--
-- Distinct from the pre-existing `feature.agg_version` (V2) JPA @Version optimistic-lock column —
-- that guards ordinary Feature field writes; aggregate_version here is a purpose-built counter for
-- the mutating-case slot only, per the explicit predicate design in research R3 (rejected coupling
-- the guard to unrelated Feature updates via the existing @Version column).

ALTER TABLE feature
    ADD COLUMN aggregate_version bigint NOT NULL DEFAULT 0,
    ADD COLUMN active_mutating_case_id uuid NULL;
