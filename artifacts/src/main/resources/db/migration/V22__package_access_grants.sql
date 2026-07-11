-- V22 Role-scoped package access grants (Phase 5, US5, T3-d, research R6).
--
-- Numbering: see governance's V20 migration header — this repo's V17/V18/V19 were already taken by
-- Phase 4 migrations by the time Phase 5 was implemented, so this is V22, not the V19 the design
-- docs name.
--
-- Reading a delivered package requires a grant for one of the caller's roles — default-deny beyond
-- the granted roles; grants are seeded at delivery for the case's participant roles (nothing
-- workspace-wide by default, PackageAccessService, Phase 7).

CREATE TABLE package_access_grant (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    package_id   UUID NOT NULL REFERENCES execution_package(id),
    role         TEXT NOT NULL,
    granted_by   TEXT NOT NULL,             -- user id, or 'system:delivery' (participant-role seeding)
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at   TIMESTAMPTZ,               -- revocation is a new state; row retained for audit
    CONSTRAINT uq_package_access_grant_role UNIQUE (package_id, role)
);

CREATE INDEX idx_package_access_grant_package ON package_access_grant (package_id);

ALTER TABLE package_access_grant ENABLE ROW LEVEL SECURITY;

CREATE POLICY ws_isolation_package_access_grant ON package_access_grant
    USING (workspace_id = current_setting('app.workspace_id', true)::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON package_access_grant TO d2os_app;
