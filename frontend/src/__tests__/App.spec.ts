import { describe, expect, it, vi, beforeEach } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import TDesign from 'tdesign-vue-next';
import App from '@/App.vue';
import router from '@/router';
import * as api from '@/api';

describe('App', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.restoreAllMocks();
    vi.spyOn(api, 'me').mockRejectedValue(new Error('401'));
  });

  it('renders the login view when unauthenticated', async () => {
    await router.push('/login');
    await flushPromises();

    const wrapper = mount(App, {
      global: {
        plugins: [router, TDesign],
      },
    });
    await flushPromises();

    expect(wrapper.text()).toContain('MiQroKey');
    expect(wrapper.find('[data-testid="login-submit"]').exists()).toBe(true);
  });

  it('redirects unknown paths to login when unauthenticated', async () => {
    await router.push('/app/keys');
    await flushPromises();

    const wrapper = mount(App, {
      global: {
        plugins: [router, TDesign],
      },
    });
    await flushPromises();

    expect(wrapper.find('[data-testid="login-submit"]').exists()).toBe(true);
  });
});
