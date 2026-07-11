# Disaster Recovery Drill Runbook

Phase 7 US6 (T044-T046, research R7, NFR-8). Target posture: **RPO ≤ 15 min, RTO ≤ 1 h**.

WAL-archiving compose config landed in T044 (`docker-compose.yml`'s `postgres` service —
`wal_level=replica`, `archive_mode=on`, `archive_command` copying every completed segment into the
`d2os-wal-archive` volume, `archive_timeout=300` so an idle system still archives at least every 5
minutes). This runbook is the restore procedure and drill checklist for that config.

## Prerequisites

- Docker Compose stack running (`docker compose up -d`) with T044's WAL-archiving config active.
- A completed base backup (see **Backup Regime** below) taken *after* archiving was enabled — a base
  backup taken before `archive_mode=on` has no continuous WAL stream to replay forward from.
- `.env` populated (`D2OS_DB_OWNER_USER`/`D2OS_DB_OWNER_PASSWORD`) — the restore procedure runs
  `pg_basebackup`/`pg_ctl` as the same owner role the compose stack already uses.
- A second, throwaway Postgres data directory to restore into (never restore over the live
  `d2os-postgres-data` volume in place — that destroys the ability to re-attempt the drill).

## Backup Regime

- **Continuous**: WAL archiving (T044) — every completed 16 MB segment, or every 300 s of activity
  (`archive_timeout`), whichever comes first, copied to `/wal-archive` inside the `postgres`
  container (backed by the `d2os-wal-archive` named volume).
- **Nightly base backup**: `docker compose exec postgres pg_basebackup -D /wal-archive/base-$(date
  +%F) -Ft -z -P -U ${D2OS_DB_OWNER_USER:-d2os_owner}` — a full physical copy of the data directory,
  taken once every 24 h. Point-in-time recovery replays WAL forward from the most recent base backup
  before the target restore time, not from the beginning of time — the base backup bounds how much WAL
  a restore ever has to replay, which is what keeps RTO bounded regardless of how long the system has
  been running.
- **Retention of backup artifacts themselves**: out of scope for this runbook — governed by ordinary
  object-store/volume retention, not `workspace.retention_years` (that column governs case/package data
  inside the database, a different retention concern entirely, per T041's `RetentionVerificationJob`).

## Restore Procedure

1. Stop the target Postgres container (or point a *new* one at a copy of the archived base backup +
   WAL — never restore into the live volume in place).
2. Extract the most recent base backup (`pg_basebackup`'s tar output) into a fresh data directory.
3. Create `recovery.signal` in that data directory (Postgres 12+ replaces `recovery.conf`) and set
   `restore_command = 'cp /wal-archive/%f %p'` plus `recovery_target_time = '<timestamp>'` in
   `postgresql.conf` (or a `postgresql.auto.conf` override) — `<timestamp>` is the target
   point-in-time, normally "as close to the incident as WAL archiving reached."
4. Start Postgres against the restored data directory. It replays WAL from the base backup forward to
   `recovery_target_time`, then either pauses (if `recovery_target_action = 'pause'`) or promotes.
5. Point the application at the restored instance (`spring.datasource.url` / `spring.flyway.url`) and
   boot `:app:bootRun` (or the equivalent container) against it.
6. Run the **Post-Restore Validation** suites below before declaring the drill/incident resolved.

## RPO / RTO Measurement

- **RPO** = the gap between `recovery_target_time` (what was actually recovered) and the true incident
  time (what was lost). Bounded above by `archive_timeout` (300 s) under normal load, plus however long
  it takes to notice the incident and pick a target time — WAL archiving itself doesn't lose more than
  one segment's worth of in-flight writes.
- **RTO** = wall-clock from "restore procedure begins" (step 1 above) to "post-restore validation
  passes" (step 6) — extract + replay + app boot + validation, end to end.
- Both are measured by actually timing a real drill run, not estimated from the config alone — see
  **Results** below.

## Post-Restore Validation

Run against the restored instance before considering the drill (or a real incident recovery) complete:

- `ReplayHarness`-backed replay-audit on a sample of recently-delivered Cases — byte-identical replay
  proves the restored artifact content and its recorded hashes still agree (same guarantee
  `ParallelReplayIT`/`KnowledgeReplayIT` check in normal CI, run here against the restored DB instead).
- `AuditChainVerifier.verifyWorkspace` (T039) for every workspace with recent activity —
  `POST /audit/chain/verify` — confirms the restored audit stream's hash chain is still intact (proves
  the restore captured a self-consistent snapshot, not a torn write mid-transaction).
- Smoke: submit a new Case through to `Delivered` against the restored instance, confirming the schema
  and application are fully functional post-restore, not just readable.

## Results

**A drill has been executed** — see `specs/005-governance-review-gates/quickstart-results.md` for the
full write-up. Summary: this sandbox has no live, persistent Docker Compose stack (Testcontainers
ephemeral containers only), but PostgreSQL 16 is installed locally outside Docker, which was enough to
run the actual archive → base backup → point-in-time restore mechanism end to end against a standalone
cluster — real `pg_basebackup`, real archived WAL segments, real `recovery.signal`/`restore_command`/
`recovery_target_time`, real measured wall-clock RTO (81.5 s at drill scale), and an exact point-in-time
boundary (0 rows leaked past the recovery target). What this run did NOT cover: the full application
stack (Spring Boot, Flyway, the real D2OS schema, `AuditChainVerifier`/`ReplayHarness` post-restore
validation) booting against a restored instance, since that requires the Docker Compose stack this
sandbox cannot run.

**Treat the core restore mechanism as verified; treat the RPO ≤ 15 min / RTO ≤ 1 h targets as verified
in mechanism but not yet at production scale/schema.** Recommended before full production trust: re-run
this drill once in a persistent, Docker-capable environment against the real D2OS schema and a
realistic data volume, including the full Post-Restore Validation suites (§ above) and a deliberate
idle-period archive_timeout probe (this run's idle-timeout sub-check was inconclusive, not failed — see
quickstart-results.md's Findings).

## Sign-off

Drill executed 2026-07-11 by Claude (autonomous session) against a standalone local PostgreSQL 16
cluster (not the Docker Compose stack — see **Results** above for scope). Restore mechanism confirmed
working with real measured RTO = 81.5 s and an exact point-in-time recovery boundary. Full-stack
post-restore validation remains outstanding pending a Docker-capable persistent environment — see
`specs/005-governance-review-gates/quickstart-results.md` for the complete results and findings.
