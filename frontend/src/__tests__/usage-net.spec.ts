import { describe, expect, it } from 'vitest';
import {
  adjustmentNote,
  changedDimensions,
  netOrObserved,
  netTokens,
  type UsageNetFields,
} from '@/lib/usage-net';

describe('usage-net', () => {
  describe('netOrObserved', () => {
    it('prefers the net count', () => {
      expect(netOrObserved(1_500, 1_000)).toBe(1_500);
    });

    it('falls back to the observed count when the row carries no net count', () => {
      expect(netOrObserved(null, 1_000)).toBe(1_000);
      expect(netOrObserved(undefined, 1_000)).toBe(1_000);
    });

    it('invents nothing when neither count exists', () => {
      // A dimension the event never used stays empty — the fallback must not
      // turn "no such count" into a zero.
      expect(netOrObserved(null, null)).toBeNull();
      expect(netOrObserved(undefined, undefined)).toBeNull();
    });
  });

  describe('netTokens', () => {
    it('reports net where present and observed elsewhere', () => {
      const record: UsageNetFields = {
        inputTokens: 1_000,
        netInputTokens: 2_000,
        outputTokens: 500,
        netOutputTokens: 500,
        cacheReadInputTokens: null,
        netCacheReadInputTokens: null,
      };

      expect(netTokens(record)).toEqual({
        input: 2_000,
        output: 500,
        cacheRead: null,
        cacheCreation: null,
      });
    });
  });

  describe('changedDimensions', () => {
    it('lists only the dimensions the adjustment actually moved', () => {
      const record: UsageNetFields = {
        inputTokens: 1_000,
        netInputTokens: 2_000,
        outputTokens: 500,
        netOutputTokens: 500,
      };

      expect(changedDimensions(record)).toEqual([{ label: '输入', observed: 1_000, net: 2_000 }]);
    });

    it('is empty when the adjustment nets out', () => {
      const record: UsageNetFields = { inputTokens: 1_000, netInputTokens: 1_000 };

      expect(changedDimensions(record)).toEqual([]);
    });
  });

  describe('adjustmentNote', () => {
    it('shows the observed count beside the net one', () => {
      const record: UsageNetFields = {
        inputTokens: 1_000,
        netInputTokens: 2_000,
        outputTokens: 500,
        netOutputTokens: 400,
      };

      expect(adjustmentNote(record)).toBe('输入 1,000 → 2,000 · 输出 500 → 400');
    });

    it('says the row was reversed rather than showing an empty bubble', () => {
      // A correction plus its reversal is a real state: the row is adjusted and
      // its numbers are unchanged. An empty bubble would read as "no adjustment".
      const record: UsageNetFields = {
        inputTokens: 1_000,
        netInputTokens: 1_000,
        adjusted: true,
      };

      expect(changedDimensions(record)).toEqual([]);
      expect(adjustmentNote(record)).toContain('已冲销');
    });
  });
});
