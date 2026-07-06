-- V1 baseline: extensions + reserved system workspace for global catalog rows.
CREATE EXTENSION IF NOT EXISTS "pgcrypto";   -- gen_random_uuid()
CREATE EXTENSION IF NOT EXISTS "vector";      -- pgvector (provisioned now, exercised in Phase 3)

-- Reserved workspace id for global-library / system-owned catalog rows (Principle IV exception).
-- Actual workspace table is created in V2; this comment documents the reserved UUID contract.
-- Reserved system workspace: 00000000-0000-0000-0000-000000000000
