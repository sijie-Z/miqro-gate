#!/usr/bin/env bash
# Flyway upgrade pre-flight: the V70 single-reversal invariant (#1249).
#
# Read-only checks against the target database. Exits non-zero when a pending
# migration is known to fail, so the gate happens BEFORE the control plane
# starts instead of as a crash loop after it.
#
#   deploy/preflight/miqrokey-migration-preflight.sh [--print-sql]
#
# Connection comes from the same variables as the backup scripts:
#   MIQROKEY_DB_URL (jdbc:postgresql://host:port/name)
#   MIQROKEY_DB_USERNAME, MIQROKEY_DB_PASSWORD
#
# Exit codes: 0 = no blocker, 1 = blocker(s) found, 2 = tooling error.
#
# Why this exists (measured on PostgreSQL 17.11 + Flyway 12.4.0, issue #1249):
# V70__usage_adjustment_single_reversal.sql:32 creates a unique index without
# IF NOT EXISTS. On PostgreSQL a migration runs in a single transaction, so a
# failure rolls the schema change AND the schema-history insert back together:
# flyway_schema_history keeps no row at all — not even success = false. Flyway
# repair then reports "No failed migration detected" and every restart fails in
# the same place. Both reachable states are visible in the data beforehand,
# which is what this script reports. Remediation: docs/operations-runbook.md
# §9b.
set -euo pipefail

# ${BASH_SOURCE[0]:-$0} so the script also runs when piped in (bash -s over
# ssh), where BASH_SOURCE is unset and $0 is just "bash".
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)

# The SQL below is a single psql script; --print-sql makes it available for
# manual runs (docker compose exec -T postgres psql ...) without the wrapper.
read -r -d '' PREFLIGHT_SQL <<'SQL' || true
\t on
\a
\set ON_ERROR_STOP on

-- Temp table only: nothing outside this session is created, dropped or written,
-- so the script is safe to run against a live database.
CREATE TEMP TABLE preflight_blockers (check_name text, detail text);

DO $preflight$
DECLARE
    has_history boolean := to_regclass('public.flyway_schema_history') IS NOT NULL;
    has_ledger  boolean := to_regclass('public.usage_adjustments') IS NOT NULL;
    has_index   boolean := to_regclass('public.uq_usage_adjustments_reversal_of') IS NOT NULL;
    v70_applied boolean := false;
    current_version text := '(none)';
    pending text := '?';
    n integer;
    r record;
BEGIN
    IF has_history THEN
        SELECT coalesce(version, '(baseline)') INTO current_version
          FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1;
        SELECT EXISTS (SELECT 1 FROM flyway_schema_history WHERE version = '70' AND success)
          INTO v70_applied;
        -- A version is pending when any installed script sits above it, or when
        -- nothing at all is recorded yet (fresh database).
        SELECT count(*)::text INTO pending FROM flyway_schema_history;
    END IF;

    RAISE NOTICE '[info] schema history: %, last applied version: %, V70 applied: %, usage_adjustments present: %',
        CASE WHEN has_history THEN 'present (' || pending || ' rows)' ELSE 'absent (fresh database)' END,
        current_version, v70_applied, has_ledger;

    -- (A) V70 already applied by hand / operator built the index out of band.
    -- V69__usage_adjustments_fk_check_index.sql:83-86 tells operators to build
    -- FK-check indexes out of band with CREATE INDEX CONCURRENTLY and mark the
    -- migration as applied; doing that for V70's index makes the later
    -- CREATE UNIQUE INDEX fail with "relation already exists".
    IF has_index AND has_history AND NOT v70_applied THEN
        INSERT INTO preflight_blockers
        VALUES ('v70-index-already-exists',
                'index uq_usage_adjustments_reversal_of exists but V70 is not recorded as applied; '
                || 'Flyway will fail with: relation "uq_usage_adjustments_reversal_of" already exists');
    END IF;

    -- (B) The data V70 refuses to accept: two reversals of one original row.
    -- #999 stopped the service from creating new ones; rows written before it
    -- are still there and are exactly what makes the CREATE UNIQUE INDEX fail.
    --
    -- Only a blocker while V70 is still pending. Once V70 is recorded as
    -- applied Flyway never executes it again, so the duplicates cannot fail the
    -- upgrade; they are the expected residue of the §9b.3(二) "mark V70 as
    -- applied" escape, where the index was skipped on purpose (that choice is
    -- recorded in the change log, not re-litigated here). Reporting it as a
    -- blocker there would block every future upgrade forever.
    IF has_ledger THEN
        FOR r IN
            SELECT tenant_id, reversal_of_id, count(*) AS dup_count
              FROM usage_adjustments
             WHERE reversal_of_id IS NOT NULL
             GROUP BY tenant_id, reversal_of_id
            HAVING count(*) > 1
             ORDER BY tenant_id, reversal_of_id
        LOOP
            IF v70_applied THEN
                RAISE NOTICE '[info] duplicate reversal kept: tenant %, reversal_of %, % rows — V70 is already applied, so this does not block an upgrade; the unique index is absent by decision (section 9b.3)',
                    r.tenant_id, r.reversal_of_id, r.dup_count;
            ELSE
                INSERT INTO preflight_blockers
                VALUES ('duplicate-reversal',
                        'tenant ' || r.tenant_id || ', reversal_of ' || r.reversal_of_id
                        || ': ' || r.dup_count || ' reversal rows (V70 allows exactly 1)');
            END IF;
        END LOOP;
    ELSE
        RAISE NOTICE '[info] usage_adjustments absent (pre-V63 schema): duplicate-reversal check skipped';
    END IF;
