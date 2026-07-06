-- V4 Case core + audit spine (E1.4, §6, AD-6, Principle V, T6).

CREATE TABLE case_instance (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id   UUID NOT NULL REFERENCES workspace(id),
    feature_id     UUID NOT NULL REFERENCES feature(id),
    submission_id  UUID NOT NULL,
    case_type_key      TEXT NOT NULL,
    case_type_version  TEXT NOT NULL,
    mode           TEXT NOT NULL DEFAULT 'mutating' CHECK (mode IN ('mutating','assessment')),
    status         TEXT NOT NULL DEFAULT 'Submitted' CHECK (status IN
                     ('Submitted','Classified','Planned','Running','Waiting',
                      'Suspended','Escalated','Delivered','Cancelled')),
    token_budget   BIGINT NOT NULL DEFAULT 0,
    tokens_spent   BIGINT NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by     TEXT NOT NULL
);

-- Invariant: at most one active mutating Case per Feature (FR-016). Terminal/suspended states exempt.
CREATE UNIQUE INDEX uq_active_mutating_case_per_feature
    ON case_instance (feature_id)
    WHERE mode = 'mutating'
      AND status NOT IN ('Delivered','Cancelled','Suspended');

-- Now that case_instance exists, wire the snapshot FK from V3.
ALTER TABLE case_definition_snapshot
    ADD CONSTRAINT fk_snapshot_case FOREIGN KEY (case_instance_id) REFERENCES case_instance(id);

CREATE TABLE workflow_instance (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id       UUID NOT NULL REFERENCES workspace(id),
    case_instance_id   UUID NOT NULL REFERENCES case_instance(id),
    engine_instance_id TEXT NOT NULL,        -- Flowable process instance id (E1.5 correlation)
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Transactional outbox (AD-6) — same tx as the state change it describes.
CREATE TABLE event_outbox (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id   UUID NOT NULL REFERENCES workspace(id),
    aggregate_type TEXT NOT NULL,
    aggregate_id   UUID NOT NULL,
    event_type     TEXT NOT NULL,
    payload        JSONB NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ
);

-- Tamper-evident audit trail (Principle V). Append-only enforced by grants below (T6-a).
CREATE TABLE audit_entry (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    subject_type TEXT NOT NULL,
    subject_id   UUID NOT NULL,
    action       TEXT NOT NULL,
    actor        TEXT NOT NULL,
    tx_time      TIMESTAMPTZ NOT NULL DEFAULT now(),
    details      JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE decision (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id     UUID NOT NULL REFERENCES workspace(id),
    case_instance_id UUID NOT NULL REFERENCES case_instance(id),
    decision_type    TEXT NOT NULL CHECK (decision_type IN ('D1','D2','D3','D4')),
    decided_by       TEXT NOT NULL,
    rationale        TEXT,
    inputs_ref       TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Append-only DB grants on the audit stream (T6-a): a dedicated app role may INSERT/SELECT only.
-- (Role creation is environment-specific; documented here as the required grant contract.)
--   REVOKE UPDATE, DELETE ON audit_entry, event_outbox FROM d2os_app;
--   GRANT  INSERT, SELECT ON audit_entry, event_outbox TO d2os_app;

ALTER TABLE case_instance    ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow_instance ENABLE ROW LEVEL SECURITY;
ALTER TABLE event_outbox     ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_entry      ENABLE ROW LEVEL SECURITY;
ALTER TABLE decision         ENABLE ROW LEVEL SECURITY;

CREATE POLICY ws_isolation_case ON case_instance
    USING (workspace_id = current_setting('app.workspace_id', true)::uuid);
CREATE POLICY ws_isolation_wf ON workflow_instance
    USING (workspace_id = current_setting('app.workspace_id', true)::uuid);
CREATE POLICY ws_isolation_outbox ON event_outbox
    USING (workspace_id = current_setting('app.workspace_id', true)::uuid);
CREATE POLICY ws_isolation_audit ON audit_entry
    USING (workspace_id = current_setting('app.workspace_id', true)::uuid);
CREATE POLICY ws_isolation_decision ON decision
    USING (workspace_id = current_setting('app.workspace_id', true)::uuid);
