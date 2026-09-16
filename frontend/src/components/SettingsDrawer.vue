<script setup lang="ts">
/**
 * SettingsDrawer — console preferences panel (界面设置), modelled on the Vben
 * Admin v2 preference drawer: a 330px right slide-over of hairline sections.
 * Sections: 主题色 swatches, 菜单主题 / 内容区域宽度 segmented controls,
 * 界面显示 switch rows and 过渡动画. Every control writes through
 * `setPreference`, which persists and re-applies the document side effects.
 */
import type { ContentCompact, MenuTheme, Preferences } from '@/preferences';
import { PRIMARY_PRESETS, preferences, setPreference } from '@/preferences';
import { UiDrawer, UiSwitch } from '@/ui';

defineProps<{ open: boolean }>();

const emit = defineEmits<{
  'update:open': [value: boolean];
  close: [];
}>();

type DisplayToggleKey = Extract<
  keyof Preferences,
  | 'showBreadcrumb'
  | 'showTabs'
  | 'showHeader'
  | 'showLogo'
  | 'showTabRefresh'
  | 'grayMode'
  | 'colorWeakMode'
>;

const DISPLAY_TOGGLES: Array<{ key: DisplayToggleKey; label: string }> = [
  { key: 'showBreadcrumb', label: '面包屑' },
  { key: 'showTabs', label: '标签页' },
  { key: 'showHeader', label: '顶栏' },
  { key: 'showLogo', label: 'Logo' },
  { key: 'showTabRefresh', label: '标签页刷新按钮' },
  { key: 'grayMode', label: '灰色模式' },
  { key: 'colorWeakMode', label: '色弱模式' },
];

const MENU_THEMES: Array<{ value: MenuTheme; label: string }> = [
  { value: 'dark', label: '深色' },
  { value: 'light', label: '浅色' },
];

const COMPACT_MODES: Array<{ value: ContentCompact; label: string }> = [
  { value: 'wide', label: '流式' },
  { value: 'fixed', label: '固定' },
];

const LOCK_OPTIONS: Array<{ value: number; label: string }> = [
  { value: 0, label: '关闭' },
  { value: 5, label: '5 分钟' },
  { value: 15, label: '15 分钟' },
  { value: 30, label: '30 分钟' },
];

const SIDEBAR_WIDTHS: Array<{ value: number; label: string }> = [
  { value: 210, label: '紧凑 210' },
  { value: 240, label: '标准 240' },
  { value: 280, label: '宽敞 280' },
];

function isActiveColor(color: string): boolean {
  return preferences.primaryColor.toLowerCase() === color;
}
</script>

