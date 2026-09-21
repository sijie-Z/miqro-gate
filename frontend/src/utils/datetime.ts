/**
 * Local calendar day of an instant, as `YYYY-MM-DD`.
 *
 * Timestamps reach the console as ISO-8601 UTC strings. Slicing one
 * (`String(iso).slice(0, 10)`) yields the *UTC* day, which is not the day the
 * viewer is in: at UTC+8 a record logged 2026-09-04 00:30 sliced back to
 * 2026-09-03. Every other timestamp the console prints goes through local
 * getters (see `formatTime` in `NextUsageView.vue`), so day labels have to be
 * derived the same way or the same record carries two different dates.
 *
 * Returns `''` for a missing or unparseable value so callers can pick their own
 * placeholder.
 */
export function localDayKey(iso?: string | null): string {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

/**
 * The viewer's fixed offset from UTC, in minutes (#1050) — the value the
 * reporting endpoints take so `groupBy=day|month` buckets land on the local
 * calendar day the console prints beside each row, the same shape the hourly
 * report already uses. A fixed offset, not an IANA zone: a window spanning a
 * DST change is approximated, which is the documented contract trade-off.
 */
export function localTzOffsetMinutes(date: Date = new Date()): number {
  return -date.getTimezoneOffset();
}
