/**
 * Console preferences — the single source of truth for user-level UI settings
 * (Vben Admin v2 preference-drawer model). The reactive `preferences` object is
 * shared by the shell and the settings drawer; mutations go through
 * `setPreference`, which persists to localStorage and re-applies the side
 * effects on the document root (`--ui-*` custom properties + `data-*` flags).
 *
 * Storage key: 'miqrolegate.prefs'. Derived brand colors are computed here
 * with plain hex math — no color dependency.
 */
import { reactive } from 'vue';

export type MenuTheme = 'dark' | 'light';
export type ContentCompact = 'fixed' | 'wide';

export interface Preferences {
  primaryColor: string; // hex, default '#0960bd'
  menuTheme: MenuTheme; // default 'dark'
  showBreadcrumb: boolean; // default true
  showTabs: boolean; // default true
  showHeader: boolean; // default true
  showLogo: boolean; // default true
  showTabRefresh: boolean; // default false
  grayMode: boolean; // default false
  colorWeakMode: boolean; // default false
  collapsed: boolean; // default false (rail collapsed to icons)
  contentCompact: ContentCompact; // default 'wide' (fluid — vben v5 fills the viewport; 'fixed' caps at 1200px)
  showPageDesc: boolean; // page-header description line, default true (a click on any page title toggles it too)
  animations: boolean; // default true
  lockMinutes: number; // auto-lock idle timeout in minutes (0 = off), default 0
  sidebarWidth: number; // expanded rail width in px, default 210
}

const STORAGE_KEY = 'miqrolegate.prefs';

const DEFAULT_PREFERENCES: Preferences = {
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
  contentCompact: 'wide',
  showPageDesc: true,
  animations: true,
  lockMinutes: 0,
  sidebarWidth: 210,
};

export const preferences = reactive<Preferences>({ ...DEFAULT_PREFERENCES });

/** Theme-color swatches offered by the settings drawer (Vben v2 presets). */
export const PRIMARY_PRESETS: Array<{ name: string; color: string }> = [
  { name: '蓝', color: '#0960bd' }, // default
  { name: '青', color: '#0e7490' },
  { name: '绿', color: '#2f9e44' },
  { name: '橙', color: '#d48806' },
  { name: '红', color: '#bd1426' },
  { name: '灰', color: '#595959' },
];

/**
 * Persist the current preferences and re-apply every side effect on
 * `document.documentElement`. Idempotent: calling it twice with unchanged
 * state leaves the same custom properties, data flags and storage payload.
 */
export function applyPreferences(): void {
  const root = document.documentElement;
  // Fall back to the default for unparsable values (e.g. hand-edited storage):
  // an invalid hex would otherwise blank every primary token.
  const hex = normalizeHex(preferences.primaryColor) ?? DEFAULT_PREFERENCES.primaryColor;
  const rgb = parseHex(hex);
  if (rgb) {
    root.style.setProperty('--ui-primary', hex);
    root.style.setProperty('--ui-primary-hover', toHex(mixRgb(rgb, WHITE, 0.12)));
    root.style.setProperty('--ui-primary-active', toHex(mixRgb(rgb, BLACK, 0.1)));
    root.style.setProperty('--ui-primary-soft', toHex(mixRgb(WHITE, rgb, 0.08)));
    root.style.setProperty('--ui-primary-text', hex);
    root.style.setProperty('--ui-ring', `rgba(${rgb.r}, ${rgb.g}, ${rgb.b}, 0.11)`);
  }
  root.dataset.menuTheme = preferences.menuTheme;
  root.dataset.compact = preferences.contentCompact;
  root.dataset.pageDesc = preferences.showPageDesc ? 'show' : 'hide';
  root.dataset.gray = preferences.grayMode ? 'on' : 'off';
  root.dataset.colorWeak = preferences.colorWeakMode ? 'on' : 'off';
  root.dataset.anim = preferences.animations ? 'on' : 'off';
  const width = Math.min(320, Math.max(180, Math.round(preferences.sidebarWidth) || 210));
  root.style.setProperty('--ui-sidebar-width', `${width}px`);
  writeStored();
}

/** Update one preference: mutate, persist, apply side effects. */
export function setPreference<K extends keyof Preferences>(key: K, value: Preferences[K]): void {
  preferences[key] = value;
  applyPreferences();
}

