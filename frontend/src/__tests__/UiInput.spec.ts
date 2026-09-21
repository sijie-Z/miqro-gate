import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import { UiInput } from '@/ui';

/**
 * Labelling contract of the v2 text field: the visible label must supply the
 * control's accessible name, `required` must be exposed to assistive tech, and
 * error/hint text must be tied to the control it describes. Assertions go
 * through the platform APIs (`labels`, `aria-*`) rather than raw attributes so
 * they hold for any association mechanism (wrapping label or `for`/`id`).
 */
function control(wrapper: ReturnType<typeof mount>): HTMLInputElement {
  return wrapper.get('input').element as HTMLInputElement;
}

describe('UiInput labelling', () => {
  it('associates the visible label with the input', () => {
    const wrapper = mount(UiInput, { props: { label: '回调地址', modelValue: '' } });
    const labels = [...(control(wrapper).labels ?? [])];
    expect(labels.map((label) => label.textContent?.trim())).toEqual(['回调地址']);
  });

  it('omits the association when no label is rendered', () => {
    const wrapper = mount(UiInput, { props: { modelValue: '' } });
    expect(control(wrapper).labels).toHaveLength(0);
  });

  it('exposes required to assistive tech, not just as a visual asterisk', () => {
    const wrapper = mount(UiInput, { props: { label: '名称', required: true } });
    expect(wrapper.get('input').attributes('aria-required')).toBe('true');
  });

  it('leaves aria-required off when the field is optional', () => {
    const wrapper = mount(UiInput, { props: { label: '备注' } });
    expect(wrapper.get('input').attributes('aria-required')).toBeUndefined();
  });

  it('ties the error text to the input via aria-describedby', () => {
    const wrapper = mount(UiInput, {
      props: { label: '名称', error: '名称已被占用', modelValue: '' },
    });
    const error = wrapper.get('[data-testid="field-error"]');
    expect(error.attributes('id')).toBeTruthy();
    expect(wrapper.get('input').attributes('aria-describedby')).toBe(error.attributes('id'));
  });

  it('ties the hint to the input when there is no error', () => {
    const wrapper = mount(UiInput, {
      props: { label: '名称', hint: '最长 200 个字符。', modelValue: '' },
    });
    const hint = wrapper.get('.ui-field__hint');
    expect(hint.attributes('id')).toBeTruthy();
    expect(wrapper.get('input').attributes('aria-describedby')).toBe(hint.attributes('id'));
  });
});
