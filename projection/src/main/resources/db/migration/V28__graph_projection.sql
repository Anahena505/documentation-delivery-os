-- V28 Graph projection derived tables (Phase 7, `projection` module — data-model.md, research
-- R1-R9, tasks.md T004/T005).
--
-- NOTE on migration numbering: data-model.md/plan.md/tasks.md name this V23, but by the time this
-- phase was implemented V23-V27 were already taken by later Phase 5/6 migrations (tenancy's
-- V23__workspace_retention.sql, catalog's V24/V25, governance's V26/V27) — Flyway's migration
-- stream is one global `classpath:db/migration` namespace shared by every module (see
-- app/src/main/resources/application.yml's `spring.flyway.locations`), not a per-module sequence.
-- Renumbered to the actual next-free integer, V28, in the same relative order the design docs
-- specify — the same renumbering convention V20's and V26/V27's own header notes already document
-- for this repo.
--
-- NOTE on the outbox cursor (a deliberate, documented deviation from the literal task text):
-- event_outbox.id (casecore's V4__case.sql) is a randomly generated UUID with no monotonic
-- ordering, but projection_checkpoint.outbox_watermark (below) needs a reliable "what has this
-- consumer already consumed" cursor for the projector (T008, a later phase) to do incremental
-- runs. Ordering by created_at alone is not safe under concurrent writes in the same commit
-- window (ties), and a random UUID has no ordering at all. This migration ALTERs event_outbox
-- (owned by casecore's V4 migration; this is an additive column, not a redefinition of that
-- table) to add a monotonic bigserial `seq` column, so outbox_watermark can be a real bigint
-- high-water mark ordered by `seq`, not by `id` or `created_at`.
ALTER TABLE event_outbox ADD COLUMN seq BIGSERIAL;
CREATE INDEX idx_event_outbox_seq ON event_outbox (seq);

-- ----------------------------------------------------------------------------------------------
-- graph_node / graph_edge (data-model.md GraphNode/GraphEdge, research R1-R3/R7) — the derived
-- graph itself. Every row is rebuildable from source tables; never authored directly (Principle
-- III). `generation` is the generational-rebuild column (research R4): live reads always filter
-- `generation = projection_state.live_generation`; a rebuild builds N+1 from zero and flips only
-- on a passing equivalence check (T009, a later phase).
-- ----------------------------------------------------------------------------------------------
CREATE TABLE graph_node (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    generation   INT  NOT NULL,
    node_type    TEXT NOT NULL,      -- CASE|SUBMISSION|ARTIFACT_REVISION|PACKAGE|DEFINITION_VERSION|
                                      -- KNOWLEDGE_ITEM_VERSION|OPERATION_EXECUTION|GATE|FEATURE|PROJECT
    natural_key  TEXT NOT NULL,      -- deterministic source identity (R3)
    label        TEXT NOT NULL,
    attributes   JSONB NOT NULL,
    source_kind  TEXT NOT NULL,      -- OUTBOX_EVENT|TRACE_LINK|DEPENDENCY|INJECTION_SNAPSHOT (R2)
    source_ref   TEXT NOT NULL,      -- event id / edge row id / snapshot id — FR-003: no unsourced element
    projected_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_graph_node_natural_key UNIQUE (workspace_id, generation, node_type, natural_key)
);

CREATE TABLE graph_edge (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES workspace(id),
    generation   INT  NOT NULL,
    edge_type    TEXT NOT NULL,      -- TRACES_TO|DEPENDS_ON|DERIVES_FROM|SATISFIES|INJECTED_INTO|
                                      -- PRODUCED|GATED_BY|BELONGS_TO
    from_node    UUID NOT NULL REFERENCES graph_node(id),
    to_node      UUID NOT NULL REFERENCES graph_node(id),
    attributes   JSONB NOT NULL,
    source_kind  TEXT NOT NULL,
    source_ref   TEXT NOT NULL,
    projected_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_graph_edge_identity
        UNIQUE (workspace_id, generation, edge_type, from_node, to_node, source_ref)
);

-- Both-direction traversal indexes (research R7) for TraceabilityQueryService's recursive CTEs
-- (T016, a later phase). The from_node UNIQUE constraint above already covers that direction as a
-- usable index prefix; to_node needs its own.
CREATE INDEX idx_graph_edge_from ON graph_edge (workspace_id, generation, from_node, edge_type);
CREATE INDEX idx_graph_edge_to   ON graph_edge (workspace_id, generation, to_node, edge_type);

-- ----------------------------------------------------------------------------------------------
-- projection_checkpoint / projection_state (data-model.md, research R4) — the projector's own
-- bookkeeping, not part of the graph itself.
-- ----------------------------------------------------------------------------------------------
CREATE TABLE projection_checkpoint (
    consumer         TEXT NOT NULL,             -- e.g. 'graph-projector'
    workspace_id     UUID NOT NULL REFERENCES workspace(id),
    outbox_watermark BIGINT NOT NULL DEFAULT 0,  -- last consumed event_outbox.seq (see ALTER above)
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, workspace_id)
);

CREATE TABLE projection_state (
    workspace_id            UUID PRIMARY KEY REFERENCES workspace(id),
    live_generation          INT NOT NULL DEFAULT 0,   -- RebuildJob's atomic-flip target (R4)
    last_equivalence_check   TIMESTAMPTZ,
    last_equivalence_result  TEXT
);

-- ----------------------------------------------------------------------------------------------
-- projection_gap (data-model.md ProjectionGap, research R6/FR-011) — projection-sufficiency
-- findings. data-model.md's own prose calls this "append-only" but then documents status
-- transitioning OPEN -> RESOLVED (e.g. after an emitter fix + rebuild) in the very next line —
-- that is a real, expected UPDATE, not an append-only stream like audit_entry/event_outbox (V8)
-- or knowledge_injection_snapshot (persona V14). Resolving that contradiction in the doc's favor
-- of the STATE MACHINE (not the append-only label): this table gets the standard d2os_app grant
-- set, no REVOKE UPDATE/DELETE — a gap finding must be markable RESOLVED in place.
-- ----------------------------------------------------------------------------------------------
CREATE TABLE projection_gap (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id   UUID NOT NULL,
    event_id       UUID NOT NULL,
    event_type     TEXT NOT NULL,
    missing_fields TEXT[] NOT NULL,
    detected_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    status         TEXT NOT NULL DEFAULT 'OPEN'   -- OPEN -> RESOLVED
);

-- --- RLS (Principle IV) --------------------------------------------------------------------------
ALTER TABLE graph_node            ENABLE ROW LEVEL SECURITY;
ALTER TABLE graph_edge            ENABLE ROW LEVEL SECURITY;
ALTER TABLE projection_checkpoint ENABLE ROW LEVEL SECURITY;
ALTER TABLE projection_state      ENABLE ROW LEVEL SECURITY;
ALTER TABLE projection_gap        ENABLE ROW LEVEL SECURITY;

CREATE POLICY ws_isolation_graph_node ON graph_node
    USING (workspace_id = current_setting('app.workspace_id', true)::uuid);
CREATE POLICY ws_isolation_graph_edge ON graph_edge
    USING (workspace_id = current_setting('app.workspace_id', true)::uuid);
CREATE POLICY ws_isolation_projection_checkpoint ON projection_checkpoint
    USING (workspace_id = current_setting('app.workspace_id', true)::uuid);
CREATE POLICY ws_isolation_projection_state ON projection_state
    USING (workspace_id = current_setting('app.workspace_id', true)::uuid);
CREATE POLICY ws_isolation_projection_gap ON projection_gap
    USING (workspace_id = current_setting('app.workspace_id', true)::uuid);

-- ----------------------------------------------------------------------------------------------
-- d2os_projector role (research R2, sole-writer enforcement, tasks.md T005, Principle III).
-- Mirrors V8__app_role.sql's d2os_app bootstrap convention exactly: the CREATE ROLE block is a
-- local-dev/test bootstrap only (a real deployment provisions the role/password via
-- infra-as-code / a secrets manager); the GRANT/REVOKE statements below are the actual security
-- contract. Password comes from the Flyway placeholder ${projectorrolepassword} (bound to the
-- D2OS_DB_PROJECTOR_PASSWORD env var in application.yml), never hardcoded here.
-- ----------------------------------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'd2os_projector') THEN
        CREATE ROLE d2os_projector LOGIN PASSWORD '${projectorrolepassword}';
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO d2os_projector;

