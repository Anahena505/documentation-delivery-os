-- V25 Studio draft lifecycle + copy-on-subscribe schema (Phase 6, Catalog Studio, catalog —
-- data-model.md, tasks.md T004, research R2/R4/R5/R6).
--
-- NUMBERING NOTE: data-model.md/plan.md/tasks.md name this V21 (catalog) and V22 (governance,
-- V26__gate_subject_polymorphic.sql), but V21 (casecore's audit hash chain), V22 (artifacts'
-- package access grants), V23 (tenancy's workspace retention), and V24 (catalog's own governance
-- definition types, the migration immediately before this one) were already taken by the time
-- Phase 6 was implemented — Flyway's migration stream is one global `classpath:db/migration`
-- namespace shared by every module (see V20's and V24's headers for the same renumbering
-- convention). Renumbered to the actual next-free integers: V25 (catalog, this file) and V26
-- (governance) — same relative order the design docs specify.

-- ---------------------------------------------------------------------------------------------
-- definition_asset: add the InReview status (Draft -> InReview -> Published -> Deprecated,
-- research R2) and fork/copy-on-subscribe provenance columns (research R4/R6, FR-012/FR-015).
-- Same CHECK-widening pattern as V15 (kpi_sample_metric_check) / V24 (definition_asset_type_check)
-- — the inline CHECK from V3 is auto-named definition_asset_status_check.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE definition_asset DROP CONSTRAINT IF EXISTS definition_asset_status_check;
ALTER TABLE definition_asset ADD CONSTRAINT definition_asset_status_check CHECK (status IN
    ('Draft','InReview','Published','Deprecated'));

-- derived_from_id: authoritative in-catalog fork lineage (R4, FR-012) — a DIFFERENT concept from
-- the existing derived_from_key/derived_from_version text columns (V3), which remain for v0-import
-- lineage. copied_from_id: copy-on-subscribe provenance to the Global-library source row (R6,
-- FR-015). Both nullable, both self-referencing definition_asset — additive, no mutation of
-- existing rows (Constitution Principle I: Published content stays untouched).
ALTER TABLE definition_asset
    ADD COLUMN derived_from_id uuid NULL REFERENCES definition_asset(id),
    ADD COLUMN copied_from_id  uuid NULL REFERENCES definition_asset(id);

-- ---------------------------------------------------------------------------------------------
-- library_subscription: the audited record of a copy-on-subscribe act (R6, T4-d, data-model.md
-- "New Entities"). Same RLS + grant convention as every other workspace-scoped table (e.g. V22's
-- package_access_grant).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE library_subscription (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id          UUID NOT NULL REFERENCES workspace(id),
    source_definition_id  UUID NOT NULL REFERENCES definition_asset(id),
    copied_definition_id  UUID NOT NULL REFERENCES definition_asset(id),
    subscribed_by         TEXT NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Subscribe once per (workspace, source version) — re-subscribing the same source is a 409
    -- (US4 CopyOnSubscribeIT), not a duplicate copy.
    CONSTRAINT uq_library_subscription_workspace_source UNIQUE (workspace_id, source_definition_id)
);

CREATE INDEX idx_library_subscription_workspace ON library_subscription (workspace_id);
CREATE INDEX idx_library_subscription_source ON library_subscription (source_definition_id);

ALTER TABLE library_subscription ENABLE ROW LEVEL SECURITY;

CREATE POLICY ws_isolation_library_subscription ON library_subscription
    USING (workspace_id = current_setting('app.workspace_id', true)::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON library_subscription TO d2os_app;

-- ---------------------------------------------------------------------------------------------
-- case_definition_snapshot.entries: GIN index for exact-pin JSONB containment (research R5,
-- SC-005) — DeprecationImpactService (Phase 5, US3, T020) queries "does any non-terminal case's
-- pinned entries set contain {type,key,version}", which is a containment (@>) query over the
-- entries array; jsonb_path_ops is the smaller/faster index operator class for pure containment
-- queries (no key-existence or range operators needed here).
-- ---------------------------------------------------------------------------------------------
CREATE INDEX ix_case_definition_snapshot_entries_gin
    ON case_definition_snapshot USING GIN (entries jsonb_path_ops);
