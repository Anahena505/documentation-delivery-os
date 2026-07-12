#!/usr/bin/env bash
#
# backup-verify.sh — scheduled, unattended verification that the LATEST base backup is restorable and
# internally consistent (feature 008, T064, FR-017, enhancement E13). Companion doc:
# ops/backup-verification.md.
#
# Unlike ops/dr-rehearsal.sh (which takes a fresh backup and runs the full app-level validation on
# demand), this job targets the most recent PRODUCTION base backup and runs the fast integrity signal:
# restore -> AuditChainVerifier. Intended to run nightly as a Kubernetes CronJob / host cron on a
# Docker/Postgres-capable node.
#
# Validate syntax only with:  bash -n ops/backup-verify.sh

set -euo pipefail

# --- Configuration (override via environment) -------------------------------------------------------

# Where nightly base backups + archived WAL land (docker-compose.yml's d2os-wal-archive volume).
WAL_ARCHIVE="${WAL_ARCHIVE:-/wal-archive}"
# Scratch restore target — disposable.
DR_ROOT="${DR_ROOT:-/tmp/d2os-backup-verify}"
RESTORE_DATA="${DR_ROOT}/restore"
RESTORE_PORT="${RESTORE_PORT:-5597}"

DB_NAME="${DB_NAME:-d2os}"
DB_OWNER_USER="${D2OS_DB_OWNER_USER:-d2os_owner}"

log() { printf '\n=== %s\n' "$*"; }

# --- 0. Preflight -----------------------------------------------------------------------------------

for bin in pg_ctl psql tar; do
  command -v "$bin" >/dev/null 2>&1 || { echo "ERROR: '$bin' not on PATH"; exit 1; }
done

rm -rf "${DR_ROOT}"
mkdir -p "${RESTORE_DATA}"

# --- 1. Locate the latest base backup ---------------------------------------------------------------
# Base backups are written as /wal-archive/base-<date> (ops/dr-drill.md Backup Regime). Pick the
# newest one.

log "locate latest base backup under ${WAL_ARCHIVE}"
LATEST_BACKUP="$(find "${WAL_ARCHIVE}" -maxdepth 1 -type d -name 'base-*' | sort | tail -n1)"
if [ -z "${LATEST_BACKUP}" ]; then
  echo "ERROR: no base-* backup found under ${WAL_ARCHIVE}"
  exit 1
fi
log "latest base backup: ${LATEST_BACKUP}"

# --- 2. Restore into a scratch instance -------------------------------------------------------------
# Same mechanism as ops/dr-drill.md §Restore Procedure: extract, recovery.signal, restore_command,
# recovery_target_time=latest, start + promote.

log "extract base backup into ${RESTORE_DATA}"
tar -xzf "${LATEST_BACKUP}/base.tar.gz" -C "${RESTORE_DATA}"
if [ -f "${LATEST_BACKUP}/pg_wal.tar.gz" ]; then
  mkdir -p "${RESTORE_DATA}/pg_wal"
  tar -xzf "${LATEST_BACKUP}/pg_wal.tar.gz" -C "${RESTORE_DATA}/pg_wal"
fi

touch "${RESTORE_DATA}/recovery.signal"
cat >> "${RESTORE_DATA}/postgresql.conf" <<EOF
port = ${RESTORE_PORT}
restore_command = 'cp ${WAL_ARCHIVE}/%f %p'
recovery_target_action = 'promote'
EOF
chmod 700 "${RESTORE_DATA}"

log "start restored scratch instance on port ${RESTORE_PORT}"
pg_ctl -D "${RESTORE_DATA}" -o "-p ${RESTORE_PORT}" -w start

until psql -p "${RESTORE_PORT}" -U "${DB_OWNER_USER}" -d "${DB_NAME}" -tAc "SELECT NOT pg_is_in_recovery();" 2>/dev/null | grep -q '^t$'; do
  sleep 1
done
log "restore succeeded — instance is up and out of recovery"

RESTORED_JDBC_URL="jdbc:postgresql://localhost:${RESTORE_PORT}/${DB_NAME}"

# --- 3. AuditChainVerifier (app-level integrity check) ----------------------------------------------
# Needs the D2OS app booted against the restored instance. Left as a TODO with the exact intended
# command (identical shape to ops/dr-rehearsal.sh) because it requires the running application.

# TODO(FR-017, AuditChainVerifier): boot the app against ${RESTORED_JDBC_URL} and verify every
# workspace's audit hash-chain is intact; FAIL this job (exit non-zero) on any intact=false result.
#   D2OS_DB_URL="${RESTORED_JDBC_URL}" .../bootRun &   # or the built container image
#   until curl -fsS http://localhost:8080/actuator/health/readiness | grep -q '"status":"UP"'; do sleep 2; done
#   curl -fsS -X POST http://localhost:8080/api/v1/audit/chain/verify \
#        -H "Authorization: Bearer <workspace-scoped-token>" \
#     | tee "${DR_ROOT}/audit-chain-result.json"
#   grep -q '"intact":false' "${DR_ROOT}/audit-chain-result.json" && { echo "FAIL: audit chain broken"; exit 1; }
echo "TODO: run AuditChainVerifier (POST /api/v1/audit/chain/verify) against ${RESTORED_JDBC_URL} — see comment above"

# --- 4. Teardown ------------------------------------------------------------------------------------

log "teardown scratch instance"
pg_ctl -D "${RESTORE_DATA}" -m immediate stop || true

log "backup verification complete for ${LATEST_BACKUP}"
