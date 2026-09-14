#!/usr/bin/env bash
# Retention helper for miqrokey-backup.sh (G6.2 / #438): kept in its own file
# so test-retention.sh can exercise the semantics without a database.
#
# Spec (operations-runbook §备份与恢复): keep the newest <daily-keep> daily
# files, then the newest file of each of the <weekly-keep> most recent distinct
# ISO weeks (Monday start) among the older files; a backup is kept at most
# once; everything older is pruned together with its .sha256 manifest.
#
# apply_retention <dir> <daily-keep> <weekly-keep>
#   Prints the number of pruned archives on stdout.
apply_retention() {
  local dir="$1" daily_keep="$2" weekly_keep="$3"
  local all keep rest pruned=0 kept_weeks="" weeks_kept=0 f stamp iso week
  all=$(ls -1 "$dir"/miqrokey-*.sql.gz.enc 2>/dev/null | sort -r || true)
  [ -n "$all" ] || { echo 0; return 0; }

  # Newest <daily-keep> archives are kept unconditionally …
  keep=$(printf '%s\n' "$all" | sed -n "1,${daily_keep}p")
  # … the rest competes for one slot per distinct ISO week (newest first).
  rest=$(printf '%s\n' "$all" | sed -n "$((daily_keep + 1)),\$p")
  while IFS= read -r f; do
    [ -n "$f" ] || continue
    stamp=$(basename "$f")
    stamp=${stamp#miqrokey-}
    # busybox date（postgres:alpine 备份镜像）不认紧凑 YYYYMMDD，先转 ISO 再解析。
    iso="${stamp:0:4}-${stamp:4:2}-${stamp:6:2}"
    week=$(date -u -d "$iso" +%G%V 2>/dev/null || echo unknown)
    if [ "$weeks_kept" -lt "$weekly_keep" ] && ! printf '%s\n' "$kept_weeks" | grep -qxF "$week"; then
      kept_weeks="$kept_weeks$week"$'\n'
      weeks_kept=$((weeks_kept + 1))
      keep="$keep"$'\n'"$f"
    else
      rm -f -- "$f" "$f.sha256"
      pruned=$((pruned + 1))
    fi
  done <<<"$rest"
  echo "$pruned"
}
