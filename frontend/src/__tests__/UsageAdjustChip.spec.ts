import { afterEach, describe, expect, it } from 'vitest';
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils';
import { nextTick } from 'vue';
import UsageAdjustChip from '@/components/UsageAdjustChip.vue';
import type { UsageNetFields } from '@/lib/usage-net';

// Teleported popper layers live on document.body; leave no layer behind for the
// next test's negative assertions.
enableAutoUnmount(afterEach);

describe('UsageAdjustChip', () => {
  function mountChip(record: UsageNetFields) {
    return mount(UsageAdjustChip, { props: { record } });
  }

  it('leaves an unadjusted row unmarked', () => {
    const wrapper = mountChip({ inputTokens: 1_000, netInputTokens: 1_000, adjusted: false });

    expect(wrapper.text()).toBe('—');
    expect(wrapper.find('[data-testid="usage-adjust-chip"]').exists()).toBe(false);
  });

  it('marks an adjusted row and explains it on focus', async () => {
    const wrapper = mountChip({
      inputTokens: 1_000,
      netInputTokens: 2_000,
      adjusted: true,
    });

    expect(wrapper.find('[data-testid="usage-adjust-chip"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('已调整');

    await wrapper.find('.ui-tooltip__anchor').trigger('focus');
    await nextTick();
    await flushPromises();
    // The bubble carries the observed count the net one was derived from.
    expect(document.querySelector('.ui-tooltip')?.textContent).toContain('输入 1,000 → 2,000');
  });

  it('marks a row whose adjustment has been reversed', async () => {
    // The correction and its reversal cancel out, so every token column reads
    // the same as an untouched row — the chip is the only thing that says the
    // row carries an adjustment at all.
    const wrapper = mountChip({
      inputTokens: 1_000,
      netInputTokens: 1_000,
      adjusted: true,
    });

    expect(wrapper.find('[data-testid="usage-adjust-chip"]').exists()).toBe(true);

    await wrapper.find('.ui-tooltip__anchor').trigger('focus');
    await nextTick();
    await flushPromises();
    expect(document.querySelector('.ui-tooltip')?.textContent).toContain('已冲销');
  });
});
