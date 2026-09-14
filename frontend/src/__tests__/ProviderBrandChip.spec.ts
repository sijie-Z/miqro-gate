import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import ProviderBrandChip from '@/components/ProviderBrandChip.vue';

describe('ProviderBrandChip', () => {
  it('renders the official mark for providers with a public glyph', () => {
    const wrapper = mount(ProviderBrandChip, { props: { slug: 'deepseek', name: 'DeepSeek' } });
    expect(wrapper.find('.mk-brand-chip__glyph svg').exists()).toBe(true);
    expect(wrapper.classes()).toContain('mk-chip-deepseek');
  });

  it('renders the brand monogram for providers without a public glyph', () => {
    const wrapper = mount(ProviderBrandChip, { props: { slug: 'tencent' } });
    expect(wrapper.text()).toBe('腾');
    expect(wrapper.classes()).toContain('mk-chip-tencent');
  });

  it('falls back to the initial and the neutral gradient for unknown providers', () => {
    const wrapper = mount(ProviderBrandChip, { props: { slug: 'acme', name: 'Acme AI' } });
    expect(wrapper.text()).toBe('A');
    expect(wrapper.classes()).toContain('mk-brand-chip--unknown');
  });

  it('applies the small size modifier and still renders the glyph', () => {
    const wrapper = mount(ProviderBrandChip, { props: { slug: 'moonshot', size: 'sm' } });
    expect(wrapper.classes()).toContain('mk-brand-chip--sm');
    expect(wrapper.find('.mk-brand-chip__glyph svg').exists()).toBe(true);
  });
});
