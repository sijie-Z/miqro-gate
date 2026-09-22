#!/usr/bin/env bash
# Exit-code contract tests for miqrokey-backup.sh, no database required.
#
# The script promises 0/1/2/3 (header, and operations-runbook §备份与恢复) and
# callers alert on those codes. Two defects had made 1/2 unreachable:
#
#   #1402  a bare `notify failure …` inherited curl's exit status when the
#          webhook was unreachable (7/22/28 instead of 1/2). An unreachable
#          webhook is not hypothetical: it is usually the same incident as the
#          failing backup — database down, network partition, monitoring host
#          restarting.
#   #1405  a failed `rm` inside apply_retention could not propagate at all: the
#          call site is a `||` list, which disables errexit for the whole
#          function body, so the script printed `backup ok: … (pruned N)` and
#          exited 0 while every file was still on disk.
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

KEY_FILE="$WORK/key"
printf '%s' "$(openssl rand -base64 32)" > "$KEY_FILE"
chmod 400 "$KEY_FILE"

# A port nothing listens on. If something ever does answer here, the assertions
# below would pass for the wrong reason — refuse to run instead.
DEAD_HOOK="http://127.0.0.1:9/miqrokey-hook"
if curl -s --max-time 5 -o /dev/null "$DEAD_HOOK"; then
  echo "FAIL: $DEAD_HOOK answered; this test needs an unreachable webhook" >&2
  exit 1
fi
echo "control: $DEAD_HOOK confirmed unreachable (curl could not connect)"

# -- mock binaries ----------------------------------------------------------
mkdir -p "$WORK/bin-fail" "$WORK/bin-ok" "$WORK/bin-rmfail"
cat > "$WORK/bin-fail/pg_dump" <<'WRAP'
#!/usr/bin/env bash
echo 'pg_dump: error: connection to server failed: database "miqrokey" does not exist' >&2
exit 1
WRAP
cat > "$WORK/bin-ok/pg_dump" <<'WRAP'
#!/usr/bin/env bash
printf 'MOCK-DUMP-%s\n' "$(date +%s)"
WRAP
cat > "$WORK/bin-rmfail/pg_dump" <<'WRAP'
#!/usr/bin/env bash
printf 'MOCK-DUMP-%s\n' "$(date +%s)"
WRAP
# Retention that cannot prune — a read-only volume, a bad mount, an IO error.
cat > "$WORK/bin-rmfail/rm" <<'WRAP'
#!/usr/bin/env bash
echo "rm: cannot remove '$*': Permission denied" >&2
exit 1
WRAP
chmod +x "$WORK/bin-fail/pg_dump" "$WORK/bin-ok/pg_dump" \
         "$WORK/bin-rmfail/pg_dump" "$WORK/bin-rmfail/rm"

# seed_old_archives <dir>: two prunable archives older than today's dump.
seed_old_archives() {
  local d="$1" i
  mkdir -p "$d"
  for i in 1 2; do
    printf 'old-%s\n' "$i" > "$d/miqrokey-2026010${i}T000000Z.sql.gz.enc"
    printf 'old-%s\n' "$i" > "$d/miqrokey-2026010${i}T000000Z.sql.gz.enc.sha256"
  done
}

# run_backup <bin-dir> <out-dir> <webhook-url> <daily-keep> <weekly-keep>
# Prints the script's exit code on stdout; stderr/stdout go to $WORK/run.log.
run_backup() {
  local bindir="$1" out="$2" hook="$3" daily="$4" weekly="$5" rc=0
  mkdir -p "$out"
  MIQROKEY_BACKUP_PATH="$out" \
  MIQROKEY_BACKUP_KEY_FILE="$KEY_FILE" \
  MIQROKEY_BACKUP_DAILY_KEEP="$daily" \
  MIQROKEY_BACKUP_WEEKLY_KEEP="$weekly" \
  MIQROKEY_DB_URL="jdbc:postgresql://127.0.0.1:5432/miqrokey" \
  MIQROKEY_BACKUP_WEBHOOK_URL="$hook" \
  PATH="$bindir:$PATH" \
    bash "$HERE/miqrokey-backup.sh" >"$WORK/run.log" 2>&1 || rc=$?
  printf '%s' "$rc"
}

