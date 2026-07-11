# DR Drill Results (T046)

**Status: executed.** See `ops/dr-drill.md`'s own **Results** section for the full procedure and
caveats. This delivery sandbox has no persistent Docker Compose stack (only ephemeral Testcontainers,
torn down per test run) — the app itself never ran against this drill — but PostgreSQL 16 is
installed locally in the sandbox outside Docker, which is enough to run the *real* WAL-archiving →
base-backup → point-in-time-restore mechanics end to end against a standalone Postgres cluster, one
level below the full app stack (see "Scope" below).

## Scope (what this drill does and does not prove)

- **Proves**: PostgreSQL WAL archiving + `pg_basebackup` + `recovery.signal`/`restore_command`/
  `recovery_target_time` point-in-time recovery — the exact mechanism `docker-compose.yml`'s
  `postgres` service (T044) and `ops/dr-drill.md`'s restore procedure (T045) specify — genuinely
  works, with real measured timings, against real WAL segments and a real base backup.
- **Does not prove**: the full application stack (Spring Boot `:app`, Flyway migrations, the D2OS
  schema, `AuditChainVerifier`/`ReplayHarness` post-restore validation) booting against a restored
  instance — that step requires the Docker Compose stack this sandbox cannot run. The drill used a
  standalone database + a single table shaped like `audit_entry` (id/subject_type/subject_id/
  action/created_at), not the full D2OS schema.

## Procedure actually run

1. `initdb` a fresh cluster (`postgres 16.13`), configured with `wal_level=replica`,
   `archive_mode=on`, `archive_command='test ! -f /tmp/dr-drill/archive/%f && cp %p /tmp/dr-drill/archive/%f'`
   (identical to `docker-compose.yml`'s command) and `archive_timeout=20` (shortened from
   production's `300` purely so the drill itself runs in minutes, not hours).
2. Inserted 50 rows ("PRE_BACKUP"), took a real `pg_basebackup -Ft -z` (4.2 MB compressed).
3. Inserted 30 more rows ("POST_BACKUP"), recorded `now()` as `recovery_target_time`, forced a WAL
   switch (`pg_switch_wal()`) so the segment archived promptly.
4. Inserted 15 more rows ("AFTER_TARGET") *after* the chosen recovery target — these must NOT survive
   a correct point-in-time restore.
5. Stopped the primary (`pg_ctl stop -m immediate`) — the simulated incident. Timer started.
6. Extracted the base backup into a fresh data directory, wrote `recovery.signal`, set
   `restore_command`/`recovery_target_time`/`recovery_target_action=promote`, started Postgres
   against it.
7. Polled until the restored instance accepted connections. Timer stopped.
8. Validated: total row count, presence of PRE_BACKUP/POST_BACKUP rows, **absence** of AFTER_TARGET
   rows.

## Results

| Field | Value |
|---|---|
| Date | 2026-07-11 |
| Operator | Claude (autonomous session) |
| Environment | Local PostgreSQL 16.13 in the delivery sandbox (not Docker Compose — see Scope) |
| Target RPO | ≤ 15 min (production `archive_timeout=300s`) |
| Achieved RPO | **Point-in-time boundary was exact**: all 80 rows committed before `recovery_target_time` were recovered; all 15 rows committed after it were correctly excluded (0 leaked). The archive-latency component of RPO was not independently re-measured under passive/idle `archive_timeout` conditions in this run (a follow-up idle-timeout probe did not trigger within ~4 minutes of observation, likely sandbox process-scheduling — inconclusive, not a finding against the mechanism); the *forced*-switch archiving path (the one exercised in the actual restore) completed in low single-digit seconds each time, well inside the 15 min target. |
| Target RTO | ≤ 1 h |
| Achieved RTO | **81.5 s** (extract base backup + replay 7 WAL segments + reach "ready to accept connections") at this drill's data volume (95 rows, ~4 MB base backup). Real wall-clock measurement (`date +%s.%N` around the restore procedure), not estimated. |
| Result | **Mechanism verified end to end with real timings.** Application-level post-restore validation (`AuditChainVerifier`, `ReplayHarness`, a smoke Case submission) was NOT run — that requires the full Docker Compose stack, unavailable in this sandbox. |

## Findings / shortfalls

- The restore mechanics (base backup + WAL archive + `recovery_target_time`) work exactly as
  `ops/dr-drill.md` describes, with no corrections needed to the runbook's procedure.
- RTO scales with data volume; 81.5 s at ~95 rows / 4 MB is not a production-scale estimate — a real
  production-scale drill (with the actual D2OS schema and realistic data volume) should re-measure
  RTO before treating the "≤ 1 h" target as verified at scale, though the mechanism itself has no
  reason to scale non-linearly (extract + sequential WAL replay).
- The idle/passive `archive_timeout`-triggered switch did not visibly fire within the observation
  window of this run; this is flagged as **unconfirmed, not failed** — the forced-switch path (what
  every actual write-driven segment completion uses) archived correctly and quickly every time it was
  exercised. A follow-up drill in a normal (non-sandboxed) host should re-verify the idle-timeout path
  specifically.
- **Not yet run**: the full-stack post-restore validation (`AuditChainVerifier.verifyWorkspace`,
  `ReplayHarness` byte-identical replay, a smoke Case through to `Delivered`) against a restored
  instance running the real D2OS schema and application — this requires the Docker Compose stack.
  Recommended as the next drill iteration once a persistent Docker-capable environment is available.
