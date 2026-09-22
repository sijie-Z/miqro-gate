#!/usr/bin/env bash
# MiQroKey restore (G6.2): decrypt -> gunzip -> pg_restore.
#
#   miqrokey-restore.sh [--replace] <backup-file.sql.gz.enc> [target-db-name]
#
# Verifies the SHA-256 manifest before touching anything.
#
# The two modes differ in what they require of the target database:
#
#   default     the target must exist and hold no user objects. Restoring onto
#               a database that already has objects cannot work: pg_restore
#               re-creates every function/table/type, so it aborts on the first
#               "already exists" and --single-transaction rolls the whole thing
#               back. The pre-flight check below turns that into one readable
#               sentence instead of a wall of pg_restore errors.
#   --replace   the target database is dropped and recreated first, so a backup
#               can be restored over a live database — this is the documented
#               disaster rollback (#1423). Destructive: it needs an explicit
#               target name and MIQROKEY_RESTORE_CONFIRM=yes, and the role must
#               be allowed to CREATEDB.
set -euo pipefail

# Git Bash (Windows) passes MSYS paths that native openssl cannot open.
if command -v cygpath >/dev/null 2>&1; then
  winpath() { cygpath -w "$1"; }
else
  winpath() { printf '%s' "$1"; }
fi

REPLACE=0
while [ $# -gt 0 ]; do
  case "$1" in
    --replace) REPLACE=1; shift ;;
    --) shift; break ;;
    -*) echo "restore aborted: unknown option $1" >&2; exit 1 ;;
    *) break ;;
  esac
done

MIQROKEY_BACKUP_KEY_FILE="${MIQROKEY_BACKUP_KEY_FILE:?MIQROKEY_BACKUP_KEY_FILE is required}"
MIQROKEY_DB_URL="${MIQROKEY_DB_URL:-jdbc:postgresql://localhost:5432/miqrokey}"
MIQROKEY_DB_USERNAME="${MIQROKEY_DB_USERNAME:-miqrokey}"
MIQROKEY_DB_PASSWORD="${MIQROKEY_DB_PASSWORD:-}"

BACKUP_FILE="${1:?usage: miqrokey-restore.sh [--replace] <file.sql.gz.enc> [target-db]}"
TARGET_DB="${2:-}"

DB_HOST=$(printf '%s' "$MIQROKEY_DB_URL" | sed -E 's#jdbc:postgresql://([^:/]+).*#\1#')
DB_PORT=$(printf '%s' "$MIQROKEY_DB_URL" | sed -E 's#jdbc:postgresql://[^:]+:([0-9]+).*#\1#')
DB_NAME=$(printf '%s' "$MIQROKEY_DB_URL" | sed -E 's#.*/([^?]+).*#\1#')
if [ -z "$DB_PORT" ]; then DB_PORT=5432; fi
[ -n "$TARGET_DB" ] && DB_NAME="$TARGET_DB"

export PGHOST="$DB_HOST" PGPORT="$DB_PORT" PGDATABASE="$DB_NAME"
export PGUSER="$MIQROKEY_DB_USERNAME" PGPASSWORD="$MIQROKEY_DB_PASSWORD"

if [ "$REPLACE" = 1 ]; then
  # Never infer "drop this database" from a connection URL default: make the
  # operator name the victim, and never let the victim be the maintenance DB.
  [ -n "$TARGET_DB" ] || {
    echo "restore aborted: --replace requires an explicit target database name" >&2
    exit 1; }
  case "$DB_NAME" in
    postgres|template0|template1)
      echo "restore aborted: refusing to --replace the maintenance database $DB_NAME" >&2
      exit 1 ;;
  esac
  [ "${MIQROKEY_RESTORE_CONFIRM:-}" = "yes" ] || {
    echo "restore aborted: --replace drops and recreates $DB_NAME; re-run with MIQROKEY_RESTORE_CONFIRM=yes" >&2
    exit 1; }
fi

# Integrity gate: the manifest must exist and match the exact bytes.
MANIFEST="$BACKUP_FILE.sha256"
[ -f "$MANIFEST" ] || { echo "restore aborted: missing manifest $MANIFEST" >&2; exit 1; }
if ! (cd "$(dirname "$BACKUP_FILE")" && sha256sum -c "$(basename "$MANIFEST")" >/dev/null 2>&1); then
  echo "restore aborted: checksum mismatch" >&2
  exit 1
fi

if ! command -v psql >/dev/null 2>&1; then
  # Not fatal: the restore itself only needs pg_restore. Say so out loud rather
  # than skipping the checks silently.
  echo "warning: psql not found on PATH — cannot pre-check the target database" >&2
elif [ "$REPLACE" = 1 ]; then
  echo "replacing $DB_NAME: every row it holds now will be destroyed" >&2
  # The maintenance connection must not be the database being dropped, so it
  # names 'postgres' explicitly instead of inheriting PGDATABASE.
  # Recreate it the way it was: same owner, encoding and collation, from
  # template0 so no leftover object rides along. (Variables are interpolated
  # from stdin — psql does not substitute :'var' inside -c strings.)
  ORIGIN=$(psql -d postgres -tA -v ON_ERROR_STOP=1 --set=dbname="$DB_NAME" <<'SQL'
SELECT pg_get_userbyid(datdba) || E'\t' || pg_encoding_to_char(encoding) || E'\t' || datcollate || E'\t' || datctype
  FROM pg_database WHERE datname = :'dbname';
SQL
)
  if [ -n "$ORIGIN" ]; then
    IFS=$'\t' read -r OWNER ENCODING COLLATE CTYPE <<<"$ORIGIN"
    psql -d postgres -v ON_ERROR_STOP=1 --set=dbname="$DB_NAME" >&2 <<'SQL'
DROP DATABASE :"dbname" WITH (FORCE);
SQL
    psql -d postgres -v ON_ERROR_STOP=1 \
      --set=dbname="$DB_NAME" --set=owner="$OWNER" --set=enc="$ENCODING" \
      --set=coll="$COLLATE" --set=ctype="$CTYPE" >&2 <<'SQL'
CREATE DATABASE :"dbname" OWNER :"owner" TEMPLATE template0 ENCODING :'enc' LC_COLLATE :'coll' LC_CTYPE :'ctype';
SQL
  else
    psql -d postgres -v ON_ERROR_STOP=1 --set=dbname="$DB_NAME" >&2 <<'SQL'
CREATE DATABASE :"dbname";
SQL
  fi
else
  # Refuse early on a non-empty target: pg_restore would otherwise fail at the
  # first object and the operator would have to read a raw "already exists".
  OBJECTS=$(psql -d "$DB_NAME" -tA -v ON_ERROR_STOP=1 <<'SQL'
SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname NOT IN ('pg_catalog', 'information_schema')
   AND c.relkind IN ('r', 'p', 'v', 'm', 'S', 'f');
SQL
)
  if [ "$OBJECTS" != "0" ]; then
    echo "restore aborted: target database $DB_NAME already holds $OBJECTS user objects" >&2
    echo "  a backup can only be restored into an empty database (pg_restore re-creates every object)." >&2
    echo "  to restore over the live database, re-run with --replace (destroys its current contents)." >&2
    exit 1
  fi
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
