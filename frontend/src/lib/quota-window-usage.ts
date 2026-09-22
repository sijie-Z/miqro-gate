/**
 * #1234: 额度账本的窗口口径（共享的纯函数，两个视图共用一套）。
 *
 * 窗口全部按 UTC 日历算（与后端 AdminQuotaRuleService.window() 同口径）：
 * - 5 小时：滚动窗口 [now − 5h, now]；
 * - 本周：从「最近一个周一（含当天）00:00:00Z」起；
 * - 本月：从当月 1 日 00:00:00Z 起。
 *
 * from/to 一律格式化为秒级 ISO 字符串（去掉毫秒）：两端点最多各截掉 <1s，
 * 对窗口统计无影响，而库内测试的手算期望值写的就是秒级形式。
 */

export type QuotaWindowKey = 'ROLLING_5H' | 'WEEKLY' | 'MONTHLY';

export interface QuotaWindowRange {
  key: QuotaWindowKey;
  /** 中文标签，与 i18n 词条一一对应（'5 小时' / '本周' / '本月'）。 */
  label: string;
  /** ISO-8601（UTC，秒级），即 API 的 from。 */
  from: string;
  /** ISO-8601（UTC，秒级），即 API 的 to。 */
  to: string;
}

/** `2026-09-21T08:47:00Z` — second-precision ISO, no `.000`. */
function isoSeconds(date: Date): string {
  return date.toISOString().replace(/\.\d{3}Z$/, 'Z');
}

/** The most recent Monday at 00:00:00Z, counting today when it is a Monday. */
function mondayStart(now: Date): Date {
  const start = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate()));
  // getUTCDay(): 0=Sunday … 6=Saturday; a Monday is 1 and needs no step back.
  const stepBack = (start.getUTCDay() + 6) % 7;
  start.setUTCDate(start.getUTCDate() - stepBack);
  return start;
}

/** First day of the current UTC month at 00:00:00Z. */
function monthStart(now: Date): Date {
  return new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), 1));
}

/**
 * 单一「本月」窗口（当月 1 日 00:00:00Z 起），给所有自称「本月」的汇总用。
 *
 * 省略 from/to 不是「本月」：后端把缺省窗口解析成 MAX_WINDOW = 93 天
 * （`UsageStatsService.java:127`、`AdminUsageStatsService.java:296`），画出来的是近三个月。
 * `windowRanges` 的第三项就是这一份，一个定义两处引用。
 */
export function monthlyRange(now: Date): QuotaWindowRange {
  return {
    key: 'MONTHLY',
    label: '本月',
    from: isoSeconds(monthStart(now)),
    to: isoSeconds(now),
  };
}

/**
 * The three ledger windows for a given instant, in display order.
 * Pure: the caller passes `now` so tests can freeze it.
 */
export function windowRanges(now: Date): QuotaWindowRange[] {
  const to = isoSeconds(now);
  return [
    {
      key: 'ROLLING_5H',
      label: '5 小时',
      from: isoSeconds(new Date(now.getTime() - 5 * 60 * 60 * 1_000)),
      to,
    },
    { key: 'WEEKLY', label: '本周', from: isoSeconds(mondayStart(now)), to },
    monthlyRange(now),
  ];
}

/** The `tokens` block of an admin usage summary's `totals` (all fields optional). */
export interface UsageTotalsTokens {
  input?: number | null;
  output?: number | null;
  cacheRead?: number | null;
  cacheCreation?: number | null;
}

/**
 * The window's used tokens under the ledger rule: input + output only, cache
 * excluded (the same rule as the backend's QuotaSnapshotService.usedTokensSince).
 *
 * Returns null — never 0 — when either half is missing or not a finite number:
 * a read whose shape is broken must be drawn as a failed read, not as "no
 * usage". A genuine numeric 0 pair is a real answer and stays 0.
 */
export function usedInputOutputTokens(tokens: UsageTotalsTokens | null | undefined): number | null {
  const input = tokens?.input;
  const output = tokens?.output;
  if (typeof input !== 'number' || !Number.isFinite(input)) return null;
  if (typeof output !== 'number' || !Number.isFinite(output)) return null;
  return input + output;
}
