import { describe, expect, it } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia } from 'pinia';
import NextSettingsView from '@/views/next/NextSettingsView.vue';

describe('NextSettingsView', () => {
  it('renders the deployment facts table', async () => {
    const wrapper = mount(NextSettingsView, { global: { plugins: [createPinia()] } });
    await flushPromises();

    expect(wrapper.find('[data-testid="page-title"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="deploy-info"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('MiQroGate');
    expect(wrapper.text()).toContain('Docker Compose（单节点私有化）');
    expect(wrapper.text()).toContain('8080（管理 API）');
    expect(wrapper.text()).toContain('PostgreSQL 17');
  });
});
