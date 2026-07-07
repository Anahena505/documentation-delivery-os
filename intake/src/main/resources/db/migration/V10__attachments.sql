-- V10 Attachment sandbox surface (Phase 2, US5 / FR-015 / T1-d, data-model.md).
-- Raw uploaded bytes live in the object store; these rows track lifecycle + provenance. Personas
-- only ever consume `attachment_summary.summary_text` — the raw content is never interpolated into
-- a prompt (AD-12 extended to uploads). Both tables are workspace-scoped and RLS-enforced.

CREATE TABLE attachment (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id     UUID NOT NULL REFERENCES workspace(id),
    submission_id    UUID NOT NULL REFERENCES problem_submission(id),
    filename         TEXT NOT NULL,                 -- display only, never used as a storage path
    content_type     TEXT NOT NULL,                 -- must be in the allowlist at upload time
    size_bytes       BIGINT NOT NULL,
    object_key       TEXT NOT NULL,                 -- workspace-scoped object-store key
    content_hash     TEXT NOT NULL,                 -- SHA-256 of stored bytes (Principle III)
    status           TEXT NOT NULL DEFAULT 'RECEIVED'
                       CHECK (status IN ('RECEIVED','EXTRACTING','SUMMARIZED','REJECTED')),
    rejection_reason TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_attachment_submission ON attachment (submission_id);

-- The sanitized representation personas may consume. 1:1 with a SUMMARIZED attachment. Summarization
-- happens at UPLOAD time — before any Case (and therefore any persona_invocation/operation_execution)
-- exists — so the reproducibility snapshot of the summarize call (Principle II) is carried INLINE here
-- rather than via an operation_execution FK: model id/version, the SHA-256 of the sandbox-extracted
-- text that was summarized, and the SHA-256 of the resulting summary. With the stored raw bytes those
-- fields let the replay harness (T049) reproduce and byte-compare the summary.
CREATE TABLE attachment_summary (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id           UUID NOT NULL REFERENCES workspace(id),
    attachment_id          UUID NOT NULL UNIQUE REFERENCES attachment(id),
    extracted_chars        INT NOT NULL,
    extracted_text_hash    TEXT NOT NULL,         -- SHA-256 of the sandbox-extracted text (replay input)
    summary_text           TEXT NOT NULL,         -- the ONLY representation a persona may consume
    summary_hash           TEXT NOT NULL,         -- SHA-256 of summary_text (replay compare)
    model_id               TEXT NOT NULL,         -- provider/model identity of the summarize call
    model_version          TEXT NOT NULL,
    tokens_used            BIGINT NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE attachment         ENABLE ROW LEVEL SECURITY;
ALTER TABLE attachment_summary ENABLE ROW LEVEL SECURITY;

CREATE POLICY ws_isolation_attachment ON attachment
    USING (workspace_id = current_setting('app.workspace_id', true)::uuid);
CREATE POLICY ws_isolation_attachment_summary ON attachment_summary
    USING (workspace_id = current_setting('app.workspace_id', true)::uuid);

-- Explicit grants (V8's ALTER DEFAULT PRIVILEGES should also cover these; belt-and-suspenders).
GRANT SELECT, INSERT, UPDATE, DELETE ON attachment, attachment_summary TO d2os_app;
