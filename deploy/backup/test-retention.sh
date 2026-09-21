#!/usr/bin/env bash
# Retention semantics tests for miqrokey-backup.sh (#438), no database and no
# Docker required: the calendar-week kept-set is asserted exactly against a
# fixture, a second run must be a no-op, and a dump failure must leave no
# residue archive behind.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

fail() { echo "FAIL: $1" >&2; exit 1; }

# --- fixture: 20 daily archives 2026-08-20 .. 2026-09-08 (UTC noon) ---
OUT="$WORK/out"
mkdir -p "$OUT"
for day in $(seq 20 31); do
  f="$OUT/miqrokey-202608${day}T120000Z.sql.gz.enc"
  : > "$f"; : > "$f.sha256"
done
for day in 1 2 3 4 5 6 7 8; do
  f="$OUT/miqrokey-2026090${day}T120000Z.sql.gz.enc"
  : > "$f"; : > "$f.sha256"
done

# Expected under daily-keep=7 / weekly-keep=4 (ISO weeks, Monday start):
#   newest 7 dailies: 09-02..09-08
#   newest per distinct week among the rest: 09-01 (W36), 08-30 (W35), 08-23 (W34)
KEPT="20260902 20260903 20260904 20260905 20260906 20260907 20260908 20260901 20260830 20260823"
PRUNED="20260820 20260821 20260822 20260824 20260825 20260826 20260827 20260828 20260829 20260831"

echo "== retention: calendar-week kept set =="
# shellcheck source=lib-retention.sh
source "$HERE/lib-retention.sh"
PRUNED_N=$(apply_retention "$OUT" 7 4)
[ "$PRUNED_N" = "10" ] || fail "expected 10 pruned, got $PRUNED_N"
for d in $KEPT; do
  [ -f "$OUT/miqrokey-${d}T120000Z.sql.gz.enc" ] || fail "expected kept $d missing"
  [ -f "$OUT/miqrokey-${d}T120000Z.sql.gz.enc.sha256" ] || fail "manifest for kept $d missing"
done
for d in $PRUNED; do
  [ ! -e "$OUT/miqrokey-${d}T120000Z.sql.gz.enc" ] || fail "expected pruned $d still present"
  [ ! -e "$OUT/miqrokey-${d}T120000Z.sql.gz.enc.sha256" ] || fail "manifest for pruned $d not pruned"
done
LEFT=$(ls -1 "$OUT"/miqrokey-*.sql.gz.enc | wc -l)
[ "$LEFT" = "10" ] || fail "expected 10 archives kept, $LEFT present"

SECOND=$(apply_retention "$OUT" 7 4)
[ "$SECOND" = "0" ] || fail "second run must be a no-op, pruned $SECOND"
echo "retention PASS: exact kept set (10 kept / 10 pruned), idempotent"

echo "== failure path: dump failure leaves no residue =="
FAILOUT="$WORK/failout"
mkdir -p "$FAILOUT" "$WORK/bin"
printf '#!/usr/bin/env bash\nexit 1\n' > "$WORK/bin/pg_dump"
chmod +x "$WORK/bin/pg_dump"
KEY_FILE="$WORK/key"
printf '%s' "$(openssl rand -base64 32)" > "$KEY_FILE"
chmod 400 "$KEY_FILE"

set +e
PATH="$WORK/bin:$PATH" MIQROKEY_BACKUP_PATH="$FAILOUT" MIQROKEY_BACKUP_KEY_FILE="$KEY_FILE" \
MIQROKEY_DB_URL="jdbc:postgresql://localhost:5432/miqrokey" \
  bash "$HERE/miqrokey-backup.sh" >/dev/null 2>&1
RC=$?
set -e
[ "$RC" = "1" ] || fail "dump failure must exit 1, got $RC"
RESIDUE=$(ls -1 "$FAILOUT"/miqrokey-*.sql.gz.enc 2>/dev/null | wc -l || true)
[ "$RESIDUE" = "0" ] || fail "dump failure left $RESIDUE residue archive(s)"
echo "failure-path PASS: exit 1, no residue"

echo "test-retention PASS"
