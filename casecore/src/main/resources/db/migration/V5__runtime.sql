-- V5 Runtime execution trace (E1.4, AD-9, Q1/R4). All rows INSERT-only (audit fidelity).

CREATE TABLE persona_invocation (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id       UUID NOT NULL REFERENCES workspace(id),
    case_instance_id   UUID NOT NULL REFERENCES case_instance(id),
    persona_definition_id      UUID NOT NULL,
    persona_definition_version TEXT NOT NULL,
    sequence_no        INT NOT NULL,
    status             TEXT NOT NULL DEFAULT 'pending'
                         CHECK (status IN ('pending','running','validated','escalated')),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Crown-jewel audit row (Principle II): one per generation attempt; the replay target (R5).
CREATE TABLE operation_execution (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id          UUID NOT NULL REFERENCES workspace(id),
    persona_invocation_id UUID NOT NULL REFERENCES persona_invocation(id),
    prompt_definition_id      UUID NOT NULL,
    prompt_definition_version TEXT NOT NULL,
    model_id              TEXT NOT NULL,
    model_version         TEXT NOT NULL,
    inputs                JSONB NOT NULL,        -- FR-006: full input snapshot
    injected_knowledge    JSONB NOT NULL DEFAULT '[]'::jsonb,
    output_ref            TEXT,                  -- storage key of recorded output
    output_hash           TEXT,                  -- SHA-256 of recorded output (replay compare)
    attempt_no            INT NOT NULL CHECK (attempt_no BETWEEN 1 AND 3),   -- FR-005 hard cap
    validation_result     JSONB,
    tokens_used           BIGINT NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE activity_execution (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id          UUID NOT NULL REFERENCES workspace(id),
    persona_invocation_id UUID NOT NULL REFERENCES persona_invocation(id),
    activity_definition_id      UUID NOT NULL,
    activity_definition_version TEXT NOT NULL,
    status                TEXT NOT NULL DEFAULT 'pending',
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ActionItem references its ActionDefinition directly (R4/Q1) — not via activity_execution.
CREATE TABLE action_item (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id             UUID NOT NULL REFERENCES workspace(id),
    case_instance_id         UUID NOT NULL REFERENCES case_instance(id),
    action_definition_id      UUID NOT NULL,
    action_definition_version TEXT NOT NULL,
    payload                  JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE persona_invocation  ENABLE ROW LEVEL SECURITY;
ALTER TABLE operation_execution ENABLE ROW LEVEL SECURITY;
ALTER TABLE activity_execution  ENABLE ROW LEVEL SECURITY;
ALTER TABLE action_item         ENABLE ROW LEVEL SECURITY;

CREATE POLICY ws_isolation_persona_inv ON persona_invocation
    USING (workspace_id = current_setting('app.workspace_id', true)::uuid);
CREATE POLICY ws_isolation_op_exec ON operation_execution
    USING (workspace_id = current_setting('app.workspace_id', true)::uuid);
CREATE POLICY ws_isolation_act_exec ON activity_execution
    USING (workspace_id = current_setting('app.workspace_id', true)::uuid);
CREATE POLICY ws_isolation_action_item ON action_item
    USING (workspace_id = current_setting('app.workspace_id', true)::uuid);
