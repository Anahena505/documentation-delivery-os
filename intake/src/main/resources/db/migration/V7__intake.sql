-- V7 Intake: ProblemSubmission (E1.3, BC-3, AD-12). Body is always DATA, never instructions.

CREATE TABLE problem_submission (
    id                           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id                 UUID NOT NULL REFERENCES workspace(id),
    form_data                    JSONB NOT NULL,                 -- opaque structured input (AD-12)
    sensitivity_tags             JSONB NOT NULL DEFAULT '[]'::jsonb,  -- field-level tagging (T3-a)
    classification_case_type     TEXT,
    classification_confidence    NUMERIC(4,3),
    classification_needs_confirm BOOLEAN NOT NULL DEFAULT true,
    classification_confirmed_by  TEXT,
    status                       TEXT NOT NULL DEFAULT 'received'
                                   CHECK (status IN ('received','classified','confirmed')),
    created_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by                   TEXT NOT NULL
);

-- Encryption-at-rest (T3-b): provided at the storage tier (Postgres TDE / encrypted volume) for v1;
-- field-level column encryption for the most sensitive tags is hardened in T046 (US3).

ALTER TABLE problem_submission ENABLE ROW LEVEL SECURITY;
CREATE POLICY ws_isolation_submission ON problem_submission
    USING (workspace_id = current_setting('app.workspace_id', true)::uuid);
