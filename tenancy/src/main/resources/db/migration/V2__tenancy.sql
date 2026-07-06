-- V2 Tenancy & organization (E1.2, AD-10, Principle IV).
-- workspace is the hard isolation boundary; RLS enforced on every workspace-scoped table.

CREATE TABLE workspace (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        TEXT NOT NULL,
    status      TEXT NOT NULL DEFAULT 'active',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  TEXT NOT NULL
);

-- Reserved system workspace (global catalog rows).
INSERT INTO workspace (id, name, status, created_by)
VALUES ('00000000-0000-0000-0000-000000000000', 'system-global', 'active', 'system');

CREATE TABLE project (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id  UUID NOT NULL REFERENCES workspace(id),
    name          TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    TEXT NOT NULL
);

CREATE TABLE project_version (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    project_id   UUID NOT NULL REFERENCES project(id),
    label        TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by   TEXT NOT NULL
);

CREATE TABLE feature (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id       UUID NOT NULL REFERENCES workspace(id),
    project_version_id UUID NOT NULL REFERENCES project_version(id),
    name               TEXT NOT NULL,
    -- Optimistic-concurrency token (FR-016): CAS-incremented when a mutating Case attaches.
    agg_version        BIGINT NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by         TEXT NOT NULL
);

-- Row-level security. Application sets `SET app.workspace_id = '<uuid>'` per request
-- (WorkspaceContextFilter, T010); policies below restrict every row to that workspace.
ALTER TABLE workspace       ENABLE ROW LEVEL SECURITY;
ALTER TABLE project         ENABLE ROW LEVEL SECURITY;
ALTER TABLE project_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE feature         ENABLE ROW LEVEL SECURITY;

CREATE POLICY ws_isolation_project ON project
    USING (workspace_id = current_setting('app.workspace_id', true)::uuid);
CREATE POLICY ws_isolation_project_version ON project_version
    USING (workspace_id = current_setting('app.workspace_id', true)::uuid);
CREATE POLICY ws_isolation_feature ON feature
    USING (workspace_id = current_setting('app.workspace_id', true)::uuid);
-- workspace table itself is readable to resolve the active workspace; writes are admin-only.
CREATE POLICY ws_self ON workspace
    USING (id = current_setting('app.workspace_id', true)::uuid
           OR id = '00000000-0000-0000-0000-000000000000');
