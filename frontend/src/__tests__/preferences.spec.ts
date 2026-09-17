import { beforeEach, describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import SettingsDrawer from '@/components/SettingsDrawer.vue';
import {
  PRIMARY_PRESETS,
  applyPreferences,
  initPreferences,
  preferences,
  setPreference,
} from '@/preferences';

const STORAGE_KEY = 'miqrolegate.prefs';

const DEFAULTS = {
  primaryColor: '#0960bd',
  menuTheme: 'dark',
  showBreadcrumb: true,
  showTabs: true,
  showHeader: true,
  showLogo: true,
  showTabRefresh: false,
  grayMode: false,
  colorWeakMode: false,
  collapsed: false,
  contentCompact: 'fixed',
  animations: true,
  lockMinutes: 0,
  sidebarWidth: 210,
};

describe('preferences defaults', () => {
  beforeEach(() => {
    localStorage.clear();
    // Re-init from empty storage: resets the shared reactive object to the
    // defaults and re-applies the document side effects.
    initPreferences();
  });

  it('starts from the Vben defaults', () => {
    expect({ ...preferences }).toEqual(DEFAULTS);
  });

  it('ships the six primary presets with the default first', () => {
    expect(PRIMARY_PRESETS.map((preset) => preset.name)).toEqual([
      '蓝',
      '青',
      '绿',
      '橙',
      '红',
      '灰',
    ]);
    expect(PRIMARY_PRESETS.map((preset) => preset.color)).toEqual([
      '#0960bd',
      '#0e7490',
      '#2f9e44',
      '#d48806',
      '#bd1426',
      '#595959',
    ]);
    expect(preferences.primaryColor).toBe(PRIMARY_PRESETS[0]?.color);
  });
});

describe('applyPreferences', () => {
  beforeEach(() => {
    localStorage.clear();
    initPreferences();
  });

  it('derives the primary variables onto the document root', () => {
    const style = document.documentElement.style;
    expect(style.getPropertyValue('--ui-primary')).toBe('#0960bd');
    // hover: lighten 12% toward white; active: darken 10% toward black.
    expect(style.getPropertyValue('--ui-primary-hover')).toBe('#2773c5');
    expect(style.getPropertyValue('--ui-primary-active')).toBe('#0856aa');
    // soft: 8% of the color mixed onto white.
    expect(style.getPropertyValue('--ui-primary-soft')).toBe('#ebf2fa');
    expect(style.getPropertyValue('--ui-primary-text')).toBe('#0960bd');
    expect(style.getPropertyValue('--ui-ring')).toBe('rgba(9, 96, 189, 0.11)');
  });

  it('mirrors mode flags as data attributes', () => {
    const root = document.documentElement;
    expect(root.dataset.menuTheme).toBe('dark');
    expect(root.dataset.compact).toBe('fixed');
    expect(root.dataset.gray).toBe('off');
    expect(root.dataset.colorWeak).toBe('off');
    expect(root.dataset.anim).toBe('on');

    setPreference('menuTheme', 'light');
    setPreference('contentCompact', 'wide');
    setPreference('grayMode', true);
    setPreference('colorWeakMode', true);
    setPreference('animations', false);

    expect(root.dataset.menuTheme).toBe('light');
    expect(root.dataset.compact).toBe('wide');
    expect(root.dataset.gray).toBe('on');
    expect(root.dataset.colorWeak).toBe('on');
    expect(root.dataset.anim).toBe('off');
  });

  it('is idempotent when re-applied', () => {
    setPreference('primaryColor', '#595959');
    const styleBefore = document.documentElement.getAttribute('style');
    const storedBefore = localStorage.getItem(STORAGE_KEY);
    applyPreferences();
    expect(document.documentElement.getAttribute('style')).toBe(styleBefore);
    expect(localStorage.getItem(STORAGE_KEY)).toBe(storedBefore);
  });

  it('normalizes shorthand and uppercase hex on apply', () => {
    const style = document.documentElement.style;
    setPreference('primaryColor', '#0E7490');
    expect(style.getPropertyValue('--ui-primary')).toBe('#0e7490');
    expect(style.getPropertyValue('--ui-ring')).toBe('rgba(14, 116, 144, 0.11)');

    setPreference('primaryColor', '#abc');
    expect(style.getPropertyValue('--ui-primary')).toBe('#aabbcc');
  });
});

describe('setPreference', () => {
  beforeEach(() => {
    localStorage.clear();
    initPreferences();
  });

  it('mutates the reactive state and persists the payload', () => {
    setPreference('showTabs', false);
    setPreference('collapsed', true);
    expect(preferences.showTabs).toBe(false);
    expect(preferences.collapsed).toBe(true);

    const stored = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? 'null') as Record<
      string,
      unknown
    >;
    expect(stored.showTabs).toBe(false);
    expect(stored.collapsed).toBe(true);
    expect(stored.showBreadcrumb).toBe(true);
  });

  it('persists the complete preference object on every write', () => {
    setPreference('grayMode', true);
    expect(JSON.parse(localStorage.getItem(STORAGE_KEY) ?? 'null')).toEqual({ ...preferences });
  });
});

