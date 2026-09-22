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
 * `YYYY-MM-DD HH:mm` in the viewer's zone — the shape the console prints beside
 * a record (`formatTime` in `NextUsageView.vue`).
 *
 * Slicing the ISO string instead (`iso.slice(0, 16).replace('T', ' ')`) looks
 * like it produces the same characters, but it renders the *UTC* wall clock and
 * silently drops the zone, so at UTC+8 a probe taken at 09:30 local reads as
 * 01:30, and at UTC−4 the date is off by a whole day. Callers that want the
 * string's own value must say so; anything shown to a person goes through here.
 *
 * Returns `''` for a missing or unparseable value so callers can pick their own
 * placeholder.
 */
export function localDateTime(iso?: string | null): string {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(
    d.getHours(),
  )}:${pad(d.getMinutes())}`;
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

/**
 * Local wall clock of an instant *plus* an explicit UTC offset, as
 * `YYYY-MM-DD HH:mm:ss±HH:mm` (#1417).
 *
 * For values that leave the console inside a file. On screen a bare local time
 * is safe — the reader is standing in the zone it was rendered in. A CSV is
 * not: it gets mailed, pasted into a ledger and read by someone in another
 * zone, and a bare local time there is ambiguous while the raw UTC string is
 * simply a different clock reading from the one the exporter saw in the table
 * above it. Carrying the offset keeps both: it is the time that was on screen,
 * and it still names an unambiguous instant.
 */
export function localDateTimeWithOffset(iso?: string | null): string {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  const pad = (n: number) => String(n).padStart(2, '0');
  const offset = localTzOffsetMinutes(d);
  const sign = offset < 0 ? '-' : '+';
  const abs = Math.abs(offset);
  return (
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ` +
    `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}` +
    `${sign}${pad(Math.floor(abs / 60))}:${pad(abs % 60)}`
  );
}
