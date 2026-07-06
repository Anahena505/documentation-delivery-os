-- V6 Artifacts, package, handover, and generalized traceability edges (E1.7, AD-7).

CREATE TABLE artifact (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id           UUID NOT NULL REFERENCES workspace(id),
    case_instance_id       UUID NOT NULL REFERENCES case_instance(id),
    template_definition_id      UUID NOT NULL,
    template_definition_version TEXT NOT NULL,
    artifact_type          TEXT NOT NULL,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE artifact_revision (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    artifact_id  UUID NOT NULL REFERENCES artifact(id),
    revision_no  INT NOT NULL,
    storage_ref  TEXT NOT NULL,                 -- S3/MinIO object key
    content_hash TEXT NOT NULL,                  -- SHA-256 (integrity chain root, SC-005)
    produced_by_operation_execution_id UUID,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_artifact_revision UNIQUE (artifact_id, revision_no)
);

CREATE TABLE execution_package (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id     UUID NOT NULL REFERENCES workspace(id),
    case_instance_id UUID NOT NULL REFERENCES case_instance(id),
    manifest         JSONB NOT NULL,             -- [{artifactType, artifactRevisionId, contentHash}]
    manifest_hash    TEXT NOT NULL,              -- SHA-256 over ordered member hashes
    status           TEXT NOT NULL DEFAULT 'assembled',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- HandoverRecord — full-provenance set; all six fields mandatory (clarification Q4, FR-008).
CREATE TABLE handover_record (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id          UUID NOT NULL REFERENCES workspace(id),
    execution_package_id  UUID NOT NULL REFERENCES execution_package(id),
    contents_index        JSONB NOT NULL,
    submission_ref        UUID NOT NULL,
    definition_snapshot_ref UUID NOT NULL,
    artifact_hashes       JSONB NOT NULL,
    decision_log_ref      TEXT NOT NULL,
    owner_name            TEXT NOT NULL,
    next_action           TEXT NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Generalized polymorphic edge tables (AD-7) — future graph projection reads these verbatim.
CREATE TABLE trace_link (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    from_type    TEXT NOT NULL,
    from_id      UUID NOT NULL,
    to_type      TEXT NOT NULL,
    to_id        UUID NOT NULL,
    link_type    TEXT NOT NULL,                  -- PRODUCED_BY, DERIVES_FROM, SATISFIES, ...
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE dependency (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    from_type    TEXT NOT NULL,
    from_id      UUID NOT NULL,
    to_type      TEXT NOT NULL,
    to_id        UUID NOT NULL,
    dep_type     TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE artifact          ENABLE ROW LEVEL SECURITY;
ALTER TABLE artifact_revision ENABLE ROW LEVEL SECURITY;
ALTER TABLE execution_package ENABLE ROW LEVEL SECURITY;
ALTER TABLE handover_record   ENABLE ROW LEVEL SECURITY;
ALTER TABLE trace_link        ENABLE ROW LEVEL SECURITY;
ALTER TABLE dependency        ENABLE ROW LEVEL SECURITY;

CREATE POLICY ws_isolation_artifact ON artifact
    USING (workspace_id = current_setting('app.workspace_id', true)::uuid);
CREATE POLICY ws_isolation_artifact_rev ON artifact_revision
    USING (workspace_id = current_setting('app.workspace_id', true)::uuid);
CREATE POLICY ws_isolation_package ON execution_package
    USING (workspace_id = current_setting('app.workspace_id', true)::uuid);
CREATE POLICY ws_isolation_handover ON handover_record
    USING (workspace_id = current_setting('app.workspace_id', true)::uuid);
CREATE POLICY ws_isolation_trace ON trace_link
    USING (workspace_id = current_setting('app.workspace_id', true)::uuid);
CREATE POLICY ws_isolation_dependency ON dependency
    USING (workspace_id = current_setting('app.workspace_id', true)::uuid);
