-- V21 Audit hash-chaining (Phase 5, T6-b, research R5) + gate decision-verb support on `decision`
-- (Phase 5 US1, FR-002/003/004). Casecore-owned migration: both audit_chain_segment (new table) and
-- the decision.decision_type CHECK relaxation touch tables casecore itself owns (audit_entry's
-- sibling and V4's `decision`), so this stays in casecore rather than a cross-module migration from
-- governance — matching this repo's existing convention that a table's owning module's migrations
-- alter that table (e.g. V19 above, and V17/V18 elsewhere, are all same-module column changes).
--
-- Numbering: see governance's V20 migration header — this repo's V17/V18/V19 were already taken by
-- Phase 4 (tenancy/intake/casecore) migrations by the time Phase 5 was implemented, so this is V21,
-- not the V18 the design docs name.

-- ---------------------------------------------------------------------------------------------------
-- decision.decision_type CHECK relaxation: V4 (casecore) restricted decision_type to the D1-D4
-- decision-point codes. Phase 5's GateService.decide() (T014, later phase) records real Decision rows
-- for the three gate verbs (APPROVE/REJECT/REQUEST_CHANGES) plus the Phase 5 US3 reopen decision
-- (REOPEN) — none of which are semantically 'D4' (D4 already means the specific Knowledge Curator
-- promotion gate, PromotionGateService). Adding distinct GATE_* values (rather than overloading D4)
-- keeps decision_type an honest, queryable discriminator between the two gate families.
-- ---------------------------------------------------------------------------------------------------
-- (the inline single-column CHECK from V4 is auto-named decision_decision_type_check; drop-if-exists
-- guards against a differing name — same precautionary convention as observability's V15).
ALTER TABLE decision DROP CONSTRAINT IF EXISTS decision_decision_type_check;
ALTER TABLE decision ADD CONSTRAINT decision_decision_type_check CHECK (decision_type IN
    ('D1','D2','D3','D4','GATE_APPROVE','GATE_REJECT','GATE_REQUEST_CHANGES','GATE_REOPEN'));

-- ---------------------------------------------------------------------------------------------------
-- audit_chain_segment — periodic tamper-evidence seal over the append-only audit_entry stream
-- (T6-b, R5). Append-only, per-workspace chain: AuditChainSealer (Phase 7, US5) seals consecutive
-- audit_entry ranges hourly; AuditChainVerifier recomputes on demand/schedule, any mismatch (altered
-- or deleted sealed entry) is a tamper alert.
-- ---------------------------------------------------------------------------------------------------
CREATE TABLE audit_chain_segment (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id       UUID NOT NULL REFERENCES workspace(id),
    segment_seq        BIGINT NOT NULL,
    from_entry_id      UUID NOT NULL REFERENCES audit_entry(id),
    to_entry_id        UUID NOT NULL REFERENCES audit_entry(id),
    entry_count        INT NOT NULL,
    segment_hash       TEXT NOT NULL,             -- SHA-256 over canonical serialization of the range
    prev_segment_hash  TEXT NOT NULL,              -- genesis = 64 * '0'
    sealed_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_verified_at   TIMESTAMPTZ,                -- set by AuditChainVerifier
    CONSTRAINT uq_audit_chain_segment_seq UNIQUE (workspace_id, segment_seq)
);

CREATE INDEX idx_audit_chain_segment_workspace ON audit_chain_segment (workspace_id, segment_seq DESC);

ALTER TABLE audit_chain_segment ENABLE ROW LEVEL SECURITY;

CREATE POLICY ws_isolation_audit_chain_segment ON audit_chain_segment
    USING (workspace_id = current_setting('app.workspace_id', true)::uuid);

-- Append-only (matches audit_entry's own T6-a contract): grant INSERT/SELECT/UPDATE (last_verified_at
-- must be settable by AuditChainVerifier) but never DELETE — a sealed segment is never removed.
GRANT SELECT, INSERT, UPDATE ON audit_chain_segment TO d2os_app;
REVOKE DELETE ON audit_chain_segment FROM d2os_app;
