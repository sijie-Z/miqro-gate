<script setup lang="ts">
/**
 * NewShell — v2 console chrome (U1 formal shell for /app).
 * PostHog-style rail: white sidebar with hairline divider over warm canvas,
 * grouped nav with a left accent bar on the active item and a slim topbar
 * holding the user chip. Nav mirrors the legacy AppShell structure 1:1;
 * admin pages still render their TDesign-era content until U2 migrates them.
 */
import { computed, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
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
  FolderOpenIcon,
  LayersIcon,
  LockOnIcon,
  MoneyIcon,
  NotificationIcon,
  RobotIcon,
  SecuredIcon,
  ServerIcon,
  SettingIcon,
  ShopIcon,
  ToolsIcon,
  UserIcon,
  UsergroupCircleIcon,
} from 'tdesign-icons-vue-next';
import { useAuthStore } from '@/stores/auth';
import type { Component } from 'vue';

const auth = useAuthStore();
const route = useRoute();
const router = useRouter();

interface NavItem {
  name: string;
  label: string;
  icon: Component;
}

const regularNav: NavItem[] = [
  { name: 'overview', label: '总览', icon: DashboardIcon },
  { name: 'keys', label: '我的 Key', icon: LockOnIcon },
  { name: 'usage', label: '用量', icon: ChartBarIcon },
  { name: 'skills', label: '技能库', icon: AppIcon },
  { name: 'model-approvals', label: '模型申请', icon: EditIcon },
  { name: 'profile', label: '资料', icon: UserIcon },
];

const orgNav: NavItem[] = [
  { name: 'users', label: '用户', icon: UserIcon },
  { name: 'teams', label: '团队', icon: UsergroupCircleIcon },
  { name: 'projects', label: '项目', icon: FolderOpenIcon },
  { name: 'grants', label: '授权', icon: LockOnIcon },
  { name: 'approval-center', label: '审批中心', icon: CheckCircleIcon },
];

const providerNav: NavItem[] = [
  { name: 'providers', label: '供应商', icon: ShopIcon },
  { name: 'plans', label: '订阅', icon: LayersIcon },
  { name: 'credentials', label: '上游凭证', icon: SecuredIcon },
  { name: 'prices', label: '定价', icon: MoneyIcon },
];

const opsNav: NavItem[] = [
  { name: 'admin-usage', label: '用量报表', icon: ChartBarIcon },
  { name: 'cost', label: '成本报表', icon: MoneyIcon },
  { name: 'quota-rules', label: '配额规则', icon: ErrorCircleIcon },
  { name: 'roi', label: '缓存收益', icon: DownloadIcon },
  { name: 'exports', label: '导出任务', icon: DownloadIcon },
  { name: 'deletions', label: '用量删除', icon: DeleteIcon },
  { name: 'webhooks', label: 'Webhook 端点', icon: NotificationIcon },
  { name: 'consumers', label: 'API 消费者', icon: SecuredIcon },
  { name: 'skillhub', label: '技能库管理', icon: AppIcon },
  { name: 'agents', label: '智能体', icon: RobotIcon },
  { name: 'services', label: '服务管理', icon: ServerIcon },
  { name: 'configs', label: '全局配置', icon: SettingIcon },
  { name: 'mcp-services', label: 'MCP 服务', icon: ToolsIcon },
  { name: 'alert-rules', label: '告警规则', icon: ErrorCircleIcon },
  { name: 'audit', label: '审计日志', icon: FilePasteIcon },
];

const isAdmin = computed(() => auth.user?.role === 'SYSTEM_ADMIN');

const navGroups = computed(() => {
  const groups: { title?: string; items: NavItem[] }[] = [{ items: regularNav }];
  if (isAdmin.value) {
    groups.push(
      { title: '组织', items: orgNav },
      { title: '供应商', items: providerNav },
      { title: '数据与告警', items: opsNav },
    );
  }
  return groups;
});

const userInitial = computed(() => {
  const name = auth.user?.username ?? '?';
  return name.slice(0, 1).toUpperCase();
});

const isActive = (name: string) => route.name === name;

/** Narrow screens collapse the rail to icons only (>=640 hides the drawer entirely). */
const iconOnly = ref(false);
window.addEventListener('resize', () => {
  iconOnly.value = window.innerWidth < 1080 && window.innerWidth >= 640;
});

async function handleLogout() {
  await auth.logout();
  await router.push({ name: 'login' });
}
</script>

