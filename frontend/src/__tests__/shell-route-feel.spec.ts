/**
 * Instant route switching (#668): the content scroller resets to the top on
 * every page change (query-only changes keep position), and nav items
 * prefetch their route chunk on hover/focus plus one idle pass after mount —
 * each route's loader is invoked at most once per mount by the prefetch path.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { enableAutoUnmount, mount } from '@vue/test-utils';
import { defineComponent, nextTick } from 'vue';
import { createMemoryHistory, createRouter } from 'vue-router';
import { createPinia, setActivePinia } from 'pinia';
import NewShell from '@/components/NewShell.vue';
import { initPreferences, setPreference } from '@/preferences';
import { useAuthStore } from '@/stores/auth';

enableAutoUnmount(afterEach);

const StubView = defineComponent({ name: 'StubView', template: '<div />' });

/** Regular-nav route names, in the order the shell renders them. */
const NAV = ['overview', 'keys', 'usage', 'skills', 'model-approvals', 'profile'] as const;
type NavName = (typeof NAV)[number];

function makeLoaders(): Record<NavName, ReturnType<typeof vi.fn>> {
  return Object.fromEntries(
    NAV.map((name) => [name, vi.fn(() => Promise.resolve(StubView))]),
  ) as Record<NavName, ReturnType<typeof vi.fn>>;
}

async function mountShell(loaders: Record<NavName, ReturnType<typeof vi.fn>>) {
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
    routes: NAV.map((name) => ({ path: `/app/${name}`, name, component: loaders[name] })),
  });
  await router.push('/app/keys');
  await router.isReady();

  const wrapper = mount(NewShell, { global: { plugins: [pinia, router] } });
  await nextTick();
  await vi.advanceTimersByTimeAsync(0);
  return { wrapper, router };
}

function navItem(wrapper: ReturnType<typeof mount>, name: NavName) {
  const item = wrapper.findAll('.new-shell__nav-item')[NAV.indexOf(name)];
  if (!item) throw new Error(`nav item not rendered: ${name}`);
  return item;
}

describe('instant route switching (#668)', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    localStorage.clear();
    sessionStorage.clear();
    initPreferences();
    setPreference('collapsed', false);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('prefetches the route chunk on nav hover, once per route', async () => {
    const loaders = makeLoaders();
    const { wrapper } = await mountShell(loaders);

    expect(loaders.usage).not.toHaveBeenCalled();
    await navItem(wrapper, 'usage').trigger('mouseenter');
    expect(loaders.usage).toHaveBeenCalledTimes(1);
    await navItem(wrapper, 'usage').trigger('mouseenter');
    expect(loaders.usage).toHaveBeenCalledTimes(1);
  });

  it('prefetches on keyboard focus as well', async () => {
    const loaders = makeLoaders();
    const { wrapper } = await mountShell(loaders);

    await navItem(wrapper, 'skills').trigger('focus');
    expect(loaders.skills).toHaveBeenCalledTimes(1);
  });

  it('warms every visible menu chunk once after the idle delay', async () => {
    const loaders = makeLoaders();
    await mountShell(loaders);

    expect(loaders.profile).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1500 + 120 * NAV.length + 200);
    expect(loaders.profile).toHaveBeenCalledTimes(1);
    expect(loaders.skills).toHaveBeenCalledTimes(1);
  });

  it('resets the content scroller on page change, but not on query-only changes', async () => {
    const loaders = makeLoaders();
    const { wrapper, router } = await mountShell(loaders);

    const content = wrapper.find('.new-shell__content').element as HTMLElement;
    const writes: number[] = [];
    // jsdom has no layout; observe the writes instead of a real scrollTop.
    Object.defineProperty(content, 'scrollTop', {
      configurable: true,
      get: () => 100,
      set: (value: number) => {
        writes.push(value);
      },
    });

    await router.push('/app/usage');
    await nextTick();
    expect(writes).toContain(0);

    writes.length = 0;
    await router.push('/app/usage?tab=token');
    await nextTick();
    expect(writes).toEqual([]);
  });
});
