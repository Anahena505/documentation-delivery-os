-- V8 Least-privilege runtime role (fixes a real gap: without this, the app connects as the same
-- role that owns the tables via Flyway, and PostgreSQL exempts table owners from RLS by default —
-- meaning every RLS policy in V2/V3/V4/V5/V6/V7 would silently enforce nothing at runtime).
--
-- d2os_owner (or whatever runs Flyway) keeps owning every table and is unaffected by this.
-- d2os_app is what the application's JPA datasource connects as (see app/src/main/resources/
-- application.yml — spring.datasource.* now points at this role; spring.flyway.* keeps using the
-- owner). Because d2os_app does not own any table, every RLS policy applies to it in full.
--
-- Dev-only convenience: this migration creates the role with a fixed password matching
-- docker-compose.yml. A real deployment provisions this role (and its password/rotation) via
-- infra-as-code / a secrets manager, not a SQL migration — the GRANT/REVOKE statements below are
-- the actual security contract; the CREATE ROLE block is a local-dev bootstrap only.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'd2os_app') THEN
        CREATE ROLE d2os_app LOGIN PASSWORD 'd2os_app';
    END IF;
END
$$;

-- CREATE (not just USAGE) is required because Flowable manages its own ACT_*/FLW_* engine tables
-- via the same runtime datasource (database-schema-update=true) — a deliberate least-privilege
-- tradeoff: the app role can create tables (needed for Flowable's self-managed schema) but the
-- append-only REVOKE below still holds regardless of what creates a table.
GRANT USAGE, CREATE ON SCHEMA public TO d2os_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO d2os_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO d2os_app;

-- Append-only audit stream (T6-a, Principle V): UPDATE/DELETE denied even though the blanket
-- GRANT above included them — this is the actual enforcement point.
REVOKE UPDATE, DELETE ON audit_entry, event_outbox FROM d2os_app;

-- Ensure future tables created by this owner also grant d2os_app by default (belt-and-suspenders
-- for the next migration that adds a table and forgets to grant it explicitly).
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO d2os_app;
