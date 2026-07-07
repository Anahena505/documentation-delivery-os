-- ===================================================================================================
-- V16: knowledge_item version immutability (Phase 3 review fix — replay integrity, FR-007/FR-016).
--
-- A knowledge_item row is the byte-for-byte source that injection snapshots (V14) soft-reference so a
-- deprecated/superseded item still replays exactly (FR-007). V13 already REVOKEs DELETE, closing one
-- silent break (removal). This closes the other: mutating a published version's content/hash/embedding/
-- identity in place — which would invalidate every past snapshot's content_hash with no detection path,
-- since d2os_app retains UPDATE (needed for the deprecation status flip). We keep that UPDATE grant but
-- constrain it with a BEFORE UPDATE trigger: only the three deprecation-governance columns
-- (status, deprecated_at, deprecation_reason) may change. Everything else is append-only — a new fact is
-- a new version (a new row), never an edit. A trigger (not column-level GRANT) is used deliberately so it
-- coexists with Hibernate's full-row UPDATE on the deprecation path: re-writing the immutable columns to
-- their existing values is allowed; changing any of them is not.
--
-- Row-level triggers on a LIST-partitioned parent propagate to all partitions (existing and future) in
-- PostgreSQL 13+, so no change to create_knowledge_item_partition (V13) is needed.
-- ===================================================================================================

CREATE OR REPLACE FUNCTION knowledge_item_immutable() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.workspace_id        IS DISTINCT FROM OLD.workspace_id
       OR NEW.key              IS DISTINCT FROM OLD.key
       OR NEW.version          IS DISTINCT FROM OLD.version
       OR NEW.scope_level      IS DISTINCT FROM OLD.scope_level
       OR NEW.scope_ref        IS DISTINCT FROM OLD.scope_ref
       OR NEW.tags             IS DISTINCT FROM OLD.tags
       OR NEW.locale           IS DISTINCT FROM OLD.locale
       OR NEW.title            IS DISTINCT FROM OLD.title
       OR NEW.content          IS DISTINCT FROM OLD.content
       OR NEW.content_hash     IS DISTINCT FROM OLD.content_hash
       OR NEW.embedding::text  IS DISTINCT FROM OLD.embedding::text   -- cast: vector has no default = for DISTINCT
       OR NEW.embed_model      IS DISTINCT FROM OLD.embed_model
       OR NEW.source_candidate_id IS DISTINCT FROM OLD.source_candidate_id
       OR NEW.supersedes_version  IS DISTINCT FROM OLD.supersedes_version
       OR NEW.created_at       IS DISTINCT FROM OLD.created_at
    THEN
        RAISE EXCEPTION 'knowledge_item is append-only: only status/deprecated_at/deprecation_reason may change (immutable column mutated on key=% version=%)', OLD.key, OLD.version;
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_knowledge_item_immutable ON knowledge_item;
CREATE TRIGGER trg_knowledge_item_immutable
    BEFORE UPDATE ON knowledge_item
    FOR EACH ROW EXECUTE FUNCTION knowledge_item_immutable();
