<script setup lang="ts">
/**
 * NewShell — v2 console chrome (U1 formal shell for /app).
 * PostHog-style rail: white sidebar with hairline divider over warm canvas,
 * grouped nav with a left accent bar on the active item and a slim topbar
 * holding the user chip. Nav mirrors the legacy AppShell structure 1:1;
 * admin pages still render their TDesign-era content until U2 migrates them.
 */
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import {
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuItemIndicator,
  DropdownMenuPortal,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuRoot,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from 'radix-vue';
import {
  AppIcon,
  ChartBarIcon,
  CheckCircleIcon,
  DashboardIcon,
  DeleteIcon,
  DownloadIcon,
  EditIcon,
  ErrorCircleIcon,
  FilePasteIcon,
  Fullscreen1Icon,
  FullscreenExit1Icon,
  FolderOpenIcon,
  InfoCircleIcon,
  LayersIcon,
  LockOnIcon,
  MenuIcon,
  MoneyIcon,
  NotificationIcon,
  RefreshIcon,
  RobotIcon,
  SearchIcon,
  SecuredIcon,
  ServerIcon,
  SettingIcon,
  ShopIcon,
  ToolsIcon,
  UserIcon,
  UsergroupCircleIcon,
} from 'tdesign-icons-vue-next';
import { useAuthStore } from '@/stores/auth';
import { installPageDescToggle } from '@/utils/page-desc-toggle';
import { language } from '@/i18n';
import SettingsDrawer from '@/components/SettingsDrawer.vue';
import LockScreen from '@/components/LockScreen.vue';
import ErrorBoundary from '@/components/ErrorBoundary.vue';
import { UiTooltip } from '@/ui';
import { initPreferences, preferences, setPreference } from '@/preferences';
import type { Component } from 'vue';

const auth = useAuthStore();
const route = useRoute();
const router = useRouter();

// Console preferences drive rail/topbar/tabbar chrome below. The core applies
// its persisted values (dataset attributes + CSS vars) on init; the call is
// idempotent, so per-mount invocation is safe.
initPreferences();

/** Language options shown in the user menu (labels are language-neutral). */
const LANGS = [
  { code: 'zh-Hans', label: '简体中文' },
  { code: 'en', label: 'English' },
] as const;

interface NavItem {
  name: string;
  label: string;
  icon: Component;
}

const regularNav: NavItem[] = [
  { name: 'overview', label: '总览', icon: DashboardIcon },
  { name: 'keys', label: '我的密钥', icon: LockOnIcon },
  { name: 'usage', label: '用量', icon: ChartBarIcon },
  { name: 'skills', label: '技能库', icon: AppIcon },
  { name: 'model-approvals', label: '模型申请', icon: EditIcon },
  { name: 'profile', label: '资料', icon: UserIcon },
];

// Admin navigation mirrors the Tencent AI-gateway instance-level structure
// (#675): 模型管理 / 访问与授权(≈消费者管理) / 用量与配额 / 成本管理 /
// 可观测性 / 安全与配置 / 集成管理. Grouping IS the architecture — keep the
// seven groups readable against docs/tencent-ai-gateway-mapping.md §IA 对齐.
const modelNav: NavItem[] = [
  { name: 'providers', label: '供应商', icon: ShopIcon },
  { name: 'plans', label: '订阅', icon: LayersIcon },
  { name: 'credentials', label: '上游凭证', icon: SecuredIcon },
];

const accessNav: NavItem[] = [
  { name: 'users', label: '用户', icon: UserIcon },
  { name: 'teams', label: '团队', icon: UsergroupCircleIcon },
  { name: 'projects', label: '项目', icon: FolderOpenIcon },
  { name: 'grants', label: '授权', icon: LockOnIcon },
  { name: 'approval-center', label: '审批中心', icon: CheckCircleIcon },
  { name: 'consumers', label: 'API 消费者', icon: SecuredIcon },
];

const usageQuotaNav: NavItem[] = [
  { name: 'admin-usage', label: '用量报表', icon: ChartBarIcon },
  { name: 'quota-rules', label: '配额规则', icon: ErrorCircleIcon },
  { name: 'exports', label: '导出任务', icon: DownloadIcon },
  { name: 'deletions', label: '用量删除', icon: DeleteIcon },
];

const costNav: NavItem[] = [
  { name: 'cost', label: '成本报表', icon: MoneyIcon },
  { name: 'reconciliations', label: '账单对账', icon: FilePasteIcon },
  { name: 'roi', label: '缓存收益', icon: ChartBarIcon },
  { name: 'prices', label: '定价', icon: MoneyIcon },
];

const observabilityNav: NavItem[] = [
  { name: 'audit', label: '审计日志', icon: FilePasteIcon },
  { name: 'mcp-access-logs', label: 'MCP 访问日志', icon: FilePasteIcon },
  { name: 'retention-logs', label: '内容留痕', icon: FilePasteIcon },
];

const securityConfigNav: NavItem[] = [
  { name: 'configs', label: '全局配置', icon: SettingIcon },
  { name: 'alert-rules', label: '告警规则', icon: ErrorCircleIcon },
  { name: 'webhooks', label: 'Webhook 端点', icon: NotificationIcon },
  { name: 'settings', label: '部署信息', icon: InfoCircleIcon },
];

const integrationNav: NavItem[] = [
  { name: 'mcp-services', label: 'MCP 服务', icon: ToolsIcon },
  { name: 'agents', label: '智能体', icon: RobotIcon },
  { name: 'services', label: '服务管理', icon: ServerIcon },
  { name: 'skillhub', label: '技能库管理', icon: AppIcon },
];

const isAdmin = computed(() => auth.user?.role === 'SYSTEM_ADMIN');

/** "安全与配置 / Webhook 端点" style trail for the topbar (Vben-like chrome).
 *  Only grouped (admin) pages show a trail; ungrouped regular pages carry
 *  their own page title and a trail would just duplicate it. Split into
 *  group/current segments so the two text tones can differ, as on the demo. */
const breadcrumb = computed(() => {
  const name = route.name as string | undefined;
  if (!name) return null;
  for (const group of navGroups.value) {
    const item = group.items.find((i) => i.name === name);
    if (item) {
      return group.title ? { group: group.title, label: item.label } : null;
    }
  }
  return null;
});

const navGroups = computed(() => {
  const groups: { title?: string; items: NavItem[] }[] = [{ items: regularNav }];
  if (isAdmin.value) {
    groups.push(
      { title: '模型管理', items: modelNav },
      { title: '访问与授权', items: accessNav },
      { title: '用量与配额', items: usageQuotaNav },
      { title: '成本管理', items: costNav },
      { title: '可观测性', items: observabilityNav },
      { title: '安全与配置', items: securityConfigNav },
      { title: '集成管理', items: integrationNav },
    );
  }
  return groups;
});

const userInitial = computed(() => {
  const name = auth.user?.username ?? '?';
  return name.slice(0, 1).toUpperCase();
});

const isActive = (name: string) => route.name === name;

// ---- tab bar (Vben chrome-style visited-page tabs) ----
interface ShellTab {
  name: string;
  label: string;
}

const TABS_KEY = 'miqrogate.shell-tabs';

function labelOf(name: string): string | undefined {
  for (const group of navGroups.value) {
    const item = group.items.find((i) => i.name === name);
    if (item) return item.label;
  }
  return undefined;
}

const tabs = ref<ShellTab[]>([]);
try {
  const saved = JSON.parse(sessionStorage.getItem(TABS_KEY) ?? '[]') as ShellTab[];
  if (Array.isArray(saved)) tabs.value = saved.filter((t) => t && typeof t.name === 'string');
} catch {
  tabs.value = [];
}

watch(tabs, (value) => sessionStorage.setItem(TABS_KEY, JSON.stringify(value.slice(-24))), {
  deep: true,
});

watch(
  () => route.name as string | undefined,
  (name) => {
    if (!name) return;
    const label = labelOf(name);
    if (!label) return;
    if (!tabs.value.some((t) => t.name === name)) {
      tabs.value = [...tabs.value, { name, label }];
    }
  },
  { immediate: true },
);

function closeTab(name: string) {
  const index = tabs.value.findIndex((t) => t.name === name);
  if (index === -1) return;
  tabs.value = tabs.value.filter((t) => t.name !== name);
  if (route.name === name) {
    const next = tabs.value[Math.min(index, tabs.value.length - 1)];
    if (next) void router.push({ name: next.name });
  }
}

/** Narrow screens collapse the rail to icons only (>=640 hides the drawer entirely). */
const narrow = ref(false);
function updateNarrow() {
  narrow.value = window.innerWidth < 1080 && window.innerWidth >= 640;
}
// #440: initialize from the CURRENT width and clean the listener up on unmount
// (the old top-level addEventListener never fired before the first resize and
// leaked one listener per login).
// #830: clicking any page title collapses/expands the description line under
// it (delegated, installed once — headers are hand-rolled across 30+ views,
// and the 界面设置 drawer carries the same preference for discoverability).
installPageDescToggle();

onMounted(() => {
  updateNarrow();
  window.addEventListener('resize', updateNarrow);
  window.addEventListener('click', onTabMenuWindowClick);
  window.addEventListener('keydown', onTabMenuKeydown);
  window.addEventListener('scroll', onTabMenuWindowClick, true);
  // Auto-lock activity tracking: passive listeners, 15s idle check.
  window.addEventListener('mousemove', noteActivity, { passive: true });
  window.addEventListener('pointerdown', noteActivity, { passive: true });
  window.addEventListener('keydown', noteActivity, { passive: true });
  window.addEventListener('scroll', noteActivity, { passive: true });
  idleTimer = window.setInterval(() => {
    const minutes = preferences.lockMinutes;
    if (minutes > 0 && !locked.value && Date.now() - lastActivity.value > minutes * 60_000) {
      locked.value = true;
    }
  }, 15_000);
  document.addEventListener('fullscreenchange', onFullscreenChange);
});
onUnmounted(() => {
  window.removeEventListener('resize', updateNarrow);
  window.removeEventListener('click', onTabMenuWindowClick);
  window.removeEventListener('keydown', onTabMenuKeydown);
  window.removeEventListener('scroll', onTabMenuWindowClick, true);
  window.removeEventListener('mousemove', noteActivity);
  window.removeEventListener('pointerdown', noteActivity);
  window.removeEventListener('keydown', noteActivity);
  window.removeEventListener('scroll', noteActivity);
  window.clearInterval(idleTimer);
  document.removeEventListener('fullscreenchange', onFullscreenChange);
});

// ---- tab context menu (right-click, Vben parity) ----
const tabMenu = ref<{ open: boolean; x: number; y: number; name: string }>({
  open: false,
  x: 0,
  y: 0,
  name: '',
});

function openTabMenu(event: MouseEvent, name: string) {
  tabMenu.value = { open: true, x: event.clientX, y: event.clientY, name };
}

function closeTabMenu() {
  tabMenu.value.open = false;
}

function tabMenuAction(
  action: 'reload' | 'close' | 'closeLeft' | 'closeRight' | 'closeOthers' | 'closeAll',
) {
  const name = tabMenu.value.name;
  const index = tabs.value.findIndex((t) => t.name === name);
  closeTabMenu();

  const goTo = (target: string) => {
    if (route.name !== target) void router.push({ name: target });
  };

  if (action === 'closeAll') {
    tabs.value = [];
    void router.push({ name: 'overview' });
    return;
  }
  if (index === -1) return;

  const current = route.name as string | undefined;
  switch (action) {
    case 'reload':
      if (current === name) window.location.reload();
      else goTo(name);
      break;
    case 'close':
      closeTab(name);
      break;
    case 'closeLeft': {
      const removed = tabs.value.slice(0, index).map((t) => t.name);
      tabs.value = tabs.value.filter((t) => !removed.includes(t.name));
      if (current && removed.includes(current)) goTo(name);
      break;
    }
    case 'closeRight': {
      const removed = tabs.value.slice(index + 1).map((t) => t.name);
      tabs.value = tabs.value.filter((t) => !removed.includes(t.name));
      if (current && removed.includes(current)) goTo(name);
      break;
    }
    case 'closeOthers': {
      const keepCurrent = current === name;
      tabs.value = tabs.value.filter((t) => t.name === name);
      if (!keepCurrent) goTo(name);
      break;
    }
  }
}

function onTabMenuWindowClick() {
  if (tabMenu.value.open) closeTabMenu();
}

function onTabMenuKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') closeTabMenu();
}

