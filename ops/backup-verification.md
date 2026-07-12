# Automated Backup Verification

Feature 008, T064 (FR-017, enhancement E13). Companion script: [`backup-verify.sh`](backup-verify.sh).

A backup you have never restored is a hypothesis, not a backup. `ops/dr-rehearsal.sh` is the *manual,
full-shape* rehearsal (real Flyway schema + app-level integrity checks) run on demand. This document
specifies the **scheduled, unattended** counterpart: a job that continuously proves the *latest base
backup is restorable and internally consistent*, so a real incident is never the first time a restore
is attempted.

## What the job does

On a schedule (nightly, after the base backup in `ops/dr-drill.md`'s Backup Regime completes), the
job:

1. **Locates the latest base backup** — the most recent `pg_basebackup` tar written to the
   `d2os-wal-archive` volume (`/wal-archive/base-<date>`), plus the archived WAL segments alongside
   it.
2. **Restores it into a throwaway scratch instance** — never the live cluster. Extract the base
   backup into a fresh data directory, add `recovery.signal` + `restore_command` +
   `recovery_target_time` (latest), start Postgres so it replays WAL forward and promotes. This is
   exactly the mechanism `ops/dr-drill.md` §Restore Procedure describes and `ops/dr-rehearsal.sh`
   automates — the verification job reuses that path against the *latest* backup rather than a
   freshly-taken one.
3. **Runs `AuditChainVerifier` automatically** against the restored instance — for every workspace
   with recent activity, `POST /api/v1/audit/chain/verify` (see `AuditChainController` /
   `AuditChainVerifier.verifyWorkspace`) must report `intact=true`. An intact hash chain proves the
   restored snapshot is self-consistent (not a torn mid-transaction write) — the strongest cheap
   integrity signal available without a full smoke Case.
4. **Reports pass/fail** — a failed restore, a failed promotion, or any `intact=false` chain marks
   the run RED and pages an operator (wire into the alerting from feature 008 US2). A green run
   records the verified backup id + timestamp so backup freshness itself becomes an observable metric.
5. **Tears down the scratch instance** — the job is disposable and leaves no residue.

## Why this is separate from the DR rehearsal

| | `dr-rehearsal.sh` (T063) | backup-verification (T064) |
|---|---|---|
| Cadence | On demand (before a release; periodic full drill) | Scheduled, unattended (nightly) |
| Backup used | A backup taken *by the rehearsal itself* | The *latest production* base backup |
| Scope | Full: schema + AuditChainVerifier + smoke Case to Delivered | Restore + AuditChainVerifier only (no smoke Case — kept fast) |
| Question answered | "Does recovery of *this system* work end to end?" | "Is *last night's* backup actually restorable & consistent?" |

The rehearsal proves the *procedure*; the verification job proves each *artifact* the procedure will
one day depend on.

## Scheduling

Run as a Kubernetes `CronJob` (or host cron) on a Docker/Postgres-capable node — the same
requirement as the DR rehearsal (the app-level `AuditChainVerifier` step needs the booted app, so
this cannot run in the delivery sandbox). Suggested cadence: nightly, ~1 h after the base backup so
the freshest backup is the one under test.

## Companion script

`ops/backup-verify.sh` is the scriptable skeleton of steps 1–2 and 5 (locate latest backup, restore
to scratch, teardown). Step 3 (`AuditChainVerifier` against the booted app) needs the running
application and is left as a clearly-marked TODO with the exact intended command — identical to the
app-level TODOs in `ops/dr-rehearsal.sh`. The script is `set -euo pipefail` and passes `bash -n`.
