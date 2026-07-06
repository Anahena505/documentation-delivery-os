-- V3 Catalog: DefinitionAsset supertype + immutability (E1.1, AD-1/AD-3/AD-4, Principle I).

CREATE TABLE definition_asset (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id         UUID NOT NULL REFERENCES workspace(id),
    key                  TEXT NOT NULL,
    version              TEXT NOT NULL,                 -- semver
    type                 TEXT NOT NULL CHECK (type IN
                           ('case_type','workflow','persona','playbook',
                            'template','rule','rubric','prompt')),
    status               TEXT NOT NULL DEFAULT 'Draft'
                           CHECK (status IN ('Draft','Published','Deprecated')),
    locale               TEXT NOT NULL DEFAULT 'en',    -- Q11 schema dimension from Phase 1
    body                 JSONB NOT NULL,
    checksum             TEXT,                          -- SHA-256, set at publish (T4-a)
    -- Provenance for revised-from-v0 templates (Principle IV: revise, never discard).
    derived_from_key     TEXT,
    derived_from_version TEXT,
    published_at         TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by           TEXT NOT NULL,
    -- Identity is (type, key, version): several definition types intentionally share a key with
    -- the case type they belong to (e.g. case_type:initiation and workflow:initiation both at
    -- 1.0.0), so type must be part of the uniqueness key or those legitimately-distinct rows
    -- collide (caught by the integration test).
    CONSTRAINT uq_definition_type_key_version UNIQUE (type, key, version)
);

CREATE INDEX ix_definition_type_status ON definition_asset (type, status);

-- Publish immutability (Principle I): once Published, the row's semantic content is frozen.
-- Only status may advance (Published -> Deprecated); body/checksum/version are locked.
CREATE OR REPLACE FUNCTION enforce_definition_immutability()
RETURNS TRIGGER AS $$
BEGIN
    IF OLD.status = 'Published' THEN
        IF NEW.body IS DISTINCT FROM OLD.body
           OR NEW.version IS DISTINCT FROM OLD.version
           OR NEW.key IS DISTINCT FROM OLD.key
           OR NEW.checksum IS DISTINCT FROM OLD.checksum
           OR NEW.type IS DISTINCT FROM OLD.type THEN
            RAISE EXCEPTION 'Published definition % v% is immutable (Principle I)', OLD.key, OLD.version;
        END IF;
        IF NEW.status NOT IN ('Published','Deprecated') THEN
            RAISE EXCEPTION 'Published definition cannot revert to %', NEW.status;
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_definition_immutability
    BEFORE UPDATE ON definition_asset
    FOR EACH ROW EXECUTE FUNCTION enforce_definition_immutability();

-- CaseDefinitionSnapshot: frozen (id,version) set pinned at Case 'Planned' (AD-4).
CREATE TABLE case_definition_snapshot (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id     UUID NOT NULL REFERENCES workspace(id),
    case_instance_id UUID NOT NULL,          -- FK added in V4 after case_instance exists
    entries          JSONB NOT NULL,          -- [{type,key,version}]
    frozen_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE definition_asset          ENABLE ROW LEVEL SECURITY;
ALTER TABLE case_definition_snapshot  ENABLE ROW LEVEL SECURITY;

-- Definitions are visible to their workspace OR the global system workspace (copy-on-subscribe origin).
CREATE POLICY ws_isolation_definition ON definition_asset
    USING (workspace_id = current_setting('app.workspace_id', true)::uuid
           OR workspace_id = '00000000-0000-0000-0000-000000000000');
CREATE POLICY ws_isolation_snapshot ON case_definition_snapshot
    USING (workspace_id = current_setting('app.workspace_id', true)::uuid);