// ---- auto lock screen (Vben 自动锁屏) ----
const locked = ref(false);
const lastActivity = ref(Date.now());
let idleTimer: number | undefined;

function noteActivity() {
  lastActivity.value = Date.now();
}

function lockNow() {
  locked.value = true;
}

// ---- fullscreen toggle (Vben 全屏内容) ----
const isFullscreen = ref(false);
function onFullscreenChange() {
  isFullscreen.value = Boolean(document.fullscreenElement);
}
async function toggleFullscreen() {
  try {
    if (document.fullscreenElement) {
      await document.exitFullscreen();
    } else {
      await document.documentElement.requestFullscreen();
    }
  } catch {
    // Browsers may deny fullscreen outside a user gesture; the button state
    // stays driven by the fullscreenchange event either way.
  }
}

// ---- rail menu search (Vben 菜单搜索) ----
const navQuery = ref('');

const filteredNavGroups = computed(() => {
  const q = navQuery.value.trim().toLowerCase();
  if (!q) return navGroups.value;
  return navGroups.value
    .map((group) => ({
      ...group,
      items: group.items.filter(
        (item) => item.label.toLowerCase().includes(q) || item.name.toLowerCase().includes(q),
      ),
    }))
    .filter((group) => group.items.length > 0);
});

// ---- top route progress bar (Vben 顶部进度条) ----
const progressActive = ref(false);
let progressTimer: number | undefined;

