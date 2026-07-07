-- V13 Knowledge layer core (Phase 3, US1/US2 — data-model.md, research R2/R4–R8).
-- pgvector was provisioned in V1 (CREATE EXTENSION "vector"); this migration exercises it.
--
-- Grants: V8 set ALTER DEFAULT PRIVILEGES granting d2os_app SELECT/INSERT/UPDATE/DELETE on every new
-- table, so below we only REVOKE the operations each table forbids (matching the audit_entry pattern
-- in V8). RLS is enabled + a workspace-isolation policy added on each table, exactly as V2/V5/V11.

-- ---------------------------------------------------------------------------------------------------
-- capture_candidate — a lessons-learned proposal from the case-end capture subprocess (FR-008).
-- Created before knowledge_item because knowledge_item.source_candidate_id references it.
-- Born PROJECT-scoped/confidential/non-promotable; the promotion pipeline is a strict state machine
-- (CAPTURED → PREFILTERED → REDACTED → D4_PENDING → PUBLISHED | REJECTED). Redaction adds a NEW
-- revision row (revision_of chains them) so the pre-redaction version is preserved for audit (FR-011).
-- ---------------------------------------------------------------------------------------------------
CREATE TABLE capture_candidate (
    id                             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id                   UUID NOT NULL REFERENCES workspace(id),
    case_instance_id               UUID NOT NULL REFERENCES case_instance(id),
    project_id                     UUID NOT NULL REFERENCES project(id),
    revision                       INT  NOT NULL DEFAULT 1,
    revision_of                    UUID REFERENCES capture_candidate(id),
    title                          TEXT NOT NULL,
    content                        TEXT NOT NULL,   -- sensitive-tagged source fields excluded by default (T3-c)
    tags                           TEXT[] NOT NULL DEFAULT '{}',
    status                         TEXT NOT NULL DEFAULT 'CAPTURED'
                                     CHECK (status IN ('CAPTURED','PREFILTERED','REDACTED','D4_PENDING','PUBLISHED','REJECTED')),
    curator_operation_execution_id UUID REFERENCES operation_execution(id),
    rejection_stage                TEXT CHECK (rejection_stage IN ('PREFILTER','CURATION','D4')),
    rejection_reason               TEXT,
    created_at                     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------------------------------
-- knowledge_item — the immutable, versioned unit of governed knowledge (FR-001), LIST-partitioned by
-- workspace_id (research R2, T2-b). One partition per workspace + its own HNSW index is created by the
-- provisioning hook (T008) through create_knowledge_item_partition() below; a query carrying the
-- mandatory workspace_id predicate prunes to a single partition, so an ANN scan is STRUCTURALLY unable
-- to traverse another workspace's vectors. The PK/UNIQUE keys include workspace_id (required for
-- partitioned tables). embedding is vector(384) to match StubAiGatewayClient.EMBEDDING_DIMENSIONS (T003).
-- ---------------------------------------------------------------------------------------------------
CREATE TABLE knowledge_item (
    id                  UUID NOT NULL DEFAULT gen_random_uuid(),
    workspace_id        UUID NOT NULL REFERENCES workspace(id),
    key                 TEXT NOT NULL,
    version             INT  NOT NULL,
    scope_level         TEXT NOT NULL CHECK (scope_level IN ('WORKSPACE','PROJECT','GLOBAL')),  -- GLOBAL reserved, unreachable v1 (R4)
    scope_ref           UUID NOT NULL,                 -- workspace id or project id per scope_level
    tags                TEXT[] NOT NULL DEFAULT '{}',
    locale              TEXT NOT NULL DEFAULT 'en',    -- Q11 dimension carried forward
    title               TEXT NOT NULL,
    content             TEXT NOT NULL,
    content_hash        TEXT NOT NULL,                 -- SHA-256 (integrity, Principle III)
    embedding           VECTOR(384) NOT NULL,          -- pgvector; HNSW index per partition (R2/R3)
    embed_model         TEXT NOT NULL,                 -- model identity/version used to embed (Principle II)
    status              TEXT NOT NULL DEFAULT 'PUBLISHED' CHECK (status IN ('PUBLISHED','DEPRECATED')),
    source_candidate_id UUID REFERENCES capture_candidate(id),   -- provenance: the candidate published from
    supersedes_version  INT,
    deprecation_reason  TEXT,
    deprecated_at       TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, id),
    CONSTRAINT uq_knowledge_item_key_version UNIQUE (workspace_id, key, version)
) PARTITION BY LIST (workspace_id);

-- ---------------------------------------------------------------------------------------------------
-- Per-workspace partition + HNSW index creation. SECURITY DEFINER so it runs with the table owner's
-- rights: d2os_app is least-privilege and does NOT own knowledge_item, so it cannot CREATE a PARTITION
-- OF it directly — this function (owned by the Flyway owner) closes that gap while keeping the app role
-- least-privilege. Idempotent; returns true when it created the partition, false when it already existed.
-- Access to knowledge_item is always through the parent, so the parent's RLS + REVOKEs govern all DML;
-- per-partition grants are unnecessary (routed DML checks the parent).
-- ---------------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION create_knowledge_item_partition(ws_id UUID)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    part_name TEXT := 'knowledge_item_' || replace(ws_id::text, '-', '');
BEGIN
    IF to_regclass(format('public.%I', part_name)) IS NOT NULL THEN
        RETURN false;
    END IF;
    EXECUTE format('CREATE TABLE %I PARTITION OF knowledge_item FOR VALUES IN (%L)', part_name, ws_id);
    EXECUTE format('CREATE INDEX %I ON %I USING hnsw (embedding vector_cosine_ops)',
                   part_name || '_emb_hnsw', part_name);
    -- Match the parent's append-only stance for any direct-partition access (V8 default privileges
    -- would otherwise grant DELETE on this freshly-created partition).
    EXECUTE format('REVOKE DELETE ON %I FROM d2os_app', part_name);
    RETURN true;