fail() {
  echo "FAIL: $1" >&2
  echo "--- miqrokey-backup.sh output ---" >&2
  cat "$WORK/run.log" >&2
  exit 1
}

echo '== case 1: dump fails, no webhook (control) => rc 1 =='
RC=$(run_backup "$WORK/bin-fail" "$WORK/out1" "" 7 4)
[ "$RC" = 1 ] || fail "rc=$RC, contract says 1 (dump/encrypt failure)"
[ -z "$(ls -A "$WORK/out1")" ] || fail "partial archive left behind: $(ls -A "$WORK/out1")"
echo 'case 1 PASS: rc=1, backup dir left clean'

echo '== case 2: dump fails, webhook unreachable => rc 1 (was 7 = curl) =='
RC=$(run_backup "$WORK/bin-fail" "$WORK/out2" "$DEAD_HOOK" 7 4)
[ "$RC" = 1 ] || fail "rc=$RC, contract says 1 — an unreachable webhook replaced it"
[ -z "$(ls -A "$WORK/out2")" ] || fail "partial archive left behind: $(ls -A "$WORK/out2")"
echo 'case 2 PASS: rc=1 despite the dead webhook'

echo '== case 3: dump ok, retention cannot prune, no webhook => rc 2 =='
seed_old_archives "$WORK/out3"
RC=$(run_backup "$WORK/bin-rmfail" "$WORK/out3" "" 1 0)
[ "$RC" = 2 ] || fail "rc=$RC, contract says 2 (retention failure)"
grep -q 'cannot remove' "$WORK/run.log" || fail "the prune never actually failed; test is not exercising #1405"
if grep -q 'backup ok' "$WORK/run.log"; then fail "script claimed success while pruning failed"; fi
if grep -q 'pruned' "$WORK/run.log"; then fail "script reported a prune count while pruning failed"; fi
echo 'case 3 PASS: rc=2, no false "backup ok"/"(pruned N)" claim'

echo '== case 4: retention cannot prune AND webhook unreachable => rc 2 (#1402 x #1405) =='
seed_old_archives "$WORK/out4"
RC=$(run_backup "$WORK/bin-rmfail" "$WORK/out4" "$DEAD_HOOK" 1 0)
[ "$RC" = 2 ] || fail "rc=$RC, contract says 2 — neither the dead webhook nor the dead prune may replace it"
echo 'case 4 PASS: rc=2 on both failure paths at once'

echo '== case 5: dump ok, retention ok, webhook unreachable => rc 3 (unchanged) =='
seed_old_archives "$WORK/out5"
RC=$(run_backup "$WORK/bin-ok" "$WORK/out5" "$DEAD_HOOK" 1 0)
[ "$RC" = 3 ] || fail "rc=$RC, contract says 3 (backup ok, notification failed)"
# exit 3 happens before the "backup ok" echo, so no prune count is printed here;
# the file count is what proves retention ran.
ARCHIVES=$(ls -1 "$WORK"/out5/miqrokey-*.sql.gz.enc | wc -l)
[ "$ARCHIVES" = 1 ] || fail "expected the two old archives pruned, found $ARCHIVES archives"
echo 'case 5 PASS: rc=3, archive kept, retention genuinely pruned 2'

echo '== case 6: everything healthy, no webhook (control) => rc 0 =='
seed_old_archives "$WORK/out6"
RC=$(run_backup "$WORK/bin-ok" "$WORK/out6" "" 1 0)
[ "$RC" = 0 ] || fail "rc=$RC, contract says 0 (ok)"
grep -q '(pruned 2)' "$WORK/run.log" || fail "expected a true prune count of 2 (this is the message #1405 used to print while deleting nothing)"
echo 'case 6 PASS: rc=0, "(pruned 2)" is now a claim backed by real deletions'

echo 'exit-code contract PASS: 1/1/2/2/3/0 as documented'