watch(
  () => route.fullPath,
  () => {
    window.clearTimeout(progressTimer);
    progressActive.value = false;
    requestAnimationFrame(() => {
      progressActive.value = true;
      progressTimer = window.setTimeout(() => {
        progressActive.value = false;
      }, 600);
    });
  },
);

onUnmounted(() => {
  window.clearTimeout(progressTimer);
});

// ---- instant route switching (#668) ----
// Two halves: (1) the content scroller resets to the top on every page
// change — without it the next page inherits the previous page's scroll
// offset (long page → short page lands mid/bottom); (2) route chunks are
// prefetched on menu hover/focus plus one idle pass, so a click resolves
// from the module cache instead of waiting on a network roundtrip.
const contentEl = ref<HTMLElement | null>(null);

watch(
  () => route.path,
  () => {
    if (contentEl.value) contentEl.value.scrollTop = 0;
  },
);

const prefetchedRoutes = new Set<string>();

function prefetchRoute(name: string): void {
  if (!name || prefetchedRoutes.has(name)) return;
  prefetchedRoutes.add(name);
  try {
    for (const record of router.resolve({ name }).matched) {
      for (const loader of Object.values(record.components ?? {})) {
        if (typeof loader === 'function') {
          void Promise.resolve((loader as () => unknown)()).catch(() => {
            /* Best effort; the real navigation surfaces load errors (#663). */
          });
        }
      }
    }
  } catch {
    /* Unknown route name — regular navigation will report the real error. */
  }
}

// Idle fallback: once the first screen has settled, quietly warm the visible
// menu's chunks one by one so keyboard navigation is instant too.
const PREFETCH_IDLE_DELAY_MS = 1500;
const PREFETCH_STEP_MS = 120;
let prefetchTimer: number | undefined;

onMounted(() => {
  prefetchTimer = window.setTimeout(() => {
    const names = navGroups.value.flatMap((group) => group.items.map((item) => item.name));
    void (async () => {
      for (const name of names) {
        prefetchRoute(name);
        await new Promise((resolve) => setTimeout(resolve, PREFETCH_STEP_MS));
      }
    })();
  }, PREFETCH_IDLE_DELAY_MS);
});

