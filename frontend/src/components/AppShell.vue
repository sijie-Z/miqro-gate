<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { DialogPlugin } from 'tdesign-vue-next';
import { useAuthStore } from '@/stores/auth';

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();

const isAdmin = computed(() => auth.user?.role === 'SYSTEM_ADMIN');

interface NavItem {
  name: string;
  label: string;
  icon: string;
}

const regularNav: NavItem[] = [
  { name: 'overview', label: '总览', icon: 'dashboard' },
  { name: 'keys', label: 'Virtual Keys', icon: 'lock-on' },
  { name: 'usage', label: 'Usage', icon: 'chart-bar' },
  { name: 'profile', label: 'Profile', icon: 'user' },
];

const orgNav: NavItem[] = [
  { name: 'users', label: 'Users', icon: 'user' },
  { name: 'teams', label: 'Teams', icon: 'usergroup' },
  { name: 'projects', label: 'Projects', icon: 'folder-open' },
  { name: 'grants', label: 'Grants', icon: 'lock-on' },
];

const providerNav: NavItem[] = [
  { name: 'providers', label: 'Providers', icon: 'shop' },
  { name: 'plans', label: 'Plans', icon: 'layers' },
  { name: 'credentials', label: 'Credentials', icon: 'secured' },
];

const opsNav: NavItem[] = [
  { name: 'admin-usage', label: 'Usage', icon: 'chart-bar' },
  { name: 'exports', label: 'Exports', icon: 'download' },
  { name: 'deletions', label: 'Deletions', icon: 'delete' },
  { name: 'webhooks', label: 'Webhooks', icon: 'notification' },
  { name: 'alert-rules', label: 'Alert Rules', icon: 'error-circle' },
  { name: 'audit', label: 'Audit', icon: 'file-paste' },
];

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

const activeItem = computed(() => route.name);

// Responsive shell: >=1280 full nav, 768-1279 collapsed (icons), <768 drawer.
const viewport = ref<'wide' | 'narrow' | 'mobile'>(
  window.innerWidth >= 1280 ? 'wide' : window.innerWidth >= 768 ? 'narrow' : 'mobile',
);
const drawerOpen = ref(false);

window.addEventListener('resize', () => {
  const width = window.innerWidth;
  viewport.value = width >= 1280 ? 'wide' : width >= 768 ? 'narrow' : 'mobile';
});

const collapsed = computed(() => viewport.value === 'narrow');
const mobile = computed(() => viewport.value === 'mobile');

function navigate(name: string) {
  drawerOpen.value = false;
  router.push({ name });
}

async function handleLogout() {
  try {
    await DialogPlugin.confirm({
      header: '退出登录',
      body: '退出后需要重新登录。',
      confirmBtn: '退出',
      cancelBtn: '取消',
      theme: 'warning',
    });
  } catch {
    return; // cancelled
  }
  await auth.logout();
  router.push('/login');
}
</script>

<template>
  <div class="shell">
    <header class="shell-header">
      <t-button
        v-if="mobile"
        variant="text"
        class="menu-toggle"
        aria-label="打开导航"
        data-testid="nav-toggle"
        @click="drawerOpen = true"
      >
        ☰
      </t-button>
      <div class="brand">
        <span class="brand-mark" aria-hidden="true">MK</span>
        <span class="brand-name">MiQroKey</span>
        <span class="version-badge">v0.1</span>
      </div>
      <div class="spacer" />
      <t-dropdown trigger="click" :min-column-width="160">
        <span class="user-menu" role="button" aria-haspopup="menu" data-testid="user-menu">
          <span class="mk-avatar">{{
            (auth.user?.displayName ?? auth.user?.username ?? '?').slice(0, 1).toUpperCase()
          }}</span>
          {{ auth.user?.displayName ?? auth.user?.username }}
          <t-icon name="poweroff" />
        </span>
        <template #dropdown>
          <t-dropdown-menu>
            <t-dropdown-item disabled>{{ auth.user?.role }}</t-dropdown-item>
            <t-dropdown-item divider data-testid="logout" @click="handleLogout"
              >退出登录</t-dropdown-item
            >
          </t-dropdown-menu>
        </template>
      </t-dropdown>
    </header>
    <div class="shell-body">
      <nav v-if="!mobile" class="shell-nav" :class="{ collapsed }" data-testid="shell-nav">
        <template v-for="group in navGroups" :key="group.title ?? 'regular'">
          <div v-if="group.title && !collapsed" class="nav-group-title">{{ group.title }}</div>
          <router-link
            v-for="item in group.items"
            :key="item.name"
            :to="{ name: item.name }"
            class="nav-item"
            :class="{ active: activeItem === item.name }"
            :title="collapsed ? item.label : undefined"
          >
            <t-icon class="nav-icon" :name="item.icon" />
            <span v-if="!collapsed" class="nav-label">{{ item.label }}</span>
          </router-link>
        </template>
      </nav>
      <t-drawer v-else v-model:visible="drawerOpen" placement="left" size="260px" :header="false">
        <nav class="shell-nav shell-nav--drawer" data-testid="shell-nav-drawer">
          <template v-for="group in navGroups" :key="group.title ?? 'regular'">
            <div v-if="group.title" class="nav-group-title">{{ group.title }}</div>
            <a
              v-for="item in group.items"
              :key="item.name"
              class="nav-item"
              :class="{ active: activeItem === item.name }"
              @click="navigate(item.name)"
            >
              <t-icon class="nav-icon" :name="item.icon" />
              <span class="nav-label">{{ item.label }}</span>
            </a>
          </template>
        </nav>
      </t-drawer>
      <main class="shell-content">
        <RouterView />
      </main>
    </div>
    <footer class="mk-shell-footer">
      <span>MiQroKey Gateway 0.1.0</span>
      <span>catalog v1</span>
      <span>last sync —</span>
    </footer>
  </div>
