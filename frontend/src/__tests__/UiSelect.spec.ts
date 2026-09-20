import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import { UiSelect } from '@/ui';

/**
 * Same labelling contract as UiInput, but the control is radix-vue's trigger
 * (a real <button>), so the assertions target it rather than an <input>.
 */
const OPTIONS = [
  { value: 'a', label: '选项 A' },
  { value: 'b', label: '选项 B' },
];

function control(wrapper: ReturnType<typeof mount>): HTMLButtonElement {
  return wrapper.get('button').element as HTMLButtonElement;
}

describe('UiSelect labelling', () => {
  it('associates the visible label with the trigger', () => {
    const wrapper = mount(UiSelect, { props: { label: '供应商产品', options: OPTIONS } });
    const labels = [...control(wrapper).labels];
    expect(labels.map((label) => label.textContent?.trim())).toEqual(['供应商产品']);
  });

  it('associates the label even when the field is required', () => {
    const wrapper = mount(UiSelect, {
      props: { label: '项目', required: true, options: OPTIONS },
    });
    const labels = [...control(wrapper).labels];
    expect(labels.map((label) => label.textContent?.trim())).toEqual(['项目 *']);
  });

  it('exposes required to assistive tech, not just as a visual asterisk', () => {
    const wrapper = mount(UiSelect, {
      props: { label: '项目', required: true, options: OPTIONS },
    });
    expect(wrapper.get('button').attributes('aria-required')).toBe('true');
  });

  it('marks the trigger invalid and describes it when in error', () => {
    const wrapper = mount(UiSelect, {
      props: { label: '项目', error: '请选择项目', options: OPTIONS },
    });
    const error = wrapper.get('.ui-select__error');
    expect(wrapper.get('button').attributes('aria-invalid')).toBe('true');
    expect(error.attributes('id')).toBeTruthy();
    expect(wrapper.get('button').attributes('aria-describedby')).toBe(error.attributes('id'));
  });
});