-- Sole writer of the five graph/projector-owned tables (R2/FR-001, Principle III).
GRANT SELECT, INSERT, UPDATE, DELETE ON
    graph_node, graph_edge, projection_checkpoint, projection_state, projection_gap
    TO d2os_projector;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO d2os_projector;

-- Read-only access to every source table the projector consumes (research R2's "SELECT on the
-- read sources"): outbox-derived facts, the polymorphic edge tables, injection snapshots,
-- case/feature/project lineage, artifact/package facts, gate lifecycle, definitions, and the
-- read-only kpi_sample stream the influence dashboard (a later phase) joins against.
GRANT SELECT ON
    event_outbox, case_instance, workflow_instance, decision,
    feature, project, project_version, problem_submission,
    artifact, artifact_revision, execution_package, handover_record, trace_link, dependency,
    knowledge_injection_snapshot, knowledge_item,
    gate_instance, delta_report, definition_asset, kpi_sample
    TO d2os_projector;

-- d2os_app is SELECT-only on the graph tables (R2/FR-001, Principle III). V8__app_role.sql's
-- `ALTER DEFAULT PRIVILEGES ... GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO d2os_app` already
-- applied the full read/write set to these five tables the moment Flyway (running as the owner
-- role) created them above — that default grant must be explicitly clawed back here so the
-- projector role stays the graph's sole writer; without this REVOKE, d2os_app could still write
-- the "derived" tables directly and the whole sole-writer invariant would be a fiction.
REVOKE INSERT, UPDATE, DELETE ON
    graph_node, graph_edge, projection_checkpoint, projection_state, projection_gap
    FROM d2os_app;
GRANT SELECT ON
    graph_node, graph_edge, projection_checkpoint, projection_state, projection_gap
    TO d2os_app;
