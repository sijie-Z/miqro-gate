#!/usr/bin/env bash
# MiQroKey backup-job entrypoint (#479): resolves the *_FILE secret convention
# for the database password, then runs the backup once per day at/after 02:00
# local time (TZ comes from the environment; compose sets Asia/Shanghai).
# Restart-safe: a stamp file on the backup volume marks the day as done.
set -euo pipefail

resolve_secret() {
  local base="$1" file
  file="$(eval "printf '%s' \"\${${base}_FILE:-}\"")"
  if [ -n "$file" ] && [ -f "$file" ]; then
    eval "export ${base}=\"\$(cat \"$file\")\""
  fi
}
resolve_secret MIQROKEY_DB_PASSWORD

STAMP=/var/backups/miqrokey/.last_run
while true; do
  today="$(date +%F)"
  if [ "$(date +%H%M)" -ge 0200 ] && [ "$(cat "$STAMP" 2>/dev/null || true)" != "$today" ]; then
    echo "$today" > "$STAMP"
    echo "[backup] $(date) starting daily backup"
    if bash /opt/miqrokey/backup/miqrokey-backup.sh; then
      echo "[backup] $(date) done"
    else
      # Save the status before anything else runs: the $(date) below is a
      # command substitution and would overwrite $? with its own, so a failed
      # dump, a failed retention pass and a failed notification all read rc=0.
      rc=$?
      echo "[backup] $(date) FAILED rc=$rc (webhook notification handled by the script)"
    fi
  fi
  sleep 60
done