END
$preflight$;

SELECT 'BLOCKER | ' || check_name || ' | ' || detail AS finding
  FROM preflight_blockers ORDER BY check_name, detail;

SELECT CASE WHEN count(*) = 0
            THEN 'PREFLIGHT VERDICT=OK'
            ELSE 'PREFLIGHT VERDICT=BLOCKED (' || count(*) || ' finding(s); see docs/operations-runbook.md section 9b)'
       END AS verdict
  FROM preflight_blockers;
SQL

if [ "${1:-}" = "--print-sql" ]; then
  printf '%s\n' "$PREFLIGHT_SQL"
  exit 0
fi

MIQROKEY_DB_URL="${MIQROKEY_DB_URL:-jdbc:postgresql://localhost:5432/miqrokey}"
MIQROKEY_DB_USERNAME="${MIQROKEY_DB_USERNAME:-miqrokey}"
MIQROKEY_DB_PASSWORD="${MIQROKEY_DB_PASSWORD:-}"

DB_HOST=$(printf '%s' "$MIQROKEY_DB_URL" | sed -E 's#jdbc:postgresql://([^:/]+).*#\1#')
DB_PORT=$(printf '%s' "$MIQROKEY_DB_URL" | sed -E 's#jdbc:postgresql://[^:]+:([0-9]+).*#\1#')
DB_NAME=$(printf '%s' "$MIQROKEY_DB_URL" | sed -E 's#.*/([^?]+).*#\1#')
if [ -z "$DB_PORT" ]; then DB_PORT=5432; fi

export PGHOST="$DB_HOST" PGPORT="$DB_PORT" PGDATABASE="$DB_NAME"
export PGUSER="$MIQROKEY_DB_USERNAME" PGPASSWORD="$MIQROKEY_DB_PASSWORD"

# psql must not pick up a different database from ~/.pgpass or the environment.
OUT=$(psql -X -q -v ON_ERROR_STOP=1 -f - 2>&1 <<<"$PREFLIGHT_SQL") || {
  printf '%s\n' "$OUT" >&2
  echo "preflight failed: could not query $DB_HOST:$DB_PORT/$DB_NAME" >&2
  exit 2
}

printf '%s\n' "$OUT"

if printf '%s' "$OUT" | grep -q 'PREFLIGHT VERDICT=BLOCKED'; then
  exit 1
fi
if ! printf '%s' "$OUT" | grep -q 'PREFLIGHT VERDICT=OK'; then
  echo "preflight failed: no verdict in psql output" >&2
  exit 2
fi
echo "preflight ok: no V70 blocker in $DB_HOST:$DB_PORT/$DB_NAME (script dir: $SCRIPT_DIR)"
