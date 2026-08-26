#!/usr/bin/env bash
# MiQroKey restore (G6.2): decrypt -> gunzip -> pg_restore.
#
#   restore.sh <backup-file.sql.gz.enc> [target-db-name]
#
# Verifies the SHA-256 manifest before touching anything. The target
# database must exist (createdb if missing is left to the operator).
set -euo pipefail

# Git Bash (Windows) passes MSYS paths that native openssl cannot open.
if command -v cygpath >/dev/null 2>&1; then
  winpath() { cygpath -w "$1"; }
else
  winpath() { printf '%s' "$1"; }
fi

MIQROKEY_BACKUP_KEY_FILE="${MIQROKEY_BACKUP_KEY_FILE:?MIQROKEY_BACKUP_KEY_FILE is required}"
MIQROKEY_DB_URL="${MIQROKEY_DB_URL:-jdbc:postgresql://localhost:5432/miqrokey}"
MIQROKEY_DB_USERNAME="${MIQROKEY_DB_USERNAME:-miqrokey}"
MIQROKEY_DB_PASSWORD="${MIQROKEY_DB_PASSWORD:-}"

BACKUP_FILE="${1:?usage: miqrokey-restore.sh <file.sql.gz.enc> [target-db]}"
TARGET_DB="${2:-}"

DB_HOST=$(printf '%s' "$MIQROKEY_DB_URL" | sed -E 's#jdbc:postgresql://([^:/]+).*#\1#')
DB_PORT=$(printf '%s' "$MIQROKEY_DB_URL" | sed -E 's#jdbc:postgresql://[^:]+:([0-9]+).*#\1#')
DB_NAME=$(printf '%s' "$MIQROKEY_DB_URL" | sed -E 's#.*/([^?]+).*#\1#')
if [ -z "$DB_PORT" ]; then DB_PORT=5432; fi
[ -n "$TARGET_DB" ] && DB_NAME="$TARGET_DB"

export PGHOST="$DB_HOST" PGPORT="$DB_PORT" PGDATABASE="$DB_NAME"
export PGUSER="$MIQROKEY_DB_USERNAME" PGPASSWORD="$MIQROKEY_DB_PASSWORD"

# Integrity gate: the manifest must exist and match the exact bytes.
MANIFEST="$BACKUP_FILE.sha256"
[ -f "$MANIFEST" ] || { echo "restore aborted: missing manifest $MANIFEST" >&2; exit 1; }
if ! (cd "$(dirname "$BACKUP_FILE")" && sha256sum -c "$(basename "$MANIFEST")" >/dev/null 2>&1); then
  echo "restore aborted: checksum mismatch" >&2
  exit 1
fi

echo "restoring $BACKUP_FILE into $DB_NAME ..."
openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass file:"$(winpath "$MIQROKEY_BACKUP_KEY_FILE")" \
  -in "$BACKUP_FILE" \
  | gunzip \
  | pg_restore --no-owner --no-privileges --exit-on-error -d "$DB_NAME"

echo "restore ok"
