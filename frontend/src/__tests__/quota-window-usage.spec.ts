import { describe, expect, it } from 'vitest';
import { usedInputOutputTokens, windowRanges } from '@/lib/quota-window-usage';

/**
 * #1234: 额度账本的三窗口口径（权限在 UTC 日历上，与 AdminQuotaRuleService.window() 同口径）：
 * - 5 小时 = 滚动窗口 [now−5h, now]；
 * - 本周 = 从「最近一个周一（含今天）00:00:00Z」起；
 * - 本月 = 从当月 1 日 00:00:00Z 起。
 *
 * 期望值全部手算写死，不许拿实现再算一遍。星期事实（node 复算过）：
 * 2026-09-21 是周一、2026-09-20 是周日、2026-09-01 是周二、2026-08-31 是周一。
 */
describe('windowRanges (#1234)', () => {
  it('freezes a plain Monday: rolling 5h, this Monday, this month', () => {
    // 13:47 − 5h = 08:47; a Monday counts as its own week start
    // ("上一个周一（含当天）").
    expect(windowRanges(new Date('2026-09-21T13:47:00Z'))).toEqual([
      {
        key: 'ROLLING_5H',
        label: '5 小时',
        from: '2026-09-21T08:47:00Z',
        to: '2026-09-21T13:47:00Z',
      },
      { key: 'WEEKLY', label: '本周', from: '2026-09-21T00:00:00Z', to: '2026-09-21T13:47:00Z' },
      { key: 'MONTHLY', label: '本月', from: '2026-09-01T00:00:00Z', to: '2026-09-21T13:47:00Z' },
    ]);
  });

  it('rolls a Sunday back to the previous Monday', () => {
    expect(windowRanges(new Date('2026-09-20T10:00:00Z'))).toEqual([
      {
        key: 'ROLLING_5H',
        label: '5 小时',
        from: '2026-09-20T05:00:00Z',
        to: '2026-09-20T10:00:00Z',
      },
      { key: 'WEEKLY', label: '本周', from: '2026-09-14T00:00:00Z', to: '2026-09-20T10:00:00Z' },
      { key: 'MONTHLY', label: '本月', from: '2026-09-01T00:00:00Z', to: '2026-09-20T10:00:00Z' },
    ]);
  });

  it('steps back across a month boundary when the month opens on a Tuesday', () => {
    // 2026-09-01 is a Tuesday: the week window starts in August, the month window
    // does not — one window may cross a boundary the other never sees.
    expect(windowRanges(new Date('2026-09-01T00:30:00Z'))).toEqual([
      {
        key: 'ROLLING_5H',
        label: '5 小时',
        from: '2026-08-31T19:30:00Z',
        to: '2026-09-01T00:30:00Z',
      },
      { key: 'WEEKLY', label: '本周', from: '2026-08-31T00:00:00Z', to: '2026-09-01T00:30:00Z' },
      { key: 'MONTHLY', label: '本月', from: '2026-09-01T00:00:00Z', to: '2026-09-01T00:30:00Z' },
    ]);
  });
});

/**
 * 已用量口径 = 输入 + 输出 Token（与仓内 QuotaSnapshotService.usedTokensSince 一致），
 * 不含 cache 读写。形状不完整一律返回 null，由视图当「读取失败」处理——绝不画成 0；
 * 但真实的数字 0 是 0。
 */
describe('usedInputOutputTokens (#1234)', () => {
  it('sums input and output', () => {
    expect(usedInputOutputTokens({ input: 1_000, output: 500 })).toBe(1_500);
  });

  it('ignores cache reads and cache creation', () => {
    // totals.tokens.total() would include both; the ledger explicitly does not.
    expect(
      usedInputOutputTokens({ input: 10, output: 5, cacheRead: 999, cacheCreation: 888 }),
    ).toBe(15);
  });

  it('keeps a genuine numeric zero as zero', () => {
    // An empty window is a real answer, not a failed read.
    expect(usedInputOutputTokens({ input: 0, output: 0 })).toBe(0);
  });

  it('returns null — never 0 — when any part of the pair is missing', () => {
    expect(usedInputOutputTokens({ input: 100 })).toBeNull();
    expect(usedInputOutputTokens({ output: 100 })).toBeNull();
    expect(usedInputOutputTokens({})).toBeNull();
    expect(usedInputOutputTokens(null)).toBeNull();
    expect(usedInputOutputTokens(undefined)).toBeNull();
  });

  it('rejects non-numeric values instead of coercing them', () => {
    // The wire format is plain JSON numbers; a string here means the shape drifted,
    // which is a failed read rather than something to parse.
    expect(usedInputOutputTokens({ input: '100', output: 50 } as never)).toBeNull();
    expect(usedInputOutputTokens({ input: Number.NaN, output: 50 })).toBeNull();
    expect(usedInputOutputTokens({ input: Number.POSITIVE_INFINITY, output: 50 })).toBeNull();
  });
});