onUnmounted(() => {
  window.clearTimeout(prefetchTimer);
});

/** Icon-only rail: the user pinned the collapse (settings drawer) OR the window is narrow. */
const iconOnly = computed(() => narrow.value || preferences.collapsed);

/** Hamburger toggle — persists through the preferences core. */
function toggleCollapsed() {
  setPreference('collapsed', !preferences.collapsed);
}

// ---- topbar controls ----
const settingsOpen = ref(false);

/** Visited-tab refresh affordance: reload the console (router.go(0)). */
function refreshPage() {
  router.go(0);
}

async function handleLogout() {
  await auth.logout();
  await router.push({ name: 'login' });
}
</script>

<template>
  <div class="new-shell">
    <aside class="new-shell__rail" :class="{ 'new-shell__rail--icons': iconOnly }">
      <div v-if="preferences.showLogo" class="new-shell__brand">
        <UiTooltip text="MiQroGate" side="right" as-child :disabled="!iconOnly">
          <span class="new-shell__brand-mark">M</span>
        </UiTooltip>
        <span class="new-shell__brand-name">MiQroGate</span>
      </div>

      <div class="new-shell__search">
        <SearchIcon class="new-shell__search-icon" />
        <input
          v-model="navQuery"
          class="new-shell__search-input"
          type="search"
          placeholder="搜索菜单"
          aria-label="搜索菜单"
          data-testid="shell-nav-search"
        />
      </div>

      <nav class="new-shell__nav" aria-label="主导航">
        <p v-if="!filteredNavGroups.length" class="new-shell__search-empty">无匹配菜单</p>
        <div
          v-for="group in filteredNavGroups"
          :key="group.title ?? 'regular'"
          class="new-shell__group"
        >
          <p v-if="group.title" class="new-shell__group-title">{{ group.title }}</p>
          <UiTooltip
            v-for="item in group.items"
            :key="item.name"
            :text="item.label"
            side="right"
            as-child
            :disabled="!iconOnly"
          >
            <router-link
              :to="{ name: item.name }"
              class="new-shell__nav-item"
              :class="{ 'new-shell__nav-item--active': isActive(item.name) }"
              @mouseenter="prefetchRoute(item.name)"
              @focus="prefetchRoute(item.name)"
            >
              <component :is="item.icon" class="new-shell__nav-icon" />
              <span class="new-shell__nav-label">{{ item.label }}</span>
            </router-link>
          </UiTooltip>
        </div>
      </nav>

      <div class="new-shell__rail-foot">
        <p class="new-shell__version">MiQroGate 0.1</p>
      </div>
    </aside>

    <main class="new-shell__main">
      <div
        class="new-shell__progress"
        :class="{ 'new-shell__progress--on': progressActive }"
        aria-hidden="true"
      />
      <header v-if="preferences.showHeader" class="new-shell__topbar">
        <div class="new-shell__topbar-left">
          <button
            type="button"
            class="new-shell__icon-btn"
            data-testid="shell-collapse"
            :title="preferences.collapsed ? '展开侧边栏' : '收起侧边栏'"
            :aria-label="preferences.collapsed ? '展开侧边栏' : '收起侧边栏'"
            @click="toggleCollapsed"
          >
            <MenuIcon class="new-shell__icon-btn-icon" />
          </button>
          <span
            v-if="preferences.showBreadcrumb && breadcrumb"
            class="new-shell__breadcrumb"
            data-testid="shell-breadcrumb"
          >
            <span class="new-shell__breadcrumb-group">{{ breadcrumb.group }}</span>
            <span class="new-shell__breadcrumb-sep" aria-hidden="true">/</span>
            <span class="new-shell__breadcrumb-current">{{ breadcrumb.label }}</span>
          </span>
        </div>
        <div class="new-shell__topbar-right">
          <button
            type="button"
            class="new-shell__icon-btn"
            data-testid="shell-settings-open"
            title="系统设置"
            aria-label="系统设置"
            @click="settingsOpen = true"
          >
            <SettingIcon class="new-shell__icon-btn-icon" />
          </button>
          <DropdownMenuRoot>
            <DropdownMenuTrigger class="new-shell__user" data-testid="shell-user-menu">
              <span class="new-shell__user-avatar" aria-hidden="true">{{ userInitial }}</span>
              <span class="new-shell__user-name">{{ auth.user?.username }}</span>
              <svg
                class="new-shell__user-chevron"
                width="12"
                height="12"
                viewBox="0 0 16 16"
                fill="none"
                aria-hidden="true"
              >
                <path
                  d="M4 6.5 8 10.5 12 6.5"
                  stroke="currentColor"
                  stroke-width="1.5"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                />
              </svg>
            </DropdownMenuTrigger>
            <DropdownMenuPortal>
              <DropdownMenuContent class="ui-menu" :side-offset="6" :align="'end'">
                <div class="new-shell__user-menu-head">
                  <span class="new-shell__user-menu-name">{{ auth.user?.username }}</span>
                  <span class="new-shell__user-menu-role">{{
                    auth.user?.role === 'SYSTEM_ADMIN' ? '系统管理员' : '用户'
                  }}</span>
                </div>
                <DropdownMenuSeparator class="new-shell__user-menu-sep" />
                <div class="new-shell__user-menu-section">语言</div>
                <DropdownMenuRadioGroup v-model="language">
                  <DropdownMenuRadioItem
                    v-for="lang in LANGS"
                    :key="lang.code"
                    :value="lang.code"
                    class="ui-menu__item new-shell__user-menu-item"
                    :data-testid="`shell-lang-${lang.code}`"
                  >
                    <span>{{ lang.label }}</span>
                    <DropdownMenuItemIndicator class="new-shell__user-menu-check">
                      <svg
                        width="13"
                        height="13"
                        viewBox="0 0 16 16"
                        fill="none"
                        aria-hidden="true"
                      >
                        <path
                          d="M3.5 8.5 6.5 11.5 12.5 4.5"
                          stroke="currentColor"
                          stroke-width="1.8"
                          stroke-linecap="round"
                          stroke-linejoin="round"
                        />
                      </svg>
                    </DropdownMenuItemIndicator>
                  </DropdownMenuRadioItem>
                </DropdownMenuRadioGroup>
                <DropdownMenuSeparator class="new-shell__user-menu-sep" />
                <DropdownMenuItem
                  class="ui-menu__item new-shell__user-menu-item"
                  data-testid="shell-lock"
                  @select="lockNow"
                  >锁定屏幕</DropdownMenuItem
                >
                <DropdownMenuSeparator class="new-shell__user-menu-sep" />
                <DropdownMenuItem
                  class="ui-menu__item new-shell__user-menu-item new-shell__user-menu-item--danger"
                  data-testid="shell-logout"
                  @select="handleLogout"
                  >退出登录</DropdownMenuItem
                >
              </DropdownMenuContent>
            </DropdownMenuPortal>
          </DropdownMenuRoot>
        </div>
      </header>

      <!-- Header hidden (showHeader=false): keep a hairline strip with the
           collapse + settings controls so the console stays usable. -->
      <div v-else class="new-shell__slim-strip" data-testid="shell-topbar-slim">
        <button
          type="button"
          class="new-shell__icon-btn"
          data-testid="shell-collapse"
          :title="preferences.collapsed ? '展开侧边栏' : '收起侧边栏'"
          :aria-label="preferences.collapsed ? '展开侧边栏' : '收起侧边栏'"
          @click="toggleCollapsed"
        >
          <MenuIcon class="new-shell__icon-btn-icon" />
        </button>
        <button
          type="button"
          class="new-shell__icon-btn new-shell__slim-strip-end"
          data-testid="shell-settings-open"
          title="系统设置"
          aria-label="系统设置"
          @click="settingsOpen = true"
        >
          <SettingIcon class="new-shell__icon-btn-icon" />
        </button>
        <button
          type="button"
          class="new-shell__icon-btn"
          data-testid="shell-fullscreen"
          :title="isFullscreen ? '退出全屏' : '全屏'"
          :aria-label="isFullscreen ? '退出全屏' : '全屏'"
          @click="toggleFullscreen"
        >
          <FullscreenExit1Icon v-if="isFullscreen" class="new-shell__icon-btn-icon" />
          <Fullscreen1Icon v-else class="new-shell__icon-btn-icon" />
        </button>
      </div>

      <div
        v-if="preferences.showTabs && tabs.length"
        class="new-shell__tabbar"
        data-testid="shell-tabbar"
      >
        <div
          v-for="tab in tabs"
          :key="tab.name"
          class="new-shell__tab"
          :class="{ 'new-shell__tab--active': isActive(tab.name) }"
          @click="router.push({ name: tab.name })"
          @contextmenu.prevent="openTabMenu($event, tab.name)"
        >
          <span class="new-shell__tab-label">{{ tab.label }}</span>
          <button
            v-if="tabs.length > 1"
            type="button"
            class="new-shell__tab-close"
            :aria-label="`关闭 ${tab.label}`"
            @click.stop="closeTab(tab.name)"
          >
            <svg width="11" height="11" viewBox="0 0 16 16" fill="none" aria-hidden="true">
              <path
                d="M4 4 12 12M12 4 4 12"
                stroke="currentColor"
                stroke-width="1.6"
                stroke-linecap="round"
              />
            </svg>
          </button>
        </div>
        <button
          v-if="preferences.showTabRefresh"
          type="button"
          class="new-shell__icon-btn new-shell__icon-btn--sm new-shell__tab-refresh"
          data-testid="shell-tab-refresh"
          title="刷新当前页"
          aria-label="刷新当前页"
          @click="refreshPage"
        >
          <RefreshIcon class="new-shell__icon-btn-icon" />
        </button>
      </div>

      <!-- Tab right-click menu (Vben: reload / close / close left / right /
           others / all). Positioned at the pointer; chrome comes from .ui-menu. -->
      <Teleport to="body">
        <div
          v-if="tabMenu.open"
          class="ui-menu new-shell__tabmenu"
          :style="{ left: `${tabMenu.x}px`, top: `${tabMenu.y}px` }"
          data-testid="shell-tab-menu"
          @click.stop
          @contextmenu.prevent
        >
          <button
            type="button"
            class="ui-menu__item new-shell__tabmenu-item"
            @click="tabMenuAction('reload')"
          >
            重新加载
          </button>
          <button
            type="button"
            class="ui-menu__item new-shell__tabmenu-item"
            @click="tabMenuAction('close')"
          >
            关闭标签页
          </button>
          <div class="new-shell__tabmenu-sep" />
          <button
            type="button"
            class="ui-menu__item new-shell__tabmenu-item"
            @click="tabMenuAction('closeLeft')"
          >
            关闭左侧标签页
          </button>
          <button
            type="button"
            class="ui-menu__item new-shell__tabmenu-item"
            @click="tabMenuAction('closeRight')"
          >
            关闭右侧标签页
          </button>
          <div class="new-shell__tabmenu-sep" />
          <button
            type="button"
            class="ui-menu__item new-shell__tabmenu-item"
            @click="tabMenuAction('closeOthers')"
          >
            关闭其它标签页
          </button>
          <button
            type="button"
            class="ui-menu__item new-shell__tabmenu-item"
            @click="tabMenuAction('closeAll')"
          >
            关闭全部标签页
          </button>
        </div>
      </Teleport>

      <div ref="contentEl" class="new-shell__content">
        <!-- #833: page crashes keep the shell (nav stays usable); the card
             offers retry/reload/overview and clears on navigation. -->
        <ErrorBoundary>
          <RouterView v-slot="{ Component }">
            <Transition name="shell-page" mode="out-in">
              <component :is="Component" />
            </Transition>
          </RouterView>
        </ErrorBoundary>
      </div>
    </main>

    <SettingsDrawer v-model:open="settingsOpen" />

    <LockScreen
      v-if="locked"
      :username="auth.user?.username ?? ''"
      @unlock="locked = false"
      @logout="handleLogout"
    />
  </div>
