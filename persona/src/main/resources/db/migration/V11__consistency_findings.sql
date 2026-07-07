-- V11 Consistency-Check findings + branch tagging (Phase 2, US3 / FR-006..008 / FR-019; US2 branch_id).
-- One row per cross-output discrepancy the Consistency-Check subprocess surfaces. DETERMINISTIC
-- findings hard-block delivery; SEMANTIC findings are advisory and escalate. Each contradiction also
-- writes a trace_link CONFLICTS_WITH edge (AD-7) so the future graph sees conflicts without reading
-- this table.
--
-- Findings anchor to operation_execution ids, not artifact ids: the Consistency-Check runs at the
-- parallel join, BEFORE artifacts are materialized (materialization happens at package assembly, the
-- final step). The persona outputs it checks already exist as operation_execution snapshots.
CREATE TABLE consistency_finding (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id           UUID NOT NULL REFERENCES workspace(id),
    case_id                UUID NOT NULL REFERENCES case_instance(id),
    tier                   TEXT NOT NULL CHECK (tier IN ('DETERMINISTIC','SEMANTIC')),
    kind                   TEXT NOT NULL
                             CHECK (kind IN ('DANGLING_REFERENCE','ATTRIBUTE_CONTRADICTION','SEMANTIC_INCOHERENCE')),
    subject_ref            TEXT NOT NULL,             -- e.g. 'entity:Order' or an attribute name
    source_operation_id    UUID NOT NULL REFERENCES operation_execution(id),   -- output making the reference/assertion
    target_operation_id    UUID REFERENCES operation_execution(id),            -- the owning/contradicting output; NULL for a dangling reference
    detail                 JSONB NOT NULL DEFAULT '{}'::jsonb,
    status                 TEXT NOT NULL DEFAULT 'OPEN'
                             CHECK (status IN ('OPEN','RESOLVED','WAIVED')),
    resolved_by            TEXT,
    resolved_at            TIMESTAMPTZ,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_consistency_finding_case ON consistency_finding (case_id);
-- Fast check of the blocking invariant: any OPEN DETERMINISTIC finding stops the package advancing.
CREATE INDEX idx_consistency_finding_blocking ON consistency_finding (case_id)
    WHERE tier = 'DETERMINISTIC' AND status = 'OPEN';

ALTER TABLE consistency_finding ENABLE ROW LEVEL SECURITY;
CREATE POLICY ws_isolation_consistency_finding ON consistency_finding
    USING (workspace_id = current_setting('app.workspace_id', true)::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON consistency_finding TO d2os_app;

-- Parallel-branch tagging (US2): the BPMN execution/activity id of the branch a persona step ran on
-- (NULL for sequential steps) so replay, reconciliation, and the join can reason about branches
-- without querying the engine.
ALTER TABLE persona_invocation ADD COLUMN branch_id TEXT;

-- The real persona key this invocation ran (T008/T018). Phase 1 inferred it as 'persona-<sequence_no>';
-- the full suite has real keys (e.g. security-architect), so artifacts trace to the actual persona
-- (SC-002) instead of a positional label. Nullable so pre-existing rows validate.
ALTER TABLE persona_invocation ADD COLUMN persona_key TEXT;
