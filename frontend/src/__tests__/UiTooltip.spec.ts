import { describe, expect, it } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { nextTick } from 'vue';
import { UiTooltip } from '@/ui';

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

  it('#655: disabled keeps the slot rendered but never opens the bubble', async () => {
    const wrapper = mount(UiTooltip, {
      props: { text: '解释文本', disabled: true },
      slots: { default: '<button type="button">状态</button>' },
    });
    expect(wrapper.find('button').text()).toBe('状态');

    await wrapper.find('.ui-tooltip__anchor').trigger('focus');
    await flushPromises();
    expect(document.querySelector('.ui-tooltip')).toBeFalsy();
  });

  it('#655: asChild merges the trigger onto the slotted element (no wrapper)', async () => {
    const wrapper = mount(UiTooltip, {
      props: { text: '总览', asChild: true },
      slots: { default: '<a href="#x" class="nav-link">导航</a>' },
    });
    expect(wrapper.find('.ui-tooltip__anchor').exists()).toBe(false);

    const link = wrapper.find('.nav-link');
    expect(link.exists()).toBe(true);
    await link.trigger('focus');
    await flushPromises();
    expect(document.querySelector('.ui-tooltip')?.textContent).toContain('总览');
  });
});
