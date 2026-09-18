import { describe, expect, it } from 'vitest';
import { costGapNote } from '@/lib/usage-pricing';

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
});