END;
$$;

REVOKE ALL ON FUNCTION create_knowledge_item_partition(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION create_knowledge_item_partition(UUID) TO d2os_app;

-- The reserved system-global workspace (V2) needs its partition now so global KnowledgeItems can seed.
SELECT create_knowledge_item_partition('00000000-0000-0000-0000-000000000000');

-- ---------------------------------------------------------------------------------------------------
-- prefilter_finding — deterministic sensitivity/PII findings feeding the redaction step (FR-010, R7).
-- Append-only.
-- ---------------------------------------------------------------------------------------------------
CREATE TABLE prefilter_finding (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    candidate_id UUID NOT NULL REFERENCES capture_candidate(id),
    category     TEXT NOT NULL CHECK (category IN ('EMAIL','PHONE','ID_NUMBER','CREDENTIAL','TAGGED_SENSITIVE')),
    span_start   INT  NOT NULL,
    span_end     INT  NOT NULL,
    source       TEXT NOT NULL,                 -- 'PATTERN:<name>' or 'INTAKE_TAG:<field>' (T3-a)
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------------------------------
-- promotion_gate_record — one row per gate outcome per candidate (FR-013). Append-only. The partial
-- unique index enforces at-most-one PASS per (candidate, gate) so the pipeline cannot double-pass a gate.
-- ---------------------------------------------------------------------------------------------------
CREATE TABLE promotion_gate_record (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    candidate_id UUID NOT NULL REFERENCES capture_candidate(id),
    gate         TEXT NOT NULL CHECK (gate IN ('PREFILTER','CURATION','D4')),
    outcome      TEXT NOT NULL CHECK (outcome IN ('PASS','REJECT')),
    decision_id  UUID REFERENCES decision(id),  -- D4 human decision; NULL for the automated prefilter
    actor        TEXT NOT NULL,                 -- user id, 'system:prefilter', or Curator persona key+version
    detail       TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_promotion_gate_pass ON promotion_gate_record (candidate_id, gate)
    WHERE outcome = 'PASS';

-- ---------------------------------------------------------------------------------------------------
-- knowledge_affected_execution — deprecation flags (FR-015, R8). Append-only except the review_status
-- acknowledgement flip; never touches the flagged operation_execution row (history is never rewritten).
-- ---------------------------------------------------------------------------------------------------
CREATE TABLE knowledge_affected_execution (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id           UUID NOT NULL REFERENCES workspace(id),
    knowledge_item_key     TEXT NOT NULL,
    knowledge_item_version INT  NOT NULL,
    operation_execution_id UUID NOT NULL REFERENCES operation_execution(id),
    case_instance_id       UUID NOT NULL REFERENCES case_instance(id),
    review_status          TEXT NOT NULL DEFAULT 'OPEN' CHECK (review_status IN ('OPEN','REVIEWED')),
    flagged_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_affected_execution_review ON knowledge_affected_execution (review_status);

-- --- RLS (workspace isolation) ---------------------------------------------------------------------
ALTER TABLE capture_candidate            ENABLE ROW LEVEL SECURITY;
ALTER TABLE knowledge_item               ENABLE ROW LEVEL SECURITY;   -- applies through the parent to every partition
ALTER TABLE prefilter_finding            ENABLE ROW LEVEL SECURITY;
ALTER TABLE promotion_gate_record        ENABLE ROW LEVEL SECURITY;
ALTER TABLE knowledge_affected_execution ENABLE ROW LEVEL SECURITY;

CREATE POLICY ws_isolation_capture_candidate ON capture_candidate
    USING (workspace_id = current_setting('app.workspace_id', true)::uuid);
CREATE POLICY ws_isolation_knowledge_item ON knowledge_item
    USING (workspace_id = current_setting('app.workspace_id', true)::uuid);
CREATE POLICY ws_isolation_prefilter_finding ON prefilter_finding
    USING (workspace_id = current_setting('app.workspace_id', true)::uuid);
CREATE POLICY ws_isolation_promotion_gate_record ON promotion_gate_record
    USING (workspace_id = current_setting('app.workspace_id', true)::uuid);
CREATE POLICY ws_isolation_affected_execution ON knowledge_affected_execution
    USING (workspace_id = current_setting('app.workspace_id', true)::uuid);

-- --- Append-only enforcement (V8 default privileges already granted the base set; deny the rest) ----
REVOKE DELETE          ON knowledge_item               FROM d2os_app;   -- status→DEPRECATED only, never deleted
REVOKE DELETE          ON capture_candidate            FROM d2os_app;   -- status transitions, never deleted
REVOKE UPDATE, DELETE  ON prefilter_finding            FROM d2os_app;   -- append-only
REVOKE UPDATE, DELETE  ON promotion_gate_record        FROM d2os_app;   -- append-only
REVOKE DELETE          ON knowledge_affected_execution FROM d2os_app;   -- append-only + review_status flip
