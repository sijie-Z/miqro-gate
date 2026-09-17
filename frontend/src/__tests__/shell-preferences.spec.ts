/**
 * Console-shell preference wiring (NewShell + src/preferences).
 *
 * The shell reads the reactive `preferences` object for chrome visibility
 * (header / breadcrumb / tabs / refresh / logo) and merges the persisted
 * `collapsed` flag into the rail's icon-only logic (narrow viewport OR the
 * user-pinned collapse). The drawer opens from the topbar gear button.
 *
 * The drawer is a second entry point for the same `collapsed` flag (issue
 * #579): flipping the switch inside it must collapse the rail immediately and
 * survive a reload, exactly like the topbar button.
 *
 * Mounted with a minimal memory router (no auth guards, stub pages) so the
 * spec exercises shell chrome only — never the lazily-loaded real views.
 */
/// <reference types="node" />
import { beforeEach, describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
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

  it('collapses the rail from the drawer switch and keeps it across a reload', async () => {
    const wrapper = await mountShell();
    await wrapper.find('[data-testid="shell-settings-open"]').trigger('click');
    await nextTick();

    const drawerSwitch = document.body.querySelector<HTMLInputElement>(
      '[data-testid="settings-toggle-collapsed"]',
    );
    if (!(drawerSwitch instanceof HTMLInputElement)) {
      throw new Error('settings drawer has no collapsed switch');
    }
    expect(drawerSwitch.checked).toBe(false);
    expect(wrapper.find('.new-shell__rail--icons').exists()).toBe(false);

    drawerSwitch.checked = true;
    drawerSwitch.dispatchEvent(new Event('change'));
    await nextTick();

    // Effective immediately in the shell …
    expect(preferences.collapsed).toBe(true);
    expect(wrapper.find('.new-shell__rail--icons').exists()).toBe(true);
    // … and written through to storage.
    const stored = JSON.parse(localStorage.getItem('miqrolegate.prefs') ?? '{}') as {
      collapsed?: boolean;
    };
    expect(stored.collapsed).toBe(true);

    // Simulate a reload: drop the in-memory flag, re-read the persisted payload.
    preferences.collapsed = false;
    initPreferences();
    await nextTick();

    expect(preferences.collapsed).toBe(true);
    expect(wrapper.find('.new-shell__rail--icons').exists()).toBe(true);
    expect(
      document.body.querySelector<HTMLInputElement>('[data-testid="settings-toggle-collapsed"]')
        ?.checked,
    ).toBe(true);

    wrapper.unmount();
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

/**
 * The two layout values issue #579 pins down: the fixed content area is
 * 1200px (Vben's contentWidth default) and the drawer header is padded
 * 16px/24px. jsdom applies no stylesheet cascade, so the shipped CSS source is
 * read instead (same technique as aesthetic.spec.ts) — every `var()` is
 * resolved back to its px value so the assertion cannot pass on a renamed
 * token.
 */
describe('#579 layout contract', () => {
  const tokens = readFileSync('src/styles/design-tokens.css', 'utf-8');
  const base = readFileSync('src/styles/design-base.css', 'utf-8');
  const drawer = readFileSync('src/ui/Drawer.vue', 'utf-8');

  /** Declarations of the first `selector {` block in a stylesheet. */
  function ruleBody(css: string, selector: string): string {
    const start = css.indexOf(`${selector} {`);
    if (start < 0) throw new Error(`no rule for ${selector}`);
    const open = css.indexOf('{', start);
    return css.slice(open + 1, css.indexOf('}', open));
  }

  function declaration(body: string, property: string): string {
    const match = new RegExp(`(?:^|[;\\s])${property}\\s*:\\s*([^;]+);`).exec(body);
    if (!match?.[1]) throw new Error(`no declaration for ${property}`);
    return match[1].trim();
  }

  const root = ruleBody(tokens, ':root');

  it('caps the fixed content area at the 1200px default', () => {
    expect(declaration(root, '--ui-content-max')).toBe('1200px');
    // … and that token is what .ui-page actually enforces.
    expect(declaration(ruleBody(base, '.ui-page'), 'max-width')).toBe('var(--ui-content-max)');
  });

  it("still lets 'wide' mode fill the viewport instead of the cap", () => {
    expect(declaration(ruleBody(base, "[data-compact='wide']"), '--ui-content-max')).toBe('100%');
  });

  it('pads the drawer header 16px / 24px', () => {
    expect(declaration(root, '--ui-space-4')).toBe('16px');
    expect(declaration(root, '--ui-space-6')).toBe('24px');
    expect(declaration(ruleBody(drawer, '.ui-drawer__head'), 'padding')).toBe(
      'var(--ui-space-4) var(--ui-space-6)',
    );
  });
});
