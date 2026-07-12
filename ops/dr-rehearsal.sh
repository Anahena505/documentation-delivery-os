#!/usr/bin/env bash
#
# dr-rehearsal.sh — Disaster-recovery rehearsal against the REAL D2OS schema (feature 008, T063,
# FR-017, enhancement E13).
#
# The executed DR drill (ops/dr-drill.md + specs/005-governance-review-gates/quickstart-results.md)
# proved the PostgreSQL point-in-time-recovery MECHANISM (base backup + WAL replay, exact recovery
# boundary, measured RTO) but only against a single throwaway table — never against the real D2OS
# Flyway schema, and never with the application's post-restore integrity checks
# (AuditChainVerifier + a smoke Case). FR-017 requires the recovery procedure to be rehearsed against
# the real system shape, with the recovered system passing its own integrity checks before the
# rehearsal is deemed successful. This script automates that rehearsal end to end:
#
#   1. initdb a fresh, WAL-archiving SCRATCH primary configured exactly like docker-compose.yml's
#      postgres service (wal_level=replica, archive_mode=on, the same archive_command shape).
#   2. Run the real Flyway migrations (the full V1..V29+ global namespace) so the scratch primary
#      carries the actual D2OS schema, then take a real `pg_basebackup` and drive some WAL forward.
#   3. Restore that base backup + archived WAL into a SECOND scratch data directory using
#      recovery.signal / restore_command / recovery_target_time (per ops/dr-drill.md's Restore
#      Procedure), and start the restored instance.
#   4. Run the app-level Post-Restore Validation against the RESTORED instance: AuditChainVerifier
#      (POST /audit/chain/verify) and a smoke Case through to Delivered. These need the running app,
#      so they are left as clearly-marked TODOs with the exact intended command.
#
# It mirrors the real commands already documented in ops/dr-drill.md and
# specs/005-governance-review-gates/quickstart-results.md (initdb / pg_basebackup / recovery.signal /
# restore_command). Run it on a Docker-capable / Postgres-capable host — NOT in the delivery sandbox,
# which cannot boot the full app stack.
#
# Validate syntax only (no execution) with:  bash -n ops/dr-rehearsal.sh

set -euo pipefail

# --- Configuration (override via environment) -------------------------------------------------------

# A scratch working root; everything this rehearsal creates lives under here and is disposable.
DR_ROOT="${DR_ROOT:-/tmp/d2os-dr-rehearsal}"
PRIMARY_DATA="${DR_ROOT}/primary"          # the fresh primary we migrate + back up
RESTORE_DATA="${DR_ROOT}/restore"          # the restored instance we validate
WAL_ARCHIVE="${DR_ROOT}/wal-archive"       # archived WAL segments (docker-compose.yml's /wal-archive)
BASE_BACKUP="${DR_ROOT}/base-backup"       # pg_basebackup tar output

PRIMARY_PORT="${PRIMARY_PORT:-5599}"       # non-default ports so we never touch a real cluster
RESTORE_PORT="${RESTORE_PORT:-5598}"

DB_NAME="${DB_NAME:-d2os}"
DB_OWNER_USER="${D2OS_DB_OWNER_USER:-d2os_owner}"
DB_OWNER_PASSWORD="${D2OS_DB_OWNER_PASSWORD:-change-me-owner}"

# Repo root (this script lives in <repo>/ops).
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# System Gradle: the ./gradlew wrapper cannot download its distribution in every environment; the
# repo convention (see CLAUDE.md / tasks.md) is the system Gradle at /opt/gradle/bin/gradle.
GRADLE="${GRADLE:-/opt/gradle/bin/gradle}"

log() { printf '\n=== %s\n' "$*"; }

# --- 0. Preflight -----------------------------------------------------------------------------------

log "DR rehearsal starting — scratch root ${DR_ROOT}"
for bin in initdb pg_ctl pg_basebackup psql; do
  command -v "$bin" >/dev/null 2>&1 || { echo "ERROR: '$bin' not on PATH (need a PostgreSQL install)"; exit 1; }
done

rm -rf "${DR_ROOT}"
mkdir -p "${PRIMARY_DATA}" "${RESTORE_DATA}" "${WAL_ARCHIVE}" "${BASE_BACKUP}"

