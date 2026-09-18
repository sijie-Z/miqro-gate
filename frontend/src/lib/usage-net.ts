/**
 * Net (adjustment-inclusive) readings of a usage record (#773).
 *
 * The read model keeps the observed count and the adjusted reading apart:
 * `usage_event` is the immutable observed fact, `usage_adjustments` is an
 * append-only correction, and the net counts are the reporting reading of the
 * two (docs/usage-accounting.md §6). The API hands back both, and the summary
 * endpoint reports the net figures — so a table that showed only observed
 * counts would fail to add up to the total printed directly above it.
 *
 * Hence: the table reports net, and anything derived from an adjustment says
 * which reading it is. Neither number is allowed to silently impersonate the
 * other.
 */

/** The four token dimensions: display label plus the observed / net field names. */
const DIMENSIONS = [
  { label: '输入', observed: 'inputTokens', net: 'netInputTokens' },
  { label: '输出', observed: 'outputTokens', net: 'netOutputTokens' },
  { label: '缓存读', observed: 'cacheReadInputTokens', net: 'netCacheReadInputTokens' },
  { label: '缓存写', observed: 'cacheCreationInputTokens', net: 'netCacheCreationInputTokens' },
] as const;

export interface UsageNetFields {
  inputTokens?: number | null;
  outputTokens?: number | null;
  cacheReadInputTokens?: number | null;
  cacheCreationInputTokens?: number | null;
  netInputTokens?: number | null;
  netOutputTokens?: number | null;
  netCacheReadInputTokens?: number | null;
  netCacheCreationInputTokens?: number | null;
  adjusted?: boolean | null;
}

export interface ChangedDimension {
  label: string;
  observed: number;
  net: number;
}

/**
 * The count to report for one dimension: the net count where the row carries
 * one, otherwise the observed count.
 *
 * The fallback cannot invent a number — the net column is null exactly where the
 * observed one is (both describe a dimension the event never used).
 */
export function netOrObserved(net?: number | null, observed?: number | null): number | null {
  return net ?? observed ?? null;
}

/** The four dimensions' reporting counts, ready for a table cell. */
export function netTokens(record: UsageNetFields) {
  return {
    input: netOrObserved(record.netInputTokens, record.inputTokens),
    output: netOrObserved(record.netOutputTokens, record.outputTokens),
    cacheRead: netOrObserved(record.netCacheReadInputTokens, record.cacheReadInputTokens),
    cacheCreation: netOrObserved(
      record.netCacheCreationInputTokens,
      record.cacheCreationInputTokens,
    ),
  };
}

/** Dimensions whose net count actually differs from the observed one. */
export function changedDimensions(record: UsageNetFields): ChangedDimension[] {
  const changed: ChangedDimension[] = [];
  for (const dimension of DIMENSIONS) {
    const observed = record[dimension.observed] ?? null;
    const net = record[dimension.net] ?? null;
    if (observed !== null && net !== null && observed !== net) {
      changed.push({ label: dimension.label, observed, net });
    }
  }
  return changed;
}

function count(value: number): string {
  return value.toLocaleString();
}

/**
 * The bubble text for a row's adjustment.
 *
 * A row can carry adjustments that net out to zero — a correction and its
 * reversal — so "no dimension changed" is a real outcome and says so, rather
 * than falling through to an empty bubble.
 */
export function adjustmentNote(record: UsageNetFields): string {
  const changed = changedDimensions(record);
  if (changed.length === 0) {
    return '存在调整记录，但净额与观测一致（已冲销）';
  }
  return changed.map((d) => `${d.label} ${count(d.observed)} → ${count(d.net)}`).join(' · ');
}
