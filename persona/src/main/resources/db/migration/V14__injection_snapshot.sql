-- V14 Per-execution knowledge injection snapshot (Phase 3, US1 — data-model.md, research R5/R9).
-- Written by OperationExecutionRecorder (T014) in the SAME transaction as the operation_execution row,
-- so an execution never exists without its full knowledge provenance (Principle II/III). Immutable /
-- append-only: replay (T016) reconstructs the injected slot by ordering on `position` and verifying
-- `content_hash` against the snapshotted item version, NOT the item's current state (FR-007).
--
-- knowledge_item_id/key/version are a SOFT reference (no FK): the snapshot must remain valid and
-- byte-identical even after the item is deprecated or superseded, and knowledge_item is partitioned in
-- another module — decoupling keeps the snapshot's integrity independent of the item's lifecycle.

CREATE TABLE knowledge_injection_snapshot (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id           UUID NOT NULL REFERENCES workspace(id),
    operation_execution_id UUID NOT NULL REFERENCES operation_execution(id),
    knowledge_item_id      UUID NOT NULL,     -- soft ref (item is partitioned; snapshot decoupled)
    knowledge_item_key     TEXT NOT NULL,
    knowledge_item_version INT  NOT NULL,     -- exact (key,version), never a mutable pointer (AD-1)
    content_hash           TEXT NOT NULL,     -- hash of injected content at injection time (replay verify)
    position               INT  NOT NULL,     -- order within the envelope's knowledge slot (byte-identical replay)
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_injection_snapshot_position UNIQUE (operation_execution_id, position)
);

CREATE INDEX idx_injection_snapshot_op ON knowledge_injection_snapshot (operation_execution_id);
-- Fast reverse lookup for deprecation flagging (R8): find executions that injected a given item version.
CREATE INDEX idx_injection_snapshot_item ON knowledge_injection_snapshot (knowledge_item_key, knowledge_item_version);

ALTER TABLE knowledge_injection_snapshot ENABLE ROW LEVEL SECURITY;
CREATE POLICY ws_isolation_injection_snapshot ON knowledge_injection_snapshot
    USING (workspace_id = current_setting('app.workspace_id', true)::uuid);

-- Immutable append-only (V8 default privileges granted the base set; deny mutation).
REVOKE UPDATE, DELETE ON knowledge_injection_snapshot FROM d2os_app;

-- Influence evaluations (US4, R9) run real, snapshot-recorded operations that must NEVER feed delivery.
-- The flag lets those paired with/without runs be excluded from package assembly and normal listings.
ALTER TABLE operation_execution ADD COLUMN evaluation BOOLEAN NOT NULL DEFAULT false;
