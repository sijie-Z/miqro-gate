/**
 * PH41: the shell's own navigation must not destroy URL-backed view state.
 *
 * The credentials list deep-links into the grants page (`#657`,
 * `NextCredentialsView.vue:546` -> `/app/grants?credentialId=c1`) and
 * `NextGrantsView` derives its filter from that query param alone — it imports
 * `useRoute` and nothing writes the URL back (`NextGrantsView.vue:174`). So the
 * URL is the *only* source of that filter state. The shell navigates by
 * `route.name` alone in two places — the tab bar and the sidebar nav — so both
 * drop `route.query` and silently clear the filter the URL was advertising.
 * Clicking the entry you are *already on* is the sharpest case: no navigation is
 * intended, yet the state is destroyed.
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
const NAV = [
  'overview',
  'keys',
  'plaza',
  'playground',
  'usage',
  'skills',
  'model-approvals',
  'profile',
  'help',
] as const;

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

/** Labels of the tabs currently in the bar, in render order. */
function tabLabels(wrapper: ReturnType<typeof mount>) {
  return wrapper.findAll('.new-shell__tab-label').map((t) => t.text().trim());
}

// Match the label node itself rather than `element.text()`: `用量` is a prefix of
// `用量报表` (NewShell.vue:87 vs :114), so a substring search can pick the wrong
// element and leave a test green for a reason it did not intend.
function tab(wrapper: ReturnType<typeof mount>, label: string) {
  const found = wrapper
    .findAll('.new-shell__tab')
    .find((t) => t.find('.new-shell__tab-label').text().trim() === label);
  if (!found) throw new Error(`tab not rendered: ${label}`);
  return found;
}

function navItem(wrapper: ReturnType<typeof mount>, label: string) {
  const found = wrapper
    .findAll('.new-shell__nav-item')
    .find((t) => t.find('.new-shell__nav-label').text().trim() === label);
  if (!found) throw new Error(`nav item not rendered: ${label}`);
  return found;
}

// Applies to every block below: the shell reads both storages on setup.
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

describe('shell tab bar keeps URL view state (PH41)', () => {
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
    // The bar has to actually grow the second tab, in visit order.
    expect(tabLabels(wrapper)).toEqual(['我的密钥', '用量']);

    await tab(wrapper, '我的密钥').trigger('click');
    await nextTick();
    await flushPromises();

    expect(router.currentRoute.value.name).toBe('keys');
    expect(router.currentRoute.value.query.credentialId).toBe('c1');
  });
});

describe('shell sidebar keeps URL view state (PH41)', () => {
  it('does not clear the query when clicking the sidebar item you are already on', async () => {
    const { wrapper, router } = await mountShell();
    expect(router.currentRoute.value.query.credentialId).toBe('c1');

    await navItem(wrapper, '我的密钥').trigger('click');
    await nextTick();
    await flushPromises();

    expect(router.currentRoute.value.query.credentialId).toBe('c1');
  });

  /**
   * Pins the rule the fix chose, so a future refactor cannot flip it silently:
   * the sidebar navigates to a *section*, never to that section's remembered
   * filter. The URL stays the single source of truth — including the fact that
   * a bare arrival (this sidebar click, or `查看全部` in NextGrantsView) means
   * "no filter", which is why the tab cannot keep advertising the old one.
   */
  it('goes to the bare section from the sidebar, and remembers that', async () => {
    const { wrapper, router } = await mountShell();

    await navItem(wrapper, '用量').trigger('click');
    await nextTick();
    await flushPromises();
    expect(router.currentRoute.value.fullPath).toBe('/app/usage');

    await navItem(wrapper, '我的密钥').trigger('click');
    await nextTick();
    await flushPromises();
    expect(router.currentRoute.value.fullPath).toBe('/app/keys');

    // ...and the tab now advertises that bare URL, so re-activating it is a
    // no-op instead of a jump back to a filter the URL no longer mentions.
    await tab(wrapper, '我的密钥').trigger('click');
    await nextTick();
    await flushPromises();
    expect(router.currentRoute.value.fullPath).toBe('/app/keys');
  });
});

describe('shell tab state across reload and logout (PH41)', () => {
  it('re-registers the current route from the URL when the tab came from sessionStorage', async () => {
    // A tab written by an older build has no remembered path. Mounting while
    // the URL carries the filter must repair it before any click.
    sessionStorage.setItem(
      'miqrogate.shell-tabs',
      JSON.stringify([{ name: 'keys', label: '我的密钥' }]),
    );

    const { wrapper, router } = await mountShell();
    expect(router.currentRoute.value.query.credentialId).toBe('c1');

    await tab(wrapper, '我的密钥').trigger('click');
    await nextTick();
    await flushPromises();

    expect(router.currentRoute.value.query.credentialId).toBe('c1');
  });

  it('never writes the query string into sessionStorage', async () => {
    // sessionStorage outlives a logout in the same browser tab, so a persisted
    // query would be inherited by whoever logs in next.
    const { router } = await mountShell();
    expect(router.currentRoute.value.query.credentialId).toBe('c1');
    await nextTick();

    const raw = sessionStorage.getItem('miqrogate.shell-tabs') ?? '';
    expect(raw).toContain('keys');
    expect(raw).not.toContain('credentialId');

    // ...and again once a second, differently-shaped tab exists: the payload
    // must stay query-free for tabs the user is *not* currently on too.
    await router.push('/app/usage?tab=token');
    await nextTick();
    await flushPromises();

    const after = sessionStorage.getItem('miqrogate.shell-tabs') ?? '';
    expect(after).toContain('usage');
    expect(after).not.toContain('credentialId');
    expect(after).not.toContain('tab=token');
  });

  it('drops a restored tab whose route no longer resolves', async () => {
    // A payload written by an older build — or edited by hand — can name a route
    // that no longer exists. Such a tab renders like any other but is dead on
    // click: `router.push({ name })` throws *synchronously* inside the handler
    // (`MATCHER_NOT_FOUND`), so no navigation happens and only the console says
    // so. Anything the router cannot resolve must not reach the bar.
    sessionStorage.setItem(
      'miqrogate.shell-tabs',
      JSON.stringify([
        { name: 'ghost', label: '幽灵页' },
        { name: 'keys', label: '我的密钥' },
      ]),
    );

    const { wrapper } = await mountShell();

    expect(tabLabels(wrapper)).toEqual(['我的密钥']);
  });
});
