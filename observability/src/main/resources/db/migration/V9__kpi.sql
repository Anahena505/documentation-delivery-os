-- V9 KPI instrumentation (E1.9, §KP). case_cost_tokens doubles as Q10 billing telemetry later.

CREATE TABLE kpi_sample (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id     UUID NOT NULL REFERENCES workspace(id),
    metric           TEXT NOT NULL CHECK (metric IN
                       ('rubric_first_pass_rate','package_completeness','case_cost_tokens')),
    case_instance_id UUID,
    value            DOUBLE PRECISION NOT NULL,
    at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_kpi_metric ON kpi_sample (metric, at);

ALTER TABLE kpi_sample ENABLE ROW LEVEL SECURITY;
CREATE POLICY ws_isolation_kpi ON kpi_sample
    USING (workspace_id = current_setting('app.workspace_id', true)::uuid);