<template>
  <UiDrawer
    :open="open"
    title="界面设置"
    width="330px"
    data-testid="settings-drawer"
    @update:open="emit('update:open', $event)"
    @close="emit('close')"
  >
    <section class="settings-drawer__section">
      <h3 class="settings-drawer__title">主题色</h3>
      <div class="settings-drawer__chips">
        <button
          v-for="preset in PRIMARY_PRESETS"
          :key="preset.color"
          type="button"
          class="settings-drawer__chip"
          :class="{ 'is-active': isActiveColor(preset.color) }"
          :style="{ background: preset.color }"
          :title="preset.name"
          :aria-label="preset.name"
          :aria-pressed="isActiveColor(preset.color)"
          :data-testid="`settings-primary-${preset.name}`"
          @click="setPreference('primaryColor', preset.color)"
        />
      </div>
    </section>

    <section class="settings-drawer__section">
      <h3 class="settings-drawer__title">菜单主题</h3>
      <div class="settings-drawer__segmented" role="group" aria-label="菜单主题">
        <button
          v-for="theme in MENU_THEMES"
          :key="theme.value"
          type="button"
          class="settings-drawer__segment"
          :class="{ 'is-active': preferences.menuTheme === theme.value }"
          :aria-pressed="preferences.menuTheme === theme.value"
          :data-testid="`settings-menu-theme-${theme.value}`"
          @click="setPreference('menuTheme', theme.value)"
        >
          {{ theme.label }}
        </button>
      </div>
    </section>

    <section class="settings-drawer__section">
      <h3 class="settings-drawer__title">界面显示</h3>
      <div v-for="toggle in DISPLAY_TOGGLES" :key="toggle.key" class="settings-drawer__row">
        <span class="settings-drawer__row-label">{{ toggle.label }}</span>
        <UiSwitch
          :model-value="preferences[toggle.key]"
          :data-testid="`settings-toggle-${toggle.key}`"
          @update:model-value="setPreference(toggle.key, $event)"
        />
      </div>
    </section>

    <section class="settings-drawer__section">
      <h3 class="settings-drawer__title">内容区域宽度</h3>
      <div class="settings-drawer__segmented" role="group" aria-label="内容区域宽度">
        <button
          v-for="mode in COMPACT_MODES"
          :key="mode.value"
          type="button"
          class="settings-drawer__segment"
          :class="{ 'is-active': preferences.contentCompact === mode.value }"
          :aria-pressed="preferences.contentCompact === mode.value"
          :data-testid="`settings-compact-${mode.value}`"
          @click="setPreference('contentCompact', mode.value)"
        >
          {{ mode.label }}
        </button>
      </div>
    </section>

    <section class="settings-drawer__section">
      <h3 class="settings-drawer__title">自动锁屏</h3>
      <div class="settings-drawer__segmented" role="group" aria-label="自动锁屏">
        <button
          v-for="option in LOCK_OPTIONS"
          :key="option.value"
          type="button"
          class="settings-drawer__segment"
          :class="{ 'is-active': preferences.lockMinutes === option.value }"
          :aria-pressed="preferences.lockMinutes === option.value"
          :data-testid="`settings-lock-${option.value}`"
          @click="setPreference('lockMinutes', option.value)"
        >
          {{ option.label }}
        </button>
      </div>
    </section>

    <section class="settings-drawer__section">
      <h3 class="settings-drawer__title">菜单展开宽度</h3>
      <div class="settings-drawer__segmented" role="group" aria-label="菜单展开宽度">
        <button
          v-for="option in SIDEBAR_WIDTHS"
          :key="option.value"
          type="button"
          class="settings-drawer__segment"
          :class="{ 'is-active': preferences.sidebarWidth === option.value }"
          :aria-pressed="preferences.sidebarWidth === option.value"
          :data-testid="`settings-width-${option.value}`"
          @click="setPreference('sidebarWidth', option.value)"
        >
          {{ option.label }}
        </button>
      </div>
    </section>

    <section class="settings-drawer__section">
      <h3 class="settings-drawer__title">动画</h3>
      <div class="settings-drawer__row">
        <span class="settings-drawer__row-label">过渡动画</span>
        <UiSwitch
          :model-value="preferences.animations"
          data-testid="settings-toggle-animations"
          @update:model-value="setPreference('animations', $event)"
        />
      </div>
    </section>
  </UiDrawer>
</template>

<style scoped>
.settings-drawer__section + .settings-drawer__section {
  margin-top: var(--ui-space-4);
}

.settings-drawer__title {
  margin: 0 0 6px;
  font-size: var(--ui-font-size-base);
  font-weight: var(--ui-weight-semibold);
  line-height: var(--ui-line-height-base);
  color: var(--ui-foreground);
}

.settings-drawer__chips {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ui-space-3);
  padding: 10px 0 6px;
}

.settings-drawer__chip {
  width: 24px;
  height: 24px;
  padding: 0;
  border: none;
  border-radius: 50%;
  cursor: pointer;
}

.settings-drawer__chip:focus-visible {
  outline: none;
  box-shadow: var(--ui-shadow-focus);
}

/* Selected swatch: white gap + brand ring (the ring follows the active primary). */
.settings-drawer__chip.is-active {
  box-shadow:
    0 0 0 2px var(--ui-card),
    0 0 0 4px var(--ui-primary);
}

/* Segmented control: muted track, active option raised to white with a
   hairline border and primary ink (Vben drawer language). */
.settings-drawer__segmented {
  display: flex;
  width: 100%;
  height: 28px;
  padding: 2px;
  margin-top: 8px;
  border-radius: 6px;
  background: var(--ui-muted);
}

.settings-drawer__segment {
  flex: 1;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid transparent;
  border-radius: 4px;
  background: transparent;
  color: var(--ui-foreground-secondary);
  font-family: inherit;
  font-size: var(--ui-font-size-sm);
  line-height: 1;
  cursor: pointer;
  transition:
    background-color var(--ui-ease),
    border-color var(--ui-ease),
    color var(--ui-ease);
}

.settings-drawer__segment.is-active {
  background: var(--ui-card);
  border-color: var(--ui-border);
  color: var(--ui-primary-text);
  font-weight: var(--ui-weight-medium);
}

.settings-drawer__segment:focus-visible {
  outline: none;
  box-shadow: var(--ui-shadow-focus);
}

.settings-drawer__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ui-space-3);
  min-height: 40px;
}

.settings-drawer__row-label {
  font-size: var(--ui-font-size-sm);
  color: var(--ui-foreground);
}
</style>
