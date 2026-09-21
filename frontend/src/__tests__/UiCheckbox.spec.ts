import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import { UiCheckbox, UiRadio } from '@/ui';

describe('UiCheckbox', () => {
  it('toggles a boolean model and emits change', async () => {
    const wrapper = mount(UiCheckbox, {
      props: { modelValue: false, label: '启用' },
      attrs: { 'data-testid': 'cb' },
    });
    const input = wrapper.find('[data-testid="cb"]');
    expect(input.attributes('type')).toBe('checkbox');
    (input.element as HTMLInputElement).checked = true;
    await input.trigger('change');
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([true]);
    expect(wrapper.emitted('change')?.[0]).toEqual([true]);
  });

  it('renders checked state from a boolean model', () => {
    const wrapper = mount(UiCheckbox, { props: { modelValue: true } });
    expect((wrapper.find('input').element as HTMLInputElement).checked).toBe(true);
  });

  it('adds and removes values in array mode', async () => {
    const wrapper = mount(UiCheckbox, {
      props: { modelValue: ['a'], value: 'b' },
    });
    const input = wrapper.find('input');
    (input.element as HTMLInputElement).checked = true;
    await input.trigger('change');
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([['a', 'b']]);

    await wrapper.setProps({ modelValue: ['a', 'b'] });
    (input.element as HTMLInputElement).checked = false;
    await input.trigger('change');
    expect(wrapper.emitted('update:modelValue')?.[1]).toEqual([['a']]);
  });

  it('mutates Set models in place, matching native checkbox v-model', async () => {
    const ids = new Set<string>(['x']);
    const wrapper = mount(UiCheckbox, { props: { modelValue: ids, value: 'y' } });
    const input = wrapper.find('input');
    (input.element as HTMLInputElement).checked = true;
    await input.trigger('change');
    expect(ids.has('y')).toBe(true);
    const payload = wrapper.emitted('update:modelValue')?.[0]?.[0];
    expect(payload).toBeInstanceOf(Set);
    expect([...(payload as Set<string>)]).toEqual(['x', 'y']);
  });

  it('honours the controlled checked override', () => {
    const wrapper = mount(UiCheckbox, {
      props: { modelValue: false, checked: true },
    });
    expect((wrapper.find('input').element as HTMLInputElement).checked).toBe(true);
  });

  it('ignores interaction when disabled', async () => {
    const wrapper = mount(UiCheckbox, { props: { modelValue: false, disabled: true } });
    const input = wrapper.find('input');
    expect(input.attributes('disabled')).toBeDefined();
    (input.element as HTMLInputElement).checked = true;
    await input.trigger('change');
    expect(wrapper.emitted('update:modelValue')).toBeUndefined();
  });
});

describe('UiRadio', () => {
  it('emits its value when the group model changes to it', async () => {
    const wrapper = mount(UiRadio, {
      props: { modelValue: 'a', value: 'b', label: '自定义通道' },
      attrs: { 'data-testid': 'radio-b' },
    });
    const input = wrapper.find('[data-testid="radio-b"]');
    expect(input.attributes('type')).toBe('radio');
    expect((input.element as HTMLInputElement).checked).toBe(false);
    (input.element as HTMLInputElement).checked = true;
    await input.trigger('change');
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual(['b']);
  });

  it('shows checked when the model matches', () => {
    const wrapper = mount(UiRadio, { props: { modelValue: 'b', value: 'b' } });
    expect((wrapper.find('input').element as HTMLInputElement).checked).toBe(true);
  });
});
