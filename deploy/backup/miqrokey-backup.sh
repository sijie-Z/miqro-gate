#!/usr/bin/env bash
# MiQroKey PostgreSQL backup (G6.2).
#
#   pg_dump -> gzip -> openssl AES-256-CBC (random salt, PBKDF2) -> <BACKUP_PATH>
#
# Retention: keep MIQROKEY_BACKUP_DAILY_KEEP (default 7) daily files and
# MIQROKEY_BACKUP_WEEKLY_KEEP (default 4) weekly files. Weekly files are the
# newest file from each calendar week (Monday start). A backup is only
# counted once; files older than the oldest kept weekly file are pruned.
#
# Exit codes: 0 = ok, 1 = dump/encrypt failure, 2 = retention failure,
# 3 = webhook notification failure (backup itself succeeded).
# These codes hold even when the webhook is unreachable; only exit 3 is *about*
# the webhook, and only on the success path (#1402).
set -euo pipefail

# Git Bash (Windows) passes MSYS paths that native openssl cannot open.
if command -v cygpath >/dev/null 2>&1; then
  winpath() { cygpath -w "$1"; }
else
  winpath() { printf '%s' "$1"; }
fi

# Retention helper (own file so test-retention.sh can run it without a database).
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib-retention.sh"

MIQROKEY_BACKUP_PATH="${MIQROKEY_BACKUP_PATH:-/var/backups/miqrokey}"
MIQROKEY_BACKUP_DAILY_KEEP="${MIQROKEY_BACKUP_DAILY_KEEP:-7}"
MIQROKEY_BACKUP_WEEKLY_KEEP="${MIQROKEY_BACKUP_WEEKLY_KEEP:-4}"
MIQROKEY_BACKUP_KEY_FILE="${MIQROKEY_BACKUP_KEY_FILE:?MIQROKEY_BACKUP_KEY_FILE is required (32-byte key material, base64)}"
MIQROKEY_BACKUP_WEBHOOK_URL="${MIQROKEY_BACKUP_WEBHOOK_URL:-}"
MIQROKEY_BACKUP_WEBHOOK_SECRET="${MIQROKEY_BACKUP_WEBHOOK_SECRET:-}"
MIQROKEY_DB_URL="${MIQROKEY_DB_URL:-jdbc:postgresql://localhost:5432/miqrokey}"
MIQROKEY_DB_USERNAME="${MIQROKEY_DB_USERNAME:-miqrokey}"
MIQROKEY_DB_PASSWORD="${MIQROKEY_DB_PASSWORD:-}"

# Parse the JDBC URL into PGHOST/PGPORT/PGDATABASE.
DB_HOST=$(printf '%s' "$MIQROKEY_DB_URL" | sed -E 's#jdbc:postgresql://([^:/]+).*#\1#')
DB_PORT=$(printf '%s' "$MIQROKEY_DB_URL" | sed -E 's#jdbc:postgresql://[^:]+:([0-9]+).*#\1#')
DB_NAME=$(printf '%s' "$MIQROKEY_DB_URL" | sed -E 's#.*/([^?]+).*#\1#')
if [ -z "$DB_PORT" ]; then DB_PORT=5432; fi

export PGHOST="$DB_HOST" PGPORT="$DB_PORT" PGDATABASE="$DB_NAME"
export PGUSER="$MIQROKEY_DB_USERNAME" PGPASSWORD="$MIQROKEY_DB_PASSWORD"

STAMP=$(date -u +%Y%m%dT%H%M%SZ)
FILE="$MIQROKEY_BACKUP_PATH/miqrokey-$STAMP.sql.gz.enc"
MANIFEST="$FILE.sha256"

notify() {
  local status="$1" message="$2"
  [ -z "$MIQROKEY_BACKUP_WEBHOOK_URL" ] && return 0
  local payload body
  payload="{\"type\":\"backup\",\"status\":\"$status\",\"message\":\"$message\",\"file\":\"$(basename "$FILE")\",\"finishedAt\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}"
  if [ -n "$MIQROKEY_BACKUP_WEBHOOK_SECRET" ]; then
    body=$(printf '%s' "$payload" | openssl dgst -sha256 -hmac "$MIQROKEY_BACKUP_WEBHOOK_SECRET" -binary | od -An -tx1 | tr -d ' \n')
    curl -sf --max-time 5 -X POST "$MIQROKEY_BACKUP_WEBHOOK_URL" -H "Content-Type: application/json" \
      -H "X-MiQroKey-Signature: sha256=$body" -d "$payload" >/dev/null
  else
    curl -sf --max-time 5 -X POST "$MIQROKEY_BACKUP_WEBHOOK_URL" -H "Content-Type: application/json" -d "$payload" >/dev/null
  fi
}

mkdir -p "$MIQROKEY_BACKUP_PATH"

# Dump -> gzip -> encrypt with a random salt each run.
if ! pg_dump --format=custom --no-owner --no-privileges "$DB_NAME" \
  | gzip -9 \
  | openssl enc -aes-256-cbc -pbkdf2 -iter 200000 -salt \
      -pass file:"$(winpath "$MIQROKEY_BACKUP_KEY_FILE")" -out "$FILE"; then
  # The failed pipeline may have left a partial archive behind (openssl opens
  # its output file up front) — remove it so ops never finds a
  # restorable-looking stub without a manifest (#438).
  rm -f -- "$FILE"
  # The exit codes above are the contract callers alert on, and an unreachable
  # webhook must not replace them — a failing database and a failing webhook are
  # usually the same incident. `notify` failing here means curl failed; swallow
  # it so `exit 1` still runs (#1402).
  notify failure "pg_dump or encryption failed" || true
  exit 1
fi

# Checksum manifest (recorded before retention runs so the kept file set
# always has its own manifest).
#
# Record the bare file name, never the absolute path the backup happened to be
# written to: the archive is synced off-host by design (operations-runbook §10
# 「存放于独立介质」/ §G6.2 「备份产物需另行同步异地（COS）」) and restored from
# wherever it lands. A manifest that pinned the producing host's path would
# make restore/verify check a file that no longer exists — or, worse, the old
# copy that does — instead of the archive being restored (#1381).
( cd "$(dirname "$FILE")" && sha256sum "$(basename "$FILE")" ) > "$MANIFEST"

# Retention: daily set + newest per ISO week among the remainder (#438).
PRUNE_COUNT=$(apply_retention "$MIQROKEY_BACKUP_PATH" "$MIQROKEY_BACKUP_DAILY_KEEP" "$MIQROKEY_BACKUP_WEEKLY_KEEP") \
  || { notify failure "retention failed" || true; exit 2; }

if ! notify success "backup completed ($(du -h "$FILE" | cut -f1))"; then
  exit 3
fi
echo "backup ok: $FILE (pruned $PRUNE_COUNT)"
