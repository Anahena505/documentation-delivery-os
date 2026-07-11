-- V18 Case-type classification proposal + human confirmation columns (Phase 4, research R5,
-- FR-019). Column-only migration — no new table; problem_submission's existing RLS policy/grants
-- (V7) apply unchanged to these additions.
--
-- proposed_case_type is the case-type-classification DMN's advisory output (INITIATION |
-- ASSESSMENT | ENHANCEMENT | UNDETERMINED, hit policy UNIQUE — see research R5). confirmed_case_type
-- is set only by the human confirm/override step and is the authority of record; the original
-- proposal is never overwritten by an override (classification_overridden flips to true instead).
-- A Case is created only once classification_status = 'CONFIRMED' (casecore/CaseService, T012).

ALTER TABLE problem_submission
    ADD COLUMN proposed_case_type text NULL,
    ADD COLUMN confirmed_case_type text NULL,
    ADD COLUMN classification_status text NOT NULL DEFAULT 'PROPOSED',
    ADD COLUMN classification_overridden boolean NOT NULL DEFAULT false;
