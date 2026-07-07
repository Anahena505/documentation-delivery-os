-- V15 Knowledge-influence KPI dimension (Phase 3, US4 — FR-018, research R9).
--
-- The Phase-1 kpi_sample (V9) CHECK-constrained `metric` to three names and has NO attribution column.
-- The knowledge-influence KPI needs a fourth metric plus per-item (key,version) attribution so a delta
-- sample is traceable to the exact KnowledgeItem version it measures. Both changes are additive and
-- backward-compatible: existing samples keep the three original metrics and default to `{}` dimensions.
--
-- NOTE (spec reconciliation): tasks.md T033 assumed "reuse V9, no schema change", but the V9 CHECK
-- structurally forbids a new metric name and there is nowhere to record (key,version). This minimal
-- widening is the smallest change that lets the metric be emitted through the existing kpi_sample stream
-- rather than inventing a parallel table.

-- Widen the metric allow-list to admit the influence KPI (the inline single-column CHECK from V9 is
-- auto-named kpi_sample_metric_check; drop-if-exists guards against a differing name).
ALTER TABLE kpi_sample DROP CONSTRAINT IF EXISTS kpi_sample_metric_check;
ALTER TABLE kpi_sample ADD CONSTRAINT kpi_sample_metric_check CHECK (metric IN
    ('rubric_first_pass_rate','package_completeness','case_cost_tokens','knowledge_influence'));

-- Per-item attribution for knowledge_influence samples: {"key":..., "version":...}. Empty {} for the
-- three original metrics (which are case-scoped, not item-scoped).
ALTER TABLE kpi_sample ADD COLUMN dimensions JSONB NOT NULL DEFAULT '{}'::jsonb;

-- Fast lookup of an item's influence series by (key,version) for GET /metrics/knowledge-influence.
CREATE INDEX ix_kpi_influence_dims ON kpi_sample ((dimensions->>'key'), (dimensions->>'version'))
    WHERE metric = 'knowledge_influence';