<template>
  <div class="new-shell">
    <aside class="new-shell__rail" :class="{ 'new-shell__rail--icons': iconOnly }">
      <div class="new-shell__brand">
        <span class="new-shell__brand-mark">M</span>
        <span v-if="!iconOnly" class="new-shell__brand-name">MiQroGate</span>
      </div>

      <nav class="new-shell__nav" aria-label="主导航">
        <div v-for="group in navGroups" :key="group.title ?? 'regular'" class="new-shell__group">
          <p v-if="group.title && !iconOnly" class="new-shell__group-title">{{ group.title }}</p>
          <router-link
            v-for="item in group.items"
            :key="item.name"
            :to="{ name: item.name }"
            class="new-shell__nav-item"
            :title="iconOnly ? item.label : undefined"
            :class="{ 'new-shell__nav-item--active': isActive(item.name) }"
          >
            <span class="new-shell__nav-accent" aria-hidden="true" />
            <component :is="item.icon" class="new-shell__nav-icon" />
            <span v-if="!iconOnly" class="new-shell__nav-label">{{ item.label }}</span>
          </router-link>
        </div>
      </nav>

      <div class="new-shell__rail-foot">
        <p v-if="!iconOnly" class="new-shell__version">MiQroGate 0.1</p>
      </div>
    </aside>

    <main class="new-shell__main">
      <header class="new-shell__topbar">
        <span class="new-shell__context">MiQroGate 控制台</span>
        <div class="new-shell__topbar-right">
          <div class="new-shell__user">
            <span class="new-shell__user-avatar" aria-hidden="true">{{ userInitial }}</span>
            <span class="new-shell__user-name">{{ auth.user?.username }}</span>
          </div>
          <button
            type="button"
            class="new-shell__logout"
            aria-label="退出登录"
            data-testid="shell-logout"
            @click="handleLogout"
          >
            <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
              <path
                d="M6 3H3.5A1.5 1.5 0 0 0 2 4.5v7A1.5 1.5 0 0 0 3.5 13H6m4-2.5L12.5 8 10 5.5M6.5 8H12.5"
                stroke="currentColor"
                stroke-width="1.4"
                stroke-linecap="round"
                stroke-linejoin="round"
              />
            </svg>
          </button>
        </div>
      </header>

      <div class="new-shell__content">
        <RouterView />
      </div>
    </main>
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
  background: #fbfaf7;
  border-right: 1px solid var(--ui-border);
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
  border-bottom: 1px solid var(--ui-border);
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
  margin-bottom: var(--ui-space-2);
}

.new-shell__group-title {
  margin: var(--ui-space-5) 0 var(--ui-space-2);
  padding: 0 var(--ui-space-2);
  font-size: 11px;
  font-weight: var(--ui-weight-semibold);
  letter-spacing: 0.05em;
  text-transform: uppercase;
  color: #8a8a94;
}

.new-shell__nav-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: var(--ui-space-2);
  height: 34px;
  padding: 0 var(--ui-space-2);
  border-radius: var(--ui-radius-control);
  color: var(--ui-foreground-secondary);
  font-size: var(--ui-font-size-sm);
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
  background: var(--ui-fill-hover);
  color: var(--ui-foreground);
}

.new-shell__nav-accent {
  position: absolute;
  left: -3px;
  top: 50%;
  transform: translateY(-50%);
  width: 3px;
  height: 16px;
  border-radius: var(--ui-radius-pill);
  background: transparent;
}

.new-shell__nav-item--active {
  background: var(--ui-primary-soft);
  color: var(--ui-primary);
  font-weight: var(--ui-weight-medium);
}

.new-shell__nav-item--active .new-shell__nav-accent {
  background: var(--ui-primary);
}

.new-shell__nav-icon {
  width: 18px;
  height: 18px;
  color: var(--ui-foreground-faint);
  flex-shrink: 0;
}

.new-shell__nav-item--active .new-shell__nav-icon {
  color: var(--ui-primary);
}

.new-shell__rail-foot {
  border-top: 1px solid var(--ui-border);
  padding: var(--ui-space-3) var(--ui-space-5);
}

.new-shell__rail--icons .new-shell__rail-foot {
  padding: var(--ui-space-3) 0;
}

.new-shell__version {
  margin: 0;
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-faint);
  letter-spacing: 0.02em;
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

.new-shell__context {
  font-size: var(--ui-font-size-sm);
  font-weight: var(--ui-weight-medium);
  color: var(--ui-foreground-faint);
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
  color: var(--ui-primary);
  font-size: var(--ui-font-size-xs);
  font-weight: var(--ui-weight-semibold);
  flex-shrink: 0;
}

.new-shell__user-name {
  font-size: var(--ui-font-size-sm);
  font-weight: var(--ui-weight-medium);
}

.new-shell__logout {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border: none;
  border-radius: var(--ui-radius-control);
  background: transparent;
  color: var(--ui-foreground-faint);
  cursor: pointer;
  flex-shrink: 0;
  transition:
    background-color var(--ui-ease),
    color var(--ui-ease);
}

.new-shell__logout:hover {
  background: var(--ui-fill-hover);
  color: var(--ui-danger-fg);
}

.new-shell__logout:focus-visible {
  outline: none;
  box-shadow: var(--ui-shadow-focus);
}

.new-shell__content {
  flex: 1;
  overflow-y: auto;
}
</style>