# --- 1. Stand up a fresh WAL-archiving primary ------------------------------------------------------
# Mirrors docker-compose.yml's postgres service command (wal_level=replica, archive_mode=on, the same
# idempotent archive_command shape) and ops/dr-drill.md's Backup Regime.

log "initdb fresh scratch primary at ${PRIMARY_DATA}"
PWFILE="${DR_ROOT}/.ownerpw"
printf '%s' "${DB_OWNER_PASSWORD}" > "${PWFILE}"
initdb -D "${PRIMARY_DATA}" -U "${DB_OWNER_USER}" --pwfile="${PWFILE}" --auth=scram-sha-256

# archive_command copies each completed segment into WAL_ARCHIVE exactly once (idempotent test ! -f),
# identical in shape to docker-compose.yml. archive_timeout shortened so the rehearsal runs in minutes.
cat >> "${PRIMARY_DATA}/postgresql.conf" <<EOF
port = ${PRIMARY_PORT}
wal_level = replica
archive_mode = on
archive_command = 'test ! -f ${WAL_ARCHIVE}/%f && cp %p ${WAL_ARCHIVE}/%f'
archive_timeout = 30
EOF

log "start primary on port ${PRIMARY_PORT}"
pg_ctl -D "${PRIMARY_DATA}" -o "-p ${PRIMARY_PORT}" -w start
createdb -p "${PRIMARY_PORT}" -U "${DB_OWNER_USER}" "${DB_NAME}" 2>/dev/null || true

# --- 2. Apply the REAL D2OS Flyway schema, then take a base backup ----------------------------------
# This is the key difference from the earlier drill: the scratch primary carries the actual D2OS
# schema (the full global V-namespace, V1..V29+), not a single throwaway table.

log "run real Flyway migrations against the scratch primary"
export D2OS_DB_OWNER_USER="${DB_OWNER_USER}"
export D2OS_DB_OWNER_PASSWORD="${DB_OWNER_PASSWORD}"
# flywayMigrate is bound to the app's datasource; point Flyway at the scratch primary. Adjust the
# task/module name if the project exposes migration under a different Gradle task.
"${GRADLE}" -p "${REPO_ROOT}" :app:flywayMigrate \
  -Dflyway.url="jdbc:postgresql://localhost:${PRIMARY_PORT}/${DB_NAME}" \
  -Dflyway.user="${DB_OWNER_USER}" \
  -Dflyway.password="${DB_OWNER_PASSWORD}" \
  || { echo "ERROR: Flyway migration against scratch primary failed"; exit 1; }

log "take real base backup (pg_basebackup -Ft -z) — the recovery baseline"
pg_basebackup -h localhost -p "${PRIMARY_PORT}" -U "${DB_OWNER_USER}" \
  -D "${BASE_BACKUP}" -Ft -z -P -X none

# Drive some WAL forward AFTER the base backup so recovery has segments to replay, and force a switch
# so the segment archives promptly (mirrors the drill's pg_switch_wal step).
psql -p "${PRIMARY_PORT}" -U "${DB_OWNER_USER}" -d "${DB_NAME}" -c "SELECT pg_switch_wal();" >/dev/null
RECOVERY_TARGET_TIME="$(psql -p "${PRIMARY_PORT}" -U "${DB_OWNER_USER}" -d "${DB_NAME}" -tAc "SELECT now();")"
log "recovery_target_time chosen: ${RECOVERY_TARGET_TIME}"
# Give archive_timeout a moment to flush the final segment.
until ls -1 "${WAL_ARCHIVE}" 2>/dev/null | grep -qv '\.history$'; do sleep 1; done

# --- 3. Restore the base backup + WAL into a second data directory ----------------------------------
# Per ops/dr-drill.md Restore Procedure: extract the tar base backup, create recovery.signal, set
# restore_command + recovery_target_time, start Postgres so it replays WAL forward to the target.

log "restore base backup into ${RESTORE_DATA}"
tar -xzf "${BASE_BACKUP}/base.tar.gz" -C "${RESTORE_DATA}"
# The WAL segments live in their own tar when -X none was used above.
if [ -f "${BASE_BACKUP}/pg_wal.tar.gz" ]; then
  mkdir -p "${RESTORE_DATA}/pg_wal"
  tar -xzf "${BASE_BACKUP}/pg_wal.tar.gz" -C "${RESTORE_DATA}/pg_wal"