describe('initPreferences', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('merges the stored payload over the defaults', () => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ menuTheme: 'light', showTabs: false }));
    initPreferences();
    expect(preferences.menuTheme).toBe('light');
    expect(preferences.showTabs).toBe(false);
    expect(preferences.showBreadcrumb).toBe(true);
    expect(document.documentElement.dataset.menuTheme).toBe('light');
  });

  it('falls back to defaults when the stored payload is corrupt', () => {
    localStorage.setItem(STORAGE_KEY, '{not-json');
    initPreferences();
    expect({ ...preferences }).toEqual(DEFAULTS);
  });

  it('clamps the numeric preferences (lock minutes / sidebar width)', () => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ lockMinutes: 999, sidebarWidth: 42 }));
    initPreferences();
    expect(preferences.lockMinutes).toBe(240);
    expect(preferences.sidebarWidth).toBe(180);
    expect(document.documentElement.style.getPropertyValue('--ui-sidebar-width')).toBe('180px');
  });

  it('drops stored values with the wrong shape', () => {
    localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({
        primaryColor: 'not-a-color',
        menuTheme: 'violet',
        contentCompact: 'huge',
        showTabs: 'yes',
        unknownKey: true,
      }),
    );
    initPreferences();
    expect({ ...preferences }).toEqual(DEFAULTS);
  });
});

describe('primary preset switching', () => {
  beforeEach(() => {
    localStorage.clear();
    initPreferences();
  });

  it('switches the primary color and its derived variables', () => {
    const green = PRIMARY_PRESETS.find((preset) => preset.name === '绿');
    if (!green) throw new Error('missing 绿 preset');
    setPreference('primaryColor', green.color);

    const style = document.documentElement.style;
    expect(style.getPropertyValue('--ui-primary')).toBe('#2f9e44');
    expect(style.getPropertyValue('--ui-primary-hover')).toBe('#48aa5a');
    expect(style.getPropertyValue('--ui-primary-text')).toBe('#2f9e44');
    expect(style.getPropertyValue('--ui-ring')).toBe('rgba(47, 158, 68, 0.11)');
  });

  it('restores a persisted preset on a fresh init', () => {
    const orange = PRIMARY_PRESETS.find((preset) => preset.name === '橙');
    if (!orange) throw new Error('missing 橙 preset');
    setPreference('primaryColor', orange.color);

    // Wipe the applied state to prove init re-reads storage, not memory.
    document.documentElement.style.setProperty('--ui-primary', '#000000');
    initPreferences();

    expect(preferences.primaryColor).toBe('#d48806');
    expect(document.documentElement.style.getPropertyValue('--ui-primary')).toBe('#d48806');
  });
});

describe('SettingsDrawer wiring', () => {
  it('routes drawer controls through setPreference', () => {
    localStorage.clear();
    initPreferences();

    const findByTestId = (testId: string): HTMLElement => {
      const element = document.body.querySelector<HTMLElement>(`[data-testid="${testId}"]`);
      if (!element) throw new Error(`missing element with data-testid="${testId}"`);
      return element;
    };

    const wrapper = mount(SettingsDrawer, { props: { open: true } });
    expect(findByTestId('settings-drawer')).toBeTruthy();

    const showTabs = findByTestId('settings-toggle-showTabs') as HTMLInputElement;
    expect(showTabs.checked).toBe(true);
    showTabs.checked = false;
    showTabs.dispatchEvent(new Event('change'));
    expect(preferences.showTabs).toBe(false);

    findByTestId('settings-primary-青').click();
    expect(preferences.primaryColor).toBe('#0e7490');

    findByTestId('settings-menu-theme-light').click();
    expect(preferences.menuTheme).toBe('light');

    findByTestId('settings-compact-wide').click();
    expect(preferences.contentCompact).toBe('wide');

    expect(document.documentElement.dataset.menuTheme).toBe('light');
    expect(document.documentElement.dataset.compact).toBe('wide');

    // #579: the collapse switch lives in the drawer too (the shell wires the
    // same flag into the rail's icon-only mode).
    const collapsed = findByTestId('settings-toggle-collapsed') as HTMLInputElement;
    expect(collapsed.checked).toBe(false);
    collapsed.checked = true;
    collapsed.dispatchEvent(new Event('change'));
    expect(preferences.collapsed).toBe(true);

    wrapper.unmount();
  });
});
