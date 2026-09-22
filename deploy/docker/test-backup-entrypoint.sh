#!/usr/bin/env bash
# Behavioural test for deploy/docker/backup-entrypoint.sh (#1390).
#
# The entrypoint is what actually runs the backup in production (the `backup`
# service in deploy/compose.prod.yaml, restart: unless-stopped). Its log line is
# the only place the documented exit-code contract is visible by default —
# MIQROKEY_BACKUP_WEBHOOK_URL is empty unless an operator sets it — so a log
# line that reports the wrong code is the difference between "a human can tell
# what to fix" and "nobody knows whether last night's backup ran".
#
# ShellCheck cannot ask this question: `rc=$?` after a command substitution is
# perfectly valid syntax that reports the wrong number. So this runs the real
# entrypoint, in the real image, against a stub backup script with a known exit
# code, and reads what the entrypoint printed.
#
# Only two things are faked, neither of them the subject: the backup script
# behind it (a one-line `exit N`) and the clock (a PATH shim), because the
# 02:00 gate decides whether the backup runs at all and the test must not pass
# or fail depending on the hour of the day. The retry/stamp policy is
# deliberately NOT pinned here — it is a design decision (#1390), not a
# contract.
#
# Run: bash deploy/docker/test-backup-entrypoint.sh
# Needs: docker. One container, the same pinned base image as the backup image.
set -euo pipefail

IMAGE="postgres:17.6-alpine@sha256:ef257d85f76e48da1c64832459b59fcaba1a4dac97bf5d7450c77753542eee94"
PREFIX="miqrokey-test-backup-entrypoint-$$"
HERE=$(cd "$(dirname "$0")" && pwd)
CASES=4
FAILURES=0
STARTED=""
LOGS=""

# Git Bash (Windows) rewrites container paths that begin with a slash; cygpath
# plus MSYS_NO_PATHCONV is the same combination deploy/backup/*.sh use.
if command -v cygpath >/dev/null 2>&1; then
  winpath() { cygpath -w "$1"; }
  export MSYS_NO_PATHCONV=1
else
  winpath() { printf '%s' "$1"; }
fi

WORK=$(mktemp -d)
cleanup() {
  # shellcheck disable=SC2086 # STARTED is a list of container names
  [ -z "$STARTED" ] || docker rm -f $STARTED >/dev/null 2>&1 || true
  rm -f "$WORK/stub/miqrokey-backup.sh" "$WORK/shim/date"
  rmdir "$WORK/stub" "$WORK/shim" "$WORK" 2>/dev/null || true
}
trap cleanup EXIT

mkdir -p "$WORK/stub" "$WORK/shim"

# Fixed clock, past 02:00: the daily gate opens on the first loop iteration, and
# every case sees the same timestamp.
cat > "$WORK/shim/date" <<'SHIM'
#!/bin/sh
case "${1:-}" in
  +%F)   echo 2026-09-22 ;;
  +%H%M) echo 0300 ;;
  *)     echo "2026-09-22 03:00:00 UTC" ;;
esac
SHIM
chmod +x "$WORK/shim/date"

# Runs the real entrypoint once against a backup script that exits <rc> and
# leaves the log it produced in $LOGS.
#
# Every case gets its own container: `docker rm -f` releases the name
# asynchronously, so reusing one name across cases races with the removal and
# fails as "name already in use" — which a command substitution would then
# report as an empty log rather than an error.
run_entrypoint() {
  local rc="$1" name="$PREFIX-$1"
  printf '#!/usr/bin/env bash\n# stub standing in for the real backup script\nexit %s\n' \
    "$rc" > "$WORK/stub/miqrokey-backup.sh"
  # --tmpfs stands in for the `backups` named volume that compose mounts at
  # /var/backups/miqrokey (deploy/compose.prod.yaml:222): the base image has no
  # such directory — the backup image's Dockerfile creates it — and without a
  # writable one the entrypoint dies writing its day stamp before it ever runs
  # the backup. Nothing under test lives in that directory.
  docker run -d --name "$name" --entrypoint /bin/bash \
    --tmpfs /var/backups/miqrokey:mode=1777 \
    -v "$(winpath "$WORK/stub"):/opt/miqrokey/backup:ro" \
    -v "$(winpath "$WORK/shim"):/shim:ro" \
    -v "$(winpath "$HERE/backup-entrypoint.sh"):/usr/local/bin/backup-entrypoint.sh:ro" \
    -e PATH="/shim:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
    "$IMAGE" /usr/local/bin/backup-entrypoint.sh >/dev/null
  STARTED="$STARTED $name"
  # The gate opens immediately, so the attempt lands within a second or two —
  # poll instead of sleeping a fixed amount.
  local _
  LOGS=""
  for _ in $(seq 1 30); do
    LOGS=$(docker logs "$name" 2>&1) || LOGS=""
    case "$LOGS" in
      *"FAILED rc="* | *" done"*) return 0 ;;
    esac
    sleep 1
  done
  echo "the entrypoint in $name logged no attempt within 30s; container state:" >&2
  docker inspect -f '  status={{.State.Status}} exit={{.State.ExitCode}} error={{.State.Error}}' \
    "$name" >&2 || true
  return 1
}

check() {  # <description> <1 = passed>
  if [ "$2" = 1 ]; then
    echo "  PASS  $1"
  else
    echo "  FAIL  $1"
    FAILURES=$((FAILURES + 1))
  fi
}

for rc in 0 1 2 3; do
  echo "== backup script exits $rc =="
  # Deliberately not a command substitution: run_entrypoint reports through
  # $LOGS and $STARTED, and a subshell would keep both to itself — cleanup
  # would have nothing to remove and every case would read an empty log.
  if ! run_entrypoint "$rc"; then
    echo "  FAIL  the entrypoint logged no attempt for rc=$rc (see above)" >&2
    FAILURES=$((FAILURES + 1))
    continue
  fi
  printf '%s\n' "$LOGS" | sed 's/^/    | /'
  if [ "$rc" = 0 ]; then
    check "a successful backup is logged as done" \
      "$(case "$LOGS" in *" done"*) echo 1 ;; *) echo 0 ;; esac)"
  else
    check "exit $rc is reported as FAILED rc=$rc" \
      "$(case "$LOGS" in *"FAILED rc=$rc "*) echo 1 ;; *) echo 0 ;; esac)"
  fi
done

if [ "$FAILURES" -eq 0 ]; then
  echo "backup entrypoint: $CASES/$CASES checks passed"
else
  echo "backup entrypoint: $((CASES - FAILURES))/$CASES checks passed" >&2
  exit 1
fi
