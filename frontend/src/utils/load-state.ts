/**
 * #1120: a count is a conclusion about the data, so it may only be drawn from a
 * read that succeeded. After a failure the number is unknown, not zero — the
 * same rule the tables answer with since #1065 (`UiTable.error`) and the cards
 * since #1104. Views render these counters as `共 {{ countWhenLoaded(loadError,
 * rows.length) }} 个…` so a failed read shows `—` instead of claiming "none".
 */
export function countWhenLoaded(loadError: string, value: number | string): string {
  return loadError ? '—' : String(value);
}
