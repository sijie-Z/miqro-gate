import { describe, expect, it } from 'vitest';
import { costGapNote, savingsBoundNote, unpricedHitCount } from '@/lib/usage-pricing';

describe('usage-pricing', () => {
  describe('costGapNote', () => {
    it('says nothing when the cost is a total', () => {
      expect(costGapNote({ pricingStatus: 'COMPLETE' })).toBeNull();
    });

    it('says nothing when the status is not one it knows', () => {
      // The marker is for a *known* gap: putting a caveat on a guess would qualify
      // figures that have nothing wrong with them.
      expect(costGapNote({})).toBeNull();
      expect(costGapNote(null)).toBeNull();
    });

    it('names the gap when part of the usage could not be priced', () => {
      const note = costGapNote({
        pricingStatus: 'PARTIAL',
        unpriced: { unpricedEvents: 617, unavailableEvents: 565 },
      });

      expect(note).toContain('617');
      expect(note).toContain('565');
      // The reader's takeaway has to be about the number they are looking at.
      expect(note).toContain('不是总额');
    });

    it('says the amount is unknowable when nothing could be priced', () => {
      const note = costGapNote({ pricingStatus: 'UNAVAILABLE', unpriced: { unpricedEvents: 12 } });

      expect(note).toContain('没有生效价目');
      expect(note).toContain('不是总额');
    });
  });

  describe('unpricedHitCount', () => {
    it('counts only hits no price could value', () => {
      expect(
        unpricedHitCount({
          pricingStatus: 'PARTIAL',
          // Events that could not be priced say nothing about the hits: different gap.
          unpriced: { unpricedEvents: 617, unavailableEvents: 12, unpricedHitEvents: 3 },
        }),
      ).toBe(3);
    });

    it('is zero when nothing said otherwise', () => {
      expect(unpricedHitCount({})).toBe(0);
      expect(unpricedHitCount(null)).toBe(0);
    });
  });

  describe('savingsBoundNote', () => {
    it('says nothing when every hit could be valued', () => {
      expect(
        savingsBoundNote({ pricingStatus: 'COMPLETE', unpriced: { unpricedHitEvents: 0 } }),
      ).toBeNull();
      expect(savingsBoundNote({})).toBeNull();
      expect(savingsBoundNote(null)).toBeNull();
    });

    it('names the bound when hits went unpriced (#790)', () => {
      const note = savingsBoundNote({ unpriced: { unpricedHitEvents: 7 } });

      expect(note).toContain('7');
      expect(note).toContain('下界');
    });

    it('still speaks when the cost is COMPLETE — the case costGapNote swallows', () => {
      // The API keeps the two gaps independent on purpose (#790): a group whose usage
      // priced fine but whose *hits* did not reports COMPLETE. Were the bound folded
      // into costGapNote, it would go silent for exactly the case it exists to catch.
      const totals = { pricingStatus: 'COMPLETE', unpriced: { unpricedHitEvents: 2 } };

      expect(costGapNote(totals)).toBeNull();
      expect(savingsBoundNote(totals)).not.toBeNull();
    });

    it('makes a claim that does not overlap costGapNote when both apply', () => {
      const totals = {
        pricingStatus: 'PARTIAL',
        unpriced: { unpricedEvents: 3, unavailableEvents: 1, unpricedHitEvents: 5 },
      };

      // Different assertions: the amount is short of a total / the saving is a floor.
      expect(costGapNote(totals)).toContain('不是总额');
      expect(savingsBoundNote(totals)).toContain('下界');
      expect(savingsBoundNote(totals)).not.toContain('不是总额');
    });
  });
});
