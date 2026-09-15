/**
 * Console-shell preference wiring (NewShell + src/preferences).
 *
 * The shell reads the reactive `preferences` object for chrome visibility
 * (header / breadcrumb / tabs / refresh / logo) and merges the persisted
 * `collapsed` flag into the rail's icon-only logic (narrow viewport OR the
 * user-pinned collapse). The drawer opens from the topbar gear button.
 *
 * Mounted with a minimal memory router (no auth guards, stub pages) so the
 * spec exercises shell chrome only — never the lazily-loaded real views.
 */
import { beforeEach, describe, expect, it } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, nextTick } from 'vue';
import { createMemoryHistory, createRouter } from 'vue-router';
import { createPinia, setActivePinia } from 'pinia';
import NewShell from '@/components/NewShell.vue';
import SettingsDrawer from '@/components/SettingsDrawer.vue';
import { initPreferences, preferences, setPreference } from '@/preferences';
import { useAuthStore } from '@/stores/auth';

/** jsdom boots with a 1024px viewport (< the 1080 narrow threshold). */
function setViewportWidth(width: number) {
  Object.defineProperty(window, 'innerWidth', { value: width, writable: true, configurable: true });
}

const StubView = defineComponent({ name: 'StubView', template: '<div />' });

/** Every regular-nav route the shell renders a <router-link> for (role USER). */
const REGULAR_NAV = ['overview', 'keys', 'usage', 'skills', 'model-approvals', 'profile'];

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: REGULAR_NAV.map((name) => ({ path: `/app/${name}`, name, component: StubView })),
  });
}

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

  const router = makeRouter();
  await router.push('/app/keys');
  await router.isReady();

  const wrapper = mount(NewShell, { global: { plugins: [pinia, router] } });
  await flushPromises();
  return wrapper;
}

describe('shell preferences wiring', () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    setViewportWidth(1440); // wide: narrow-viewport collapse must not interfere
    initPreferences();
    setPreference('collapsed', false);
    setPreference('showTabs', true);
    setPreference('showHeader', true);
    setPreference('showBreadcrumb', true);
    setPreference('showTabRefresh', true);
    setPreference('showLogo', true);
  });

  it('collapse button toggles the persisted collapsed preference and the icon rail', async () => {
    const wrapper = await mountShell();
    expect(wrapper.find('.new-shell__rail--icons').exists()).toBe(false);

    const button = wrapper.find('[data-testid="shell-collapse"]');
    expect(button.exists()).toBe(true);

    await button.trigger('click');
    expect(preferences.collapsed).toBe(true);
    await nextTick();
    expect(wrapper.find('.new-shell__rail--icons').exists()).toBe(true);

    await button.trigger('click');
    expect(preferences.collapsed).toBe(false);
    await nextTick();
    expect(wrapper.find('.new-shell__rail--icons').exists()).toBe(false);
  });

  it('hides the tabbar when showTabs is turned off', async () => {
    const wrapper = await mountShell();
    expect(wrapper.find('[data-testid="shell-tabbar"]').exists()).toBe(true);

    setPreference('showTabs', false);
    await nextTick();
    expect(wrapper.find('[data-testid="shell-tabbar"]').exists()).toBe(false);
  });

  it('opens the settings drawer from the topbar gear button', async () => {
    const wrapper = await mountShell();
    expect(wrapper.findComponent(SettingsDrawer).props('open')).toBe(false);
    // The drawer teleports to <body> only while open.
    expect(document.body.querySelector('[data-testid="settings-drawer"]')).toBeNull();

    await wrapper.find('[data-testid="shell-settings-open"]').trigger('click');
    await nextTick();
    expect(wrapper.findComponent(SettingsDrawer).props('open')).toBe(true);
    expect(document.body.querySelector('[data-testid="settings-drawer"]')).not.toBeNull();
  });

  it('keeps collapse + settings reachable in the slim strip when the header is hidden', async () => {
    setPreference('showHeader', false);
    const wrapper = await mountShell();

    expect(wrapper.find('.new-shell__topbar').exists()).toBe(false);
    const strip = wrapper.find('[data-testid="shell-topbar-slim"]');
    expect(strip.exists()).toBe(true);
    expect(strip.find('[data-testid="shell-collapse"]').exists()).toBe(true);
    expect(strip.find('[data-testid="shell-settings-open"]').exists()).toBe(true);
  });
});