</template>

<style scoped>
.new-shell {
  display: flex;
  height: 100vh;
  overflow: hidden;
  background: var(--ui-background);
  color: var(--ui-foreground);
}

.new-shell__rail {
  display: flex;
  flex-direction: column;
  width: var(--ui-sidebar-width);
  flex-shrink: 0;
  background: var(--ui-rail);
  border-right: 1px solid var(--ui-rail-line);
  transition: width var(--ui-ease);
}

.new-shell__rail--icons {
  width: 64px;
}

.new-shell__brand {
  display: flex;
  align-items: center;
  gap: var(--ui-space-2);
  height: var(--ui-header-height);
  padding: 0 var(--ui-space-5);
  border-bottom: 1px solid var(--ui-rail-line);
}

.new-shell__rail--icons .new-shell__brand {
  padding: 0;
  justify-content: center;
}

.new-shell__brand-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border-radius: var(--ui-radius-control);
  background: var(--ui-primary);
  color: #fff;
  font-size: 14px;
  font-weight: 700;
}

.new-shell__brand-name {
  font-size: var(--ui-font-size-base);
  font-weight: var(--ui-weight-semibold);
  letter-spacing: -0.01em;
  /* Rail ink that follows the rail surface — re-pointed for the light menu
     theme in design-base.css (the active item stays white-on-primary). */
  color: var(--ui-rail-text-strong);
}

