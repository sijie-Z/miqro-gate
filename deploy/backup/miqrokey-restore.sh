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
#
# Compare the digest against the archive we were *handed*, by content. Never
# dereference the path recorded next to it: `sha256sum -c` follows that path,
# so a backup synced to another host or directory would be rejected as
# "checksum mismatch" while the file it actually checked was a different one
# (#1381).
MANIFEST="$BACKUP_FILE.sha256"
[ -f "$MANIFEST" ] || { echo "restore aborted: missing manifest $MANIFEST" >&2; exit 1; }
[ -f "$BACKUP_FILE" ] || { echo "restore aborted: no such backup file $BACKUP_FILE" >&2; exit 1; }
EXPECTED=$(head -n1 "$MANIFEST" | cut -d' ' -f1)
ACTUAL=$(sha256sum "$BACKUP_FILE" | cut -d' ' -f1)
if [ -z "$EXPECTED" ] || [ "$EXPECTED" != "$ACTUAL" ]; then
  echo "restore aborted: checksum mismatch" >&2
  exit 1
fi

echo "restoring $BACKUP_FILE into $DB_NAME ..."
# --single-transaction (#438): the restore is atomic — a mid-way failure rolls
# back and the target database keeps its previous state instead of a
# half-restored mix.
openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass file:"$(winpath "$MIQROKEY_BACKUP_KEY_FILE")" \
  -in "$BACKUP_FILE" \
  | gunzip \
  | pg_restore --no-owner --no-privileges --exit-on-error --single-transaction -d "$DB_NAME"

echo "restore ok"
