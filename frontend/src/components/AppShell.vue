<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessageBox } from 'element-plus';
import { useAuthStore } from '@/stores/auth';
import {
  Bell,
  DataAnalysis,
  Delete,
  Download,
  Key,
  Lock,
  Odometer,
  SwitchButton,
  User,
  UserFilled,
  Wallet,
  Warning,
} from '@element-plus/icons-vue';

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();

const isAdmin = computed(() => auth.user?.role === 'SYSTEM_ADMIN');

interface NavItem {
  name: string;
  label: string;
  icon: unknown;
}

const regularNav: NavItem[] = [
  { name: 'usage', label: 'Usage', icon: DataAnalysis },
  { name: 'keys', label: 'Virtual Keys', icon: Key },
  { name: 'profile', label: 'Profile', icon: User },
];

const adminNav: NavItem[] = [
  { name: 'admin-usage', label: 'Usage', icon: DataAnalysis },
  { name: 'providers', label: 'Providers', icon: Wallet },
  { name: 'plans', label: 'Plans', icon: Odometer },
  { name: 'credentials', label: 'Credentials', icon: Lock },
  { name: 'grants', label: 'Grants', icon: Key },
  { name: 'exports', label: 'Exports', icon: Download },
  { name: 'deletions', label: 'Deletions', icon: Delete },
  { name: 'webhooks', label: 'Webhooks', icon: Bell },
  { name: 'alert-rules', label: 'Alert Rules', icon: Warning },
  { name: 'audit', label: 'Audit', icon: UserFilled },
];

const navGroups = computed(() => {
  const groups: { title?: string; items: NavItem[] }[] = [{ items: regularNav }];
  if (isAdmin.value) {
    groups.push({ title: '管理', items: adminNav });
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
    await ElMessageBox.confirm('退出后需要重新登录。', '退出登录', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'warning',
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
      <el-button
        v-if="mobile"
        link
        class="menu-toggle"
        data-testid="nav-toggle"
        @click="drawerOpen = true"
      >
        ☰
      </el-button>
      <div class="brand">MiQroKey</div>
      <div class="spacer" />
      <el-dropdown trigger="click">
        <span class="user-menu" data-testid="user-menu">
          {{ auth.user?.displayName ?? auth.user?.username }}
          <el-icon><SwitchButton /></el-icon>
        </span>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item disabled>{{ auth.user?.role }}</el-dropdown-item>
            <el-dropdown-item divided data-testid="logout" @click="handleLogout"
              >退出登录</el-dropdown-item
            >
          </el-dropdown-menu>
        </template>
      </el-dropdown>
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
            <el-icon class="nav-icon"><component :is="item.icon" /></el-icon>
            <span v-if="!collapsed" class="nav-label">{{ item.label }}</span>
          </router-link>
        </template>
      </nav>
      <el-drawer v-else v-model="drawerOpen" direction="ltr" size="260px" :with-header="false">
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
              <el-icon class="nav-icon"><component :is="item.icon" /></el-icon>
              <span class="nav-label">{{ item.label }}</span>
            </a>
          </template>
        </nav>
      </el-drawer>
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
  font-size: 16px;
  font-weight: 600;
  color: var(--miqrokey-text-primary);
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
  background: var(--miqrokey-bg-subtle);
  border-left-color: var(--miqrokey-accent);
  color: var(--miqrokey-text-primary);
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