.new-shell__nav {
  flex: 1;
  padding: var(--ui-space-5) var(--ui-space-3);
  overflow-y: auto;
}

.new-shell__rail--icons .new-shell__nav {
  padding: var(--ui-space-4) var(--ui-space-2);
}

.new-shell__group {
  margin-bottom: var(--ui-space-3);
}

.new-shell__group-title {
  margin: var(--ui-space-4) var(--ui-space-2) var(--ui-space-1) var(--ui-space-6);
  font-size: 12px;
  font-weight: var(--ui-weight-semibold);
  letter-spacing: 0.05em;
  text-transform: uppercase;
  color: var(--ui-rail-text-muted);
}

.new-shell__nav-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: var(--ui-space-3);
  height: 44px;
  padding: 0 var(--ui-space-4) 0 var(--ui-space-6);
  border-radius: 0;
  color: var(--ui-rail-text);
  font-size: var(--ui-font-size-base);
  text-decoration: none;
  transition:
    background-color var(--ui-ease),
    color var(--ui-ease);
}

.new-shell__rail--icons .new-shell__nav-item {
  justify-content: center;
  padding: 0;
}

.new-shell__nav-item:hover {
  background: var(--ui-rail-hover);
  color: var(--ui-rail-text-strong);
}

.new-shell__nav-item--active {
  background: var(--ui-primary);
  color: var(--ui-foreground-inverse);
}

.new-shell__nav-item--active:hover {
  background: var(--ui-primary);
  color: var(--ui-foreground-inverse);
}

.new-shell__nav-icon {
  width: 16px;
  height: 16px;
  color: var(--ui-rail-text-muted);
  flex-shrink: 0;
}

.new-shell__nav-item:hover .new-shell__nav-icon {
  color: var(--ui-rail-text-strong);
}

/* Active item keeps white-on-primary in both menu themes. */
.new-shell__nav-item--active .new-shell__nav-icon {
  color: var(--ui-foreground-inverse);
}

.new-shell__rail-foot {
  border-top: 1px solid var(--ui-rail-line);
  padding: var(--ui-space-3) var(--ui-space-5);
}

.new-shell__rail--icons .new-shell__rail-foot {
  padding: var(--ui-space-3) 0;
}

.new-shell__version {
  margin: 0;
  font-size: var(--ui-font-size-xs);
  color: var(--ui-rail-text-muted);
  letter-spacing: 0.02em;
}

.new-shell__search {
  position: relative;
  margin: 0 12px 8px;
  max-height: 40px;
  overflow: hidden;
  transition:
    max-height 200ms var(--ui-ease),
    opacity 140ms ease,
    margin 200ms var(--ui-ease);
}

