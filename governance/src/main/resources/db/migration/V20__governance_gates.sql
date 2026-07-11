-- V20 Governance gates core (Phase 5, BC-7, data-model.md — research R1-R4, R8).
--
-- NOTE on migration numbering: data-model.md/plan.md/tasks.md name this V17, but V17 (tenancy),
-- V18 (intake), and V19 (casecore) were already taken by Phase 4 migrations by the time this phase
-- was implemented (Flyway's stream is one global `classpath:db/migration` namespace shared by every
-- module — see app/src/main/resources/application.yml). Renumbered to the actual next-free
-- integers: V20 (governance), V21 (casecore audit chain), V22 (artifacts grants), V23 (tenancy
-- retention columns) — same relative order the design docs specify, shifted by +3.
--
-- Grants: V8 set ALTER DEFAULT PRIVILEGES granting d2os_app SELECT/INSERT/UPDATE/DELETE on every new
-- table (belt-and-suspenders, matching V12/V13's convention); each table below still gets an explicit
-- GRANT for readability, and append-only tables REVOKE UPDATE/DELETE afterward.

-- ---------------------------------------------------------------------------------------------------
-- delta_report — deterministic diff between two revisions of one artifact (R2). Created before
-- gate_instance because gate_instance.delta_report_id references it.
-- ---------------------------------------------------------------------------------------------------
CREATE TABLE delta_report (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id     UUID NOT NULL REFERENCES workspace(id),
    artifact_id      UUID NOT NULL REFERENCES artifact(id),
    from_revision_id UUID NOT NULL REFERENCES artifact_revision(id),
    to_revision_id   UUID NOT NULL REFERENCES artifact_revision(id),
    diff_content     TEXT NOT NULL,             -- unified diff, deterministic (java-diff-utils)
    diff_hash        TEXT NOT NULL,             -- SHA-256 over diff_content (tamper check / reproducibility)
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------------------------------
-- gate_instance — one runtime Review/Approval gate occurrence bridged to the engine's user task (R1).
-- ---------------------------------------------------------------------------------------------------
CREATE TABLE gate_instance (
    id                            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id                  UUID NOT NULL REFERENCES workspace(id),
    case_instance_id              UUID NOT NULL REFERENCES case_instance(id),
    gate_type                     TEXT NOT NULL CHECK (gate_type IN ('REVIEW','APPROVAL')),
    gate_definition_key           TEXT NOT NULL,
    gate_definition_version       INT NOT NULL,
    subject_artifact_revision_id  UUID REFERENCES artifact_revision(id),
    inputs_ref                    JSONB NOT NULL,   -- exact info the decision is based on (revisions, rubric scores, delta report id)
    escalation_policy_key         TEXT,
    escalation_policy_version     INT,
    status                        TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN
                                     ('OPEN','APPROVED','REJECTED','REGENERATING','REOPEN_CANDIDATE','REOPENED')),
    decision_id                   UUID REFERENCES decision(id),
    reviewer_comments              TEXT,             -- REQUEST_CHANGES payload (Q4)
    delta_report_id               UUID REFERENCES delta_report(id),
    engine_task_id                 TEXT,             -- Flowable task correlation (GateTaskBridge)
    opened_at                     TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at                    TIMESTAMPTZ,
    reopened_at                   TIMESTAMPTZ
);

CREATE INDEX idx_gate_instance_case ON gate_instance (case_instance_id);
CREATE INDEX idx_gate_instance_engine_task ON gate_instance (engine_task_id);

-- ---------------------------------------------------------------------------------------------------
-- impact_assessment — the reason/scope/risk record that must precede a reopen (Q3, R3). At most one
-- per (gate, upstream revision) — enforced below; ReopenService requires it before REOPEN_CANDIDATE
-- -> REOPENED.
-- ---------------------------------------------------------------------------------------------------
CREATE TABLE impact_assessment (
    id                             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id                   UUID NOT NULL REFERENCES workspace(id),
    gate_instance_id               UUID NOT NULL REFERENCES gate_instance(id),
    upstream_artifact_revision_id  UUID NOT NULL REFERENCES artifact_revision(id),
    reason                         TEXT NOT NULL,
    scope                          TEXT NOT NULL,
    risk                           TEXT NOT NULL,
    author                         TEXT NOT NULL,
    created_at                     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_impact_assessment_gate_upstream UNIQUE (gate_instance_id, upstream_artifact_revision_id)
);

-- ---------------------------------------------------------------------------------------------------
-- gate_reopen_candidate — DMN-identified dependents of a revised approved artifact (R3). "Identified"
-- is audited separately from "acted on": depth=1 rows are reopenable, depth>1 are MANUAL_REVIEW only.
-- ---------------------------------------------------------------------------------------------------
CREATE TABLE gate_reopen_candidate (
    id                              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id                    UUID NOT NULL REFERENCES workspace(id),
    upstream_artifact_revision_id   UUID NOT NULL REFERENCES artifact_revision(id),
    dependent_artifact_revision_id  UUID NOT NULL REFERENCES artifact_revision(id),
    gate_instance_id                UUID REFERENCES gate_instance(id),   -- the approved gate to reopen (direct only)
    depth                           INT NOT NULL,                       -- 1 = direct (reopenable); >1 = transitive
    disposition                     TEXT NOT NULL DEFAULT 'PENDING' CHECK (disposition IN
                                       ('PENDING','REOPENED','MANUAL_REVIEW','DISMISSED')),
    created_at                      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_reopen_candidate_upstream ON gate_reopen_candidate (upstream_artifact_revision_id);
CREATE INDEX idx_reopen_candidate_gate ON gate_reopen_candidate (gate_instance_id);

-- ---------------------------------------------------------------------------------------------------
-- escalation_activation — a visible record of each advisory SLA firing (Q9, R4). Append-only: firing
-- never mutates GateInstance status or the engine task (advisory only).
-- ---------------------------------------------------------------------------------------------------
CREATE TABLE escalation_activation (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id       UUID NOT NULL REFERENCES workspace(id),
    gate_instance_id   UUID NOT NULL REFERENCES gate_instance(id),
    policy_key         TEXT NOT NULL,
    policy_version     INT NOT NULL,
    step_index         INT NOT NULL,       -- position in the role chain
    role               TEXT NOT NULL,      -- escalation target role (recorded even if unassigned)
    assignee_resolved  BOOLEAN NOT NULL,   -- false => surfaced as unassigned, still recorded
    status             TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','RESOLVED')),
    fired_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_escalation_activation_gate ON escalation_activation (gate_instance_id);

-- ---------------------------------------------------------------------------------------------------
-- in_app_notification — persisted in-app notification row, the v1 delivery mechanism for advisory
-- SLA escalations and tamper alerts (FR-010; no email/webhook). `type` is deliberately NOT a CHECK
-- constraint: source_module/type are generic-by-design so later phases (e.g. Phase 7 cycle/divergence
-- alerts) can persist their own rows through the same store without a schema change (data-model.md).
-- ---------------------------------------------------------------------------------------------------
CREATE TABLE in_app_notification (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id    UUID NOT NULL REFERENCES workspace(id),
    recipient_role  TEXT NOT NULL,        -- role-addressed, resolved to users at read time
    source_module   TEXT NOT NULL,        -- 'governance' in v1; other modules may write rows later
    type            TEXT NOT NULL,        -- 'SLA_ESCALATION' | 'TAMPER_ALERT' (extensible, no CHECK)
    subject_ref     JSONB NOT NULL,       -- e.g. {gateInstanceId} / {segmentId}
    message         TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    read_at         TIMESTAMPTZ           -- per-notification read marker
);

CREATE INDEX idx_in_app_notification_recipient ON in_app_notification (workspace_id, recipient_role, read_at);

-- --- RLS (workspace isolation) ------------------------------------------------------------------
ALTER TABLE delta_report            ENABLE ROW LEVEL SECURITY;
ALTER TABLE gate_instance           ENABLE ROW LEVEL SECURITY;
ALTER TABLE impact_assessment       ENABLE ROW LEVEL SECURITY;
ALTER TABLE gate_reopen_candidate   ENABLE ROW LEVEL SECURITY;
ALTER TABLE escalation_activation   ENABLE ROW LEVEL SECURITY;
ALTER TABLE in_app_notification     ENABLE ROW LEVEL SECURITY;

CREATE POLICY ws_isolation_delta_report ON delta_report
    USING (workspace_id = current_setting('app.workspace_id', true)::uuid);
CREATE POLICY ws_isolation_gate_instance ON gate_instance
    USING (workspace_id = current_setting('app.workspace_id', true)::uuid);
CREATE POLICY ws_isolation_impact_assessment ON impact_assessment
    USING (workspace_id = current_setting('app.workspace_id', true)::uuid);
CREATE POLICY ws_isolation_gate_reopen_candidate ON gate_reopen_candidate
    USING (workspace_id = current_setting('app.workspace_id', true)::uuid);
CREATE POLICY ws_isolation_escalation_activation ON escalation_activation
    USING (workspace_id = current_setting('app.workspace_id', true)::uuid);
CREATE POLICY ws_isolation_in_app_notification ON in_app_notification
    USING (workspace_id = current_setting('app.workspace_id', true)::uuid);

-- --- Grants (d2os_app) ----------------------------------------------------------------------------
GRANT SELECT, INSERT, UPDATE, DELETE ON delta_report, gate_instance, impact_assessment,
    gate_reopen_candidate, in_app_notification TO d2os_app;

-- escalation_activation is append-only (data-model.md, Q9): grant INSERT/SELECT, deny mutation
-- (T6-a-style contract, Principle V) — a firing is a durable fact, never edited or removed.
GRANT SELECT, INSERT ON escalation_activation TO d2os_app;
REVOKE UPDATE, DELETE ON escalation_activation FROM d2os_app;