</template>

<style scoped>
.shell {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  background: var(--miqrokey-bg-canvas);
}

.shell-header {
  display: flex;
  align-items: center;
  gap: var(--miqrokey-space-2);
  height: var(--miqrokey-header-height);
  padding: 0 var(--miqrokey-space-6);
  background: var(--miqrokey-bg-surface);
  border-bottom: 1px solid var(--miqrokey-border-default);
}

.brand {
  display: flex;
  align-items: center;
  gap: var(--miqrokey-space-2);
  font-size: 16px;
  font-weight: 600;
  color: var(--miqrokey-text-primary);
}

.brand-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 8px;
  background: linear-gradient(
    135deg,
    var(--miqrokey-chip-tencent-a),
    var(--miqrokey-chip-tencent-b)
  );
  color: #fff;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.02em;
}

.brand-name {
  font-size: 15px;
}

.version-badge {
  padding: 1px 6px;
  font-size: 11px;
  font-weight: 500;
  color: var(--miqrokey-accent);
  background: var(--miqrokey-accent-soft);
  border-radius: var(--miqrokey-radius-control);
}

.spacer {
  flex: 1;
}

.user-menu {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 8px;
  border-radius: var(--miqrokey-radius-control);
  color: var(--miqrokey-text-secondary);
  font-size: 14px;
  cursor: pointer;
  outline: none;
}

.user-menu:hover {
  background: var(--miqrokey-bg-subtle);
  color: var(--miqrokey-text-primary);
}

.shell-body {
  display: flex;
  flex: 1;
}

.shell-nav {
  width: var(--miqrokey-nav-width);
  padding: var(--miqrokey-space-4) var(--miqrokey-space-2);
  background: var(--miqrokey-bg-surface);
  border-right: 1px solid var(--miqrokey-border-default);
  flex-shrink: 0;
  transition: width 160ms ease;
}

.shell-nav.collapsed {
  width: var(--miqrokey-nav-width-collapsed);
  padding-left: var(--miqrokey-space-1);
  padding-right: var(--miqrokey-space-1);
}

.shell-nav--drawer {
  width: 100%;
  height: 100%;
  border-right: none;
}

.nav-group-title {
  padding: var(--miqrokey-space-2) var(--miqrokey-space-3) 4px;
  font-size: 12px;
  color: var(--miqrokey-text-disabled);
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: var(--miqrokey-space-2);
  padding: 7px var(--miqrokey-space-3);
  margin-bottom: 2px;
  border-radius: var(--miqrokey-radius-control);
  border-left: 2px solid transparent;
  color: var(--miqrokey-text-secondary);
  font-size: 14px;
  text-decoration: none;
  cursor: pointer;
}

.collapsed .nav-item {
  justify-content: center;
  padding-left: 0;
  padding-right: 0;
}

.nav-icon {
  font-size: 16px;
  flex-shrink: 0;
}

.nav-item:hover {
  background: var(--miqrokey-bg-subtle);
  color: var(--miqrokey-text-primary);
}

.nav-item.active {
  background: var(--miqrokey-accent-soft);
  border-left-color: var(--miqrokey-accent);
  color: var(--miqrokey-accent);
  font-weight: 500;
}

.shell-content {
  flex: 1;
  min-width: 0;
  padding: var(--miqrokey-space-6) var(--miqrokey-space-8) var(--miqrokey-space-8);
  max-width: var(--miqrokey-content-max);
}

@media (max-width: 1279px) and (min-width: 768px) {
  .shell-content {
    padding: var(--miqrokey-space-4) var(--miqrokey-space-4) var(--miqrokey-space-8);
  }
}

@media (max-width: 767px) {
  .shell-content {
    padding: var(--miqrokey-space-4) var(--miqrokey-space-3) var(--miqrokey-space-6);
  }
}
</style>
