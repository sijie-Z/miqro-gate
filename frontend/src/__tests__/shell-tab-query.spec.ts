/**
 * PH41: the shell tab bar must not destroy URL-backed view state.
 *
 * `NextGrantsView` deliberately mirrors its credential filter into the URL
 * (`/app/grants?credentialId=c1`, made deep-linkable in #657), but the tab bar
 * keys tabs by `route.name` only and navigates with `router.push({ name })`,
 * which drops `route.query`. Clicking a tab therefore silently clears the
 * filter the URL was advertising.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, nextTick } from 'vue';
import { createMemoryHistory, createRouter } from 'vue-router';
import { createPinia, setActivePinia } from 'pinia';
import NewShell from '@/components/NewShell.vue';
import { initPreferences, setPreference } from '@/preferences';
import { useAuthStore } from '@/stores/auth';

const StubView = defineComponent({ name: 'StubView', template: '<div />' });

/** Every route name the shell renders a nav link for must exist in the table. */
const NAV = ['overview', 'keys', 'usage', 'skills', 'model-approvals', 'profile', 'help'] as const;

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
    routes: NAV.map((name) => ({ path: `/app/${name}`, name, component: StubView })),
  });
  await router.push('/app/keys?credentialId=c1');
  await router.isReady();

  const wrapper = mount(NewShell, { global: { plugins: [pinia, router] } });
  await nextTick();
  return { wrapper, router };
}

function tab(wrapper: ReturnType<typeof mount>, label: string) {
  const found = wrapper.findAll('.new-shell__tab').find((t) => t.text().includes(label));
  if (!found) throw new Error(`tab not rendered: ${label}`);
  return found;
}

describe('shell tab bar keeps URL view state (PH41)', () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    initPreferences();
    setPreference('collapsed', false);
    setPreference('showTabs', true);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('keeps the query string when re-activating the current tab', async () => {
    const { wrapper, router } = await mountShell();
    expect(router.currentRoute.value.query.credentialId).toBe('c1');

    await tab(wrapper, '我的密钥').trigger('click');
    await nextTick();
    await flushPromises();

    expect(router.currentRoute.value.query.credentialId).toBe('c1');
  });

  it('restores the query string when switching back to a tab', async () => {
    const { wrapper, router } = await mountShell();

    // Visit a second page so its tab exists (tabs are added on first visit).
    await router.push('/app/usage');
    await nextTick();
    expect(tab(wrapper, '用量')).toBeTruthy();

    await tab(wrapper, '我的密钥').trigger('click');
    await nextTick();
    await flushPromises();

    expect(router.currentRoute.value.name).toBe('keys');
    expect(router.currentRoute.value.query.credentialId).toBe('c1');
  });
});
