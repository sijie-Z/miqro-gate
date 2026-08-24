<script setup lang="ts">
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessageBox } from 'element-plus';
import { useAuthStore } from '@/stores/auth';

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();

const navItems = [
  { name: 'keys', label: 'Virtual Keys' },
  { name: 'usage', label: 'Usage' },
  { name: 'profile', label: 'Profile' },
];

const activeItem = computed(() => route.name);

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
      <div class="brand">MiQroKey</div>
      <div class="spacer" />
      <el-button link type="primary" class="logout" data-testid="logout" @click="handleLogout">
        退出登录
      </el-button>
    </header>
    <div class="shell-body">
      <nav class="shell-nav">
        <router-link
          v-for="item in navItems"
          :key="item.name"
          :to="{ name: item.name }"
          class="nav-item"
          :class="{ active: activeItem === item.name }"
        >
          {{ item.label }}
        </router-link>
      </nav>
      <main class="shell-content">
        <RouterView />
      </main>
    </div>
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
  height: var(--miqrokey-header-height);
  padding: 0 24px;
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

.shell-body {
  display: flex;
  flex: 1;
}

.shell-nav {
  width: var(--miqrokey-nav-width);
  padding: 16px 8px;
  background: var(--miqrokey-bg-surface);
  border-right: 1px solid var(--miqrokey-border-default);
  flex-shrink: 0;
}

.nav-item {
  display: block;
  padding: 8px 12px;
  margin-bottom: 4px;
  border-radius: var(--miqrokey-radius-control);
  border-left: 2px solid transparent;
  color: var(--miqrokey-text-secondary);
  font-size: 14px;
  text-decoration: none;
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
  padding: 24px 32px 48px;
  max-width: var(--miqrokey-content-max);
}
</style>