.new-shell__search-icon {
  position: absolute;
  left: 8px;
  top: 50%;
  transform: translateY(-50%);
  font-size: 14px;
  color: var(--ui-rail-text-muted);
  pointer-events: none;
}

.new-shell__search-input {
  width: 100%;
  height: 28px;
  padding: 0 8px 0 26px;
  border: 1px solid var(--ui-rail-line);
  border-radius: var(--ui-radius-control);
  background: var(--ui-rail-hover);
  color: var(--ui-rail-text);
  font-family: inherit;
  font-size: var(--ui-font-size-xs);
  outline: none;
}

.new-shell__search-input::placeholder {
  color: var(--ui-rail-text-muted);
}

.new-shell__search-input:focus {
  border-color: var(--ui-primary);
}

.new-shell__search-empty {
  margin: 8px 16px;
  font-size: var(--ui-font-size-xs);
  color: var(--ui-rail-text-muted);
}

.new-shell__progress {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  height: 2px;
  background: var(--ui-primary);
  transform: scaleX(0);
  transform-origin: 0 50%;
  opacity: 0;
  transition:
    transform 400ms ease,
    opacity 240ms ease;
  pointer-events: none;
  z-index: 3000;
}

.new-shell__progress--on {
  transform: scaleX(0.92);
  opacity: 1;
  transition:
    transform 520ms ease-out,
    opacity 80ms ease;
}

.new-shell__brand-name,
.new-shell__nav-label {
  max-width: 180px;
  overflow: hidden;
  white-space: nowrap;
  transition:
    max-width 200ms var(--ui-ease),
    opacity 140ms ease;
}

.new-shell__rail--icons .new-shell__brand-name,
.new-shell__rail--icons .new-shell__nav-label {
  max-width: 0;
  opacity: 0;
}

.new-shell__group-title,
.new-shell__version {
  overflow: hidden;
  transition:
    max-height 200ms var(--ui-ease),
    opacity 140ms ease;
  max-height: 32px;
}

.new-shell__rail--icons .new-shell__group-title,
.new-shell__rail--icons .new-shell__version {
  max-height: 0;
  opacity: 0;
}

.new-shell__rail--icons .new-shell__search {
  max-height: 0;
  opacity: 0;
  margin-bottom: 0;
  pointer-events: none;
}

.new-shell__main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  height: 100vh;
}

.new-shell__topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ui-space-4);
  height: var(--ui-header-height);
  padding: 0 var(--ui-space-6);
  background: var(--ui-card);
  border-bottom: 1px solid var(--ui-border);
  flex-shrink: 0;
}

.new-shell__topbar-left {
  display: flex;
  align-items: center;
  gap: var(--ui-space-2);
  min-width: 0;
}

.new-shell__breadcrumb {
  display: inline-flex;
  align-items: center;
  font-size: var(--ui-font-size-base); /* v2.pro live: antd breadcrumb 14px */
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.new-shell__breadcrumb-group {
  color: var(--ui-foreground-secondary);
}

.new-shell__breadcrumb-sep {
  margin: 0 8px; /* v2.pro live */
  color: #999999; /* v2.pro live: antd breadcrumb separator */
}

.new-shell__breadcrumb-current {
  color: var(--ui-foreground);
}

.new-shell__topbar-right {
  display: flex;
  align-items: center;
  gap: var(--ui-space-4);
}

.new-shell__user {
  display: flex;
  align-items: center;
  gap: var(--ui-space-2);
}

.new-shell__user-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: var(--ui-primary-soft);
  color: var(--ui-primary-text);
  font-size: var(--ui-font-size-xs);
  font-weight: var(--ui-weight-semibold);
  flex-shrink: 0;
}

.new-shell__user-name {
  font-size: var(--ui-font-size-sm);
  font-weight: var(--ui-weight-medium);
}

.new-shell__user {
  display: inline-flex;
  align-items: center;
  gap: var(--ui-space-2);
  border: none;
  border-radius: var(--ui-radius-control);
  background: transparent;
  color: var(--ui-foreground);
  font-family: inherit;
  padding: var(--ui-space-1) var(--ui-space-2);
  cursor: pointer;
  transition: background-color var(--ui-ease);
}

.new-shell__user:hover {
  background: var(--ui-fill-hover);
}

.new-shell__user:focus-visible {
  outline: none;
  box-shadow: var(--ui-shadow-focus);
}

.new-shell__user-chevron {
  color: var(--ui-foreground-faint);
}

.new-shell__user-menu-head {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: var(--ui-space-2) var(--ui-space-3);
}

.new-shell__user-menu-name {
  font-size: var(--ui-font-size-sm);
  font-weight: var(--ui-weight-semibold);
  color: var(--ui-foreground);
}

.new-shell__user-menu-role {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-faint);
}

.new-shell__user-menu-sep {
  height: 1px;
  background: var(--ui-border-muted);
  margin: var(--ui-space-1) 0;
}

.new-shell__user-menu-section {
  padding: var(--ui-space-1) var(--ui-space-3) 2px;
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-faint);
}

.new-shell__user-menu-check {
  margin-left: auto;
  display: grid;
  place-items: center;
  color: var(--ui-primary-text);
}

/* .new-shell__user-menu-item geometry comes from .ui-menu__item in the
   global sheet; only the danger variant stays scoped. */
.new-shell__user-menu-item--danger {
  color: var(--ui-danger-fg);
}

/* Visited-page tabs — Vben v2 style: a 32px white strip, tabs separated by
   hairline rules, the active tab a white card with a border and primary text. */
