import { afterEach, describe, expect, it } from 'vitest';
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils';
import { nextTick } from 'vue';
import { UiTooltip } from '@/ui';

// Teleported popper layers live on document.body; leave no layer behind for
// the next test's negative assertions.
enableAutoUnmount(afterEach);

describe('UiTooltip', () => {
  function mountTip() {
    return mount(UiTooltip, {
      props: { text: '解释文本' },
      slots: { default: '<button type="button">状态</button>' },
    });
  }

  it('renders the slotted trigger and shows the bubble on focus', async () => {
    const wrapper = mountTip();
    expect(wrapper.find('button').text()).toBe('状态');

    await wrapper.find('.ui-tooltip__anchor').trigger('focus');
    await nextTick();
    await flushPromises();
    expect(document.querySelector('.ui-tooltip')?.textContent).toContain('解释文本');
  });

  it('hides the bubble again on blur', async () => {
    const wrapper = mountTip();
    const anchor = wrapper.find('.ui-tooltip__anchor');
    await anchor.trigger('focus');
    await flushPromises();
    expect(document.querySelector('.ui-tooltip')).toBeTruthy();

    await anchor.trigger('blur');
    await flushPromises();
    expect(document.querySelector('.ui-tooltip')).toBeFalsy();
  });
});
