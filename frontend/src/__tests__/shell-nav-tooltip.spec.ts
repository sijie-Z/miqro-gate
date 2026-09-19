/**
 * Collapsed-rail tooltips (#655): with the rail in icon-only mode every nav
 * item answers hover/focus with the styled UiTooltip bubble carrying the
 * label; while the rail is expanded the tooltip is inert (labels visible).
 */
import { beforeEach, describe, expect, it } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent } from 'vue';
import { createMemoryHistory, createRouter } from 'vue-router';
import { createPinia, setActivePinia } from 'pinia';
import NewShell from '@/components/NewShell.vue';
import { initPreferences, setPreference } from '@/preferences';
import { useAuthStore } from '@/stores/auth';

/** jsdom boots with a 1024px viewport (< the narrow threshold). */
function setViewportWidth(width: number) {
  Object.defineProperty(window, 'innerWidth', { value: width, writable: true, configurable: true });
}

const StubView = defineComponent({ name: 'StubView', template: '<div />' });

/** Every regular-nav route the shell renders a <router-link> for (role USER). */
const REGULAR_NAV = ['overview', 'keys', 'usage', 'skills', 'model-approvals', 'profile', 'help'];

async function mountShell() {
  const pinia = createPinia();
  setActivePinia(pinia);
  const auth = useAuthStore();
  auth.user = {
    id: 'u-1',
    username: 'demo',
    displayName: 'Demo',
    role: 'USER',
    status: 'ACTIVE',
    mustChangePassword: false,
    sessionExpiresAt: '2026-12-31T00:00:00Z',
  };

  const router = createRouter({
    history: createMemoryHistory(),
    routes: REGULAR_NAV.map((name) => ({ path: `/app/${name}`, name, component: StubView })),
  });
  await router.push('/app/keys');
  await router.isReady();

  const wrapper = mount(NewShell, { global: { plugins: [pinia, router] } });
  await flushPromises();
  return wrapper;
}

describe('collapsed rail tooltips (#655)', () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    setViewportWidth(1440); // wide: only the pinned collapse can shrink the rail
    initPreferences();
    setPreference('collapsed', false);
  });

  it('shows the styled tooltip with the nav label while collapsed', async () => {
    setPreference('collapsed', true);
    const wrapper = await mountShell();
    expect(wrapper.find('.new-shell__rail--icons').exists()).toBe(true);

    const item = wrapper.find('.new-shell__nav-item');
    const label = item.find('.new-shell__nav-label').text().trim();
    expect(label.length).toBeGreaterThan(0);

    await item.trigger('focus');
    await flushPromises();
    expect(document.querySelector('.ui-tooltip')?.textContent).toContain(label);
  });

  it('keeps the tooltip inert while the rail is expanded', async () => {
    const wrapper = await mountShell();
    expect(wrapper.find('.new-shell__rail--icons').exists()).toBe(false);

    await wrapper.find('.new-shell__nav-item').trigger('focus');
    await flushPromises();
    expect(document.querySelector('.ui-tooltip')).toBeFalsy();
  });
});