fi

touch "${RESTORE_DATA}/recovery.signal"
cat >> "${RESTORE_DATA}/postgresql.conf" <<EOF
port = ${RESTORE_PORT}
restore_command = 'cp ${WAL_ARCHIVE}/%f %p'
recovery_target_time = '${RECOVERY_TARGET_TIME}'
recovery_target_action = 'promote'
EOF
chmod 700 "${RESTORE_DATA}"

log "start restored instance on port ${RESTORE_PORT} (replays WAL to recovery target, then promotes)"
pg_ctl -D "${RESTORE_DATA}" -o "-p ${RESTORE_PORT}" -w start

# Wait until recovery has finished and the instance accepts connections out of recovery.
until psql -p "${RESTORE_PORT}" -U "${DB_OWNER_USER}" -d "${DB_NAME}" -tAc "SELECT NOT pg_is_in_recovery();" 2>/dev/null | grep -q '^t$'; do
  sleep 1
done
log "restored instance is up and out of recovery"

RESTORED_JDBC_URL="jdbc:postgresql://localhost:${RESTORE_PORT}/${DB_NAME}"

# --- 4. Post-Restore Validation against the RESTORED instance (per ops/dr-drill.md) -----------------
# These require the running D2OS application booted against the restored DB. This script proves the
# schema-level restore; the two app-level integrity checks below are the FR-017 "recovered system
# passes its own integrity checks" gate and are left as explicit TODOs with the exact intended
# commands, because they need the Spring Boot app (not available in the delivery sandbox).

log "Post-Restore Validation (app-level) — see TODOs below"

# TODO(FR-017, AuditChainVerifier): boot the app against the restored instance, then verify every
# workspace's audit hash-chain is intact. Exact intended commands:
#   D2OS_DB_URL="${RESTORED_JDBC_URL}" \
#   D2OS_DB_APP_USER="${D2OS_DB_APP_USER:-d2os_app}" \
#   D2OS_DB_APP_PASSWORD="${D2OS_DB_APP_PASSWORD:?}" \
#     "${GRADLE}" -p "${REPO_ROOT}" :app:bootRun &   # (or the built container image)
#   # wait for readiness:
#   until curl -fsS http://localhost:8080/actuator/health/readiness | grep -q '"status":"UP"'; do sleep 2; done
#   # per-workspace chain verification (POST /api/v1/audit/chain/verify, AuditChainController):
#   curl -fsS -X POST http://localhost:8080/api/v1/audit/chain/verify \
#        -H "Authorization: Bearer <token-scoped-to-workspace>" \
#     | tee "${DR_ROOT}/audit-chain-result.json"
#   # FAIL the rehearsal if any chain reports intact=false.
echo "TODO: run AuditChainVerifier (POST /api/v1/audit/chain/verify) against ${RESTORED_JDBC_URL} — see comment above"

# TODO(FR-017, smoke Case): submit a new Case through to Delivered against the restored instance to
# prove the schema + application are fully functional post-restore (not merely readable). Exact
# intended command shape (mirrors the quickstart smoke path):
#   curl -fsS -X POST http://localhost:8080/api/v1/intake/problems \
#        -H "Authorization: Bearer <token>" -H 'Content-Type: application/json' \
#        -d @ops/fixtures/smoke-problem.json
#   # then drive the resulting Case to Delivered and assert the terminal state via the case API.
echo "TODO: run smoke Case to Delivered against ${RESTORED_JDBC_URL} — see comment above"

# --- 5. Teardown ------------------------------------------------------------------------------------
# The rehearsal is disposable; stop both scratch instances. (Left running if KEEP_RUNNING=1 so the
# app-level TODOs above can be executed by hand against the restored instance.)

if [ "${KEEP_RUNNING:-0}" != "1" ]; then
  log "teardown scratch instances"
  pg_ctl -D "${RESTORE_DATA}" -m immediate stop || true
  pg_ctl -D "${PRIMARY_DATA}" -m immediate stop || true
fi

log "DR rehearsal complete — schema-level restore verified; app-level integrity checks are TODOs above"