.new-shell__tabbar {
  display: flex;
  align-items: stretch;
  /* Vben live: 3px gutter between card tabs, small side padding. */
  gap: 3px;
  padding: 0 8px;
  height: 32px;
  flex-shrink: 0;
  background: var(--ui-card);
  border-bottom: 1px solid var(--ui-border);
  overflow-x: auto;
  scrollbar-width: none;
}

.new-shell__tabbar::-webkit-scrollbar {
  display: none;
}

.new-shell__tab {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  /* Vben card tabs: 30px chrome cards sitting 2px below the strip top,
     6/6/0/0 corners, hairline border; the active tab fills with primary. */
  height: 30px;
  margin-top: 2px;
  padding: 0 10px 0 16px;
  border: 1px solid var(--ui-border-strong);
  border-bottom-color: var(--ui-border);
  border-radius: 6px 6px 0 0;
  background: var(--ui-card);
  font-size: var(--ui-font-size-base);
  color: var(--ui-foreground);
  white-space: nowrap;
  cursor: pointer;
  user-select: none;
  transition:
    background-color var(--ui-ease),
    color var(--ui-ease),
    border-color var(--ui-ease);
}

.new-shell__tab:hover {
  color: var(--ui-primary-text);
}

.new-shell__tab--active,
.new-shell__tab--active:hover {
  border-color: var(--ui-primary);
  border-bottom-color: var(--ui-primary);
  background: var(--ui-primary);
  color: var(--ui-foreground-inverse);
}

.new-shell__tab-label {
  line-height: 1;
}

.new-shell__tabmenu {
  position: fixed;
  z-index: 3000;
}

.new-shell__tabmenu-item {
  width: 100%;
  border: 0;
  background: none;
  font: inherit;
  text-align: left;
}

.new-shell__tabmenu-sep {
  height: 1px;
  margin: 4px 0;
  background: var(--ui-border-muted);
}

.new-shell__tab-close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  padding: 0;
  border: none;
  border-radius: 2px;
  background: transparent;
  color: var(--ui-foreground-faint);
  cursor: pointer;
}

.new-shell__tab-close:hover {
  background: var(--ui-primary);
  color: var(--ui-foreground-inverse);
}

.new-shell__tab--active .new-shell__tab-close {
  color: rgba(255, 255, 255, 0.75);
}

.new-shell__tab--active .new-shell__tab-close:hover {
  background: rgba(255, 255, 255, 0.25);
  color: var(--ui-foreground-inverse);
}

.new-shell__content {
  flex: 1;
  overflow-y: auto;
}

/* Icon buttons (rail collapse / settings gear / tab refresh): ghost square,
   same focus-ring pattern as .ui-btn. position+z-index keeps the slim-strip
   buttons clickable when they hang over the strip's later siblings. */
.new-shell__icon-btn {
  position: relative;
  z-index: 1;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  padding: 0;
  border: none;
  border-radius: var(--ui-radius-control);
  background: transparent;
  color: var(--ui-foreground-secondary);
  cursor: pointer;
  flex-shrink: 0;
  transition:
    background-color var(--ui-ease),
    color var(--ui-ease);
}

.new-shell__icon-btn:hover {
  background: var(--ui-fill-hover);
  color: var(--ui-foreground);
}

.new-shell__icon-btn:focus-visible {
  outline: none;
  box-shadow: var(--ui-shadow-focus);
}

.new-shell__icon-btn--sm {
  width: 24px;
  height: 24px;
}

.new-shell__icon-btn-icon {
  width: 16px;
  height: 16px;
}

.new-shell__icon-btn--sm .new-shell__icon-btn-icon {
  width: 14px;
  height: 14px;
}

/* showHeader=false: a hairline strip keeps the collapse + settings controls
   reachable; the buttons overflow it downward so they stay clickable without
   restoring the header bar. */
.new-shell__slim-strip {
  display: flex;
  align-items: flex-start;
  gap: var(--ui-space-1);
  height: 8px;
  padding: 0 var(--ui-space-2);
  flex-shrink: 0;
}

.new-shell__slim-strip-end {
  margin-left: auto;
}

/* Tab refresh sits at the right end of the visited-tabs strip. */
.new-shell__tab-refresh {
  margin-left: auto;
  margin-right: var(--ui-space-1);
  align-self: center;
}

/* Page content fades in with a 4px settle when the route changes (#655) —
   vben/antd page-transition feel. Enter-only: the old page unmounts
   instantly (mode="out-in" pairs with no leave classes on purpose), so
   navigation never waits on an exit animation. Killed by the animations-off
   preference / reduced-motion through the global rules. */
.shell-page-enter-active {
  animation: shell-page-in 200ms var(--ui-ease-enter);
}

@keyframes shell-page-in {
  from {
    opacity: 0;
    transform: translateY(4px);
  }
}

/* Narrow screens (#627): the rail keeps its width, so the content column can
   get tighter than the topbar's controls — the username cluster used to
   overflow past the viewport edge and get clipped. Compact the chrome instead
   of letting it spill: hide the username and breadcrumb, tighten paddings.
   Verified overflow-free at 375px (acceptance H1). */
@media (max-width: 640px) {
  .new-shell__topbar {
    padding: 0 var(--ui-space-3);
    gap: var(--ui-space-2);
  }

  .new-shell__user-name,
  .new-shell__breadcrumb {
    display: none;
  }
}
</style>
