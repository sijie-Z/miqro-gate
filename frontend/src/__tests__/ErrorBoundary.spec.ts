import { afterEach, describe, expect, it, vi } from 'vitest';
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h, nextTick } from 'vue';
import { createMemoryHistory, createRouter } from 'vue-router';
import ErrorBoundary from '@/components/ErrorBoundary.vue';

enableAutoUnmount(afterEach);

/** Child that throws during render while `state.boom` is set. */
const state = { boom: true };
const BoomChild = defineComponent({
  name: 'BoomChild',
  setup() {
    return () => {
      if (state.boom) throw new Error('渲染炸了');
      return '内容正常';
    };
  },
});

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/app/overview', name: 'overview', component: { template: '<div />' } },
      { path: '/app/keys', name: 'keys', component: { template: '<div />' } },
    ],
  });
}

async function mountBoundary() {
  const router = makeRouter();
  await router.push('/app/keys');
  await router.isReady();
  const wrapper = mount(ErrorBoundary, {
    global: { plugins: [router] },
    slots: { default: () => h(BoomChild) },
  });
  await flushPromises();
  return { wrapper, router };
}

describe('ErrorBoundary (#833)', () => {
  it('replaces a crashing subtree with a recoverable card', async () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
    const { wrapper } = await mountBoundary();

    const card = wrapper.find('[data-testid="error-boundary"]');
    expect(card.exists()).toBe(true);
    expect(card.text()).toContain('页面出错了');
    expect(card.text()).toContain('渲染炸了');
    expect(wrapper.find('[data-testid="error-retry"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="error-reload"]').exists()).toBe(true);
    spy.mockRestore();
  });

  it('retry re-renders the subtree once the fault clears', async () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
    const { wrapper } = await mountBoundary();
    expect(wrapper.find('[data-testid="error-boundary"]').exists()).toBe(true);

    state.boom = false;
    await wrapper.find('[data-testid="error-retry"]').trigger('click');
    await nextTick();
    expect(wrapper.find('[data-testid="error-boundary"]').exists()).toBe(false);
    expect(wrapper.text()).toContain('内容正常');
    state.boom = true;
    spy.mockRestore();
  });

  it('navigating away clears the failure state', async () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
    const { wrapper, router } = await mountBoundary();
    expect(wrapper.find('[data-testid="error-boundary"]').exists()).toBe(true);

    state.boom = false;
    await router.push('/app/overview');
    await flushPromises();
    expect(wrapper.find('[data-testid="error-boundary"]').exists()).toBe(false);
    state.boom = true;
    spy.mockRestore();
  });
});
