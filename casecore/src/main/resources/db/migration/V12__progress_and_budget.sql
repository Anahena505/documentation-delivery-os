-- V12 Progress stream, workspace budget, and reconciliation audit (Phase 2, US2/US4 / FR-010,011,017).

-- Append-only user-visible liveness stream (FR-011, NFR-3). A monotonic bigint id gives the
-- `waitAfter` long-poll a stable cursor. Same append-only treatment as audit_entry (T6-a): the app
-- role may INSERT/SELECT but never UPDATE/DELETE.
CREATE TABLE progress_event (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    case_id      UUID NOT NULL REFERENCES case_instance(id),
    kind         TEXT NOT NULL
                   CHECK (kind IN ('STEP_STARTED','STEP_COMPLETED','VALIDATION_ATTEMPT',
                                   'BRANCH_FORKED','BRANCH_JOINED','HEARTBEAT','ESCALATED',
                                   'SUSPENDED','RECONCILED','DELIVERED')),
    activity_id  TEXT,
    detail       JSONB,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_progress_event_case ON progress_event (case_id, id);

-- Per-workspace AI-cost ceiling + durable consumption rollup (FR-017, T5-b). token_cap = 0 means
-- unlimited; a real deployment seeds a cap. tokens_consumed is only ever increased, in the same
-- transaction as each OperationExecution's cost record.
CREATE TABLE workspace_budget (
    workspace_id         UUID PRIMARY KEY REFERENCES workspace(id),
    period_start         DATE NOT NULL DEFAULT CURRENT_DATE,
    token_cap            BIGINT NOT NULL DEFAULT 0,
    tokens_consumed      BIGINT NOT NULL DEFAULT 0,
    rate_limit_per_minute INT NOT NULL DEFAULT 120,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()   -- optimistic-concurrency guard on rollup
);

-- Audit trail of the dual-state reconciler (FR-010). Only rows for sweeps that FOUND divergence —
-- clean sweeps emit nothing.
CREATE TABLE reconciliation_run (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    case_id      UUID NOT NULL REFERENCES case_instance(id),
    divergence   TEXT NOT NULL
                   CHECK (divergence IN ('MISSING_DOMAIN_TRANSITION','DEAD_LETTER_JOB','STATE_MISMATCH')),
    engine_state JSONB NOT NULL,
    domain_state JSONB NOT NULL,
    action       TEXT NOT NULL CHECK (action IN ('REPAIRED','ESCALATED','IGNORED_TRANSIENT')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_reconciliation_run_case ON reconciliation_run (case_id);

ALTER TABLE progress_event      ENABLE ROW LEVEL SECURITY;
ALTER TABLE workspace_budget    ENABLE ROW LEVEL SECURITY;
ALTER TABLE reconciliation_run  ENABLE ROW LEVEL SECURITY;

CREATE POLICY ws_isolation_progress_event ON progress_event
    USING (workspace_id = current_setting('app.workspace_id', true)::uuid);
CREATE POLICY ws_isolation_workspace_budget ON workspace_budget
    USING (workspace_id = current_setting('app.workspace_id', true)::uuid);
CREATE POLICY ws_isolation_reconciliation_run ON reconciliation_run
    USING (workspace_id = current_setting('app.workspace_id', true)::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON workspace_budget, reconciliation_run TO d2os_app;
-- progress_event is append-only: grant INSERT/SELECT, then deny mutation (T6-a contract, Principle V).
GRANT SELECT, INSERT ON progress_event TO d2os_app;
REVOKE UPDATE, DELETE ON progress_event FROM d2os_app;
