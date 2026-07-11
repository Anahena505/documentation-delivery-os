-- V30 ShedLock coordination table (feature 008 US3, T024, research R5, data-model.md §1).
--
-- Coordinates once-per-cycle execution of the 9 @Scheduled jobs across instances via ShedLock's
-- JdbcTemplateLockProvider (see app/ShedLockConfig). This is OPERATIONAL, not domain state: it is NOT
-- RLS-scoped and NEVER read by domain logic — it is pure infrastructure coordination (Principle III:
-- rebuildable/irrelevant to domain truth). The projector's own d2os_projector role does not touch it;
-- the app's primary d2os_app datasource is the sole reader/writer.
--
-- Numbering: single global Flyway V-namespace; V29 was the previous highest (projection), so this is
-- V30. Lives under tenancy's migration dir but is owned by the app role regardless of module.
--
-- Column shape is exactly what ShedLock's JdbcTemplateLockProvider expects (name PK, lock_until,
-- locked_at, locked_by). lock_until bounds a dead holder so a crashed instance's lock frees on the
-- next cycle (a job becomes eligible again, never lost or double-applied).

CREATE TABLE IF NOT EXISTS shedlock (
    name       text        NOT NULL PRIMARY KEY,
    lock_until timestamptz NOT NULL,
    locked_at  timestamptz NOT NULL,
    locked_by  text        NOT NULL
);

-- The runtime app role (created by V8) needs full read/write on the lock rows. V8's ALTER DEFAULT
-- PRIVILEGES already grants SELECT/INSERT/UPDATE/DELETE on future tables to d2os_app, but grant
-- explicitly here too (belt-and-suspenders; ShedLock must UPDATE/DELETE its own rows to release locks).
GRANT SELECT, INSERT, UPDATE, DELETE ON shedlock TO d2os_app;