/** Load the persisted payload (merge over defaults) and apply it. */
export function initPreferences(): void {
  Object.assign(preferences, DEFAULT_PREFERENCES, readStored());
  applyPreferences();
}

interface Rgb {
  r: number;
  g: number;
  b: number;
}

const WHITE: Rgb = { r: 255, g: 255, b: 255 };
const BLACK: Rgb = { r: 0, g: 0, b: 0 };

type BooleanPreferenceKey = {
  [K in keyof Preferences]: Preferences[K] extends boolean ? K : never;
}[keyof Preferences];

const BOOLEAN_KEYS: readonly BooleanPreferenceKey[] = [
  'showBreadcrumb',
  'showTabs',
  'showHeader',
  'showLogo',
  'showTabRefresh',
  'showPageDesc',
  'grayMode',
  'colorWeakMode',
  'collapsed',
  'animations',
];

/** '#abc' / '#aabbcc' (case-insensitive, leading '#' optional) → full lowercase '#aabbcc', else null. */
function normalizeHex(input: string): string | null {
  const value = /^#?([0-9a-f]{3}|[0-9a-f]{6})$/i.exec(input.trim())?.[1]?.toLowerCase();
  if (!value) return null;
  if (value.length === 3) {
    return `#${value
      .split('')
      .map((channel) => channel + channel)
      .join('')}`;
  }
  return `#${value}`;
}

function parseHex(hex: string): Rgb | null {
  const value = /^#([0-9a-f]{6})$/.exec(hex)?.[1];
  if (!value) return null;
  const int = Number.parseInt(value, 16);
  return { r: (int >> 16) & 0xff, g: (int >> 8) & 0xff, b: int & 0xff };
}

/** Linear sRGB mix: `amount` is the share of `to` (0..1). */
function mixRgb(from: Rgb, to: Rgb, amount: number): Rgb {
  return {
    r: from.r + (to.r - from.r) * amount,
    g: from.g + (to.g - from.g) * amount,
    b: from.b + (to.b - from.b) * amount,
  };
}

function toHex(rgb: Rgb): string {
  const channel = (value: number) =>
    Math.round(Math.min(255, Math.max(0, value)))
      .toString(16)
      .padStart(2, '0');
  return `#${channel(rgb.r)}${channel(rgb.g)}${channel(rgb.b)}`;
}

/**
 * Read + validate the persisted payload. Unknown keys, mistyped values and
 * corrupt JSON are dropped so a hand-edited storage never breaks the console.
 */
function readStored(): Partial<Preferences> {
  let raw: string | null = null;
  try {
    raw = localStorage.getItem(STORAGE_KEY);
  } catch {
    return {};
  }
  if (!raw) return {};

  let parsed: unknown;
  try {
    parsed = JSON.parse(raw) as unknown;
  } catch {
    return {};
  }
  if (typeof parsed !== 'object' || parsed === null) return {};

  const data = parsed as Record<string, unknown>;
  const merged: Partial<Preferences> = {};

  if (typeof data.primaryColor === 'string') {
    const color = normalizeHex(data.primaryColor);
    if (color) merged.primaryColor = color;
  }
  if (data.menuTheme === 'dark' || data.menuTheme === 'light') {
    merged.menuTheme = data.menuTheme;
  }
  if (data.contentCompact === 'fixed' || data.contentCompact === 'wide') {
    merged.contentCompact = data.contentCompact;
  }
  for (const key of BOOLEAN_KEYS) {
    const value = data[key];
    if (typeof value === 'boolean') merged[key] = value;
  }
  if (typeof data.lockMinutes === 'number' && Number.isFinite(data.lockMinutes)) {
    merged.lockMinutes = Math.min(240, Math.max(0, Math.round(data.lockMinutes)));
  }
  if (typeof data.sidebarWidth === 'number' && Number.isFinite(data.sidebarWidth)) {
    merged.sidebarWidth = Math.min(320, Math.max(180, Math.round(data.sidebarWidth)));
  }
  return merged;
}

function writeStored(): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(preferences));
  } catch {
    // Storage may be unavailable (private mode / quota); in-memory state still applies.
  }
}
