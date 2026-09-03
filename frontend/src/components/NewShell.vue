<script setup lang="ts">
/**
 * NewShell — v2 console chrome for the /app-new/* pilot (U0).
 * PostHog-style rail: white sidebar with hairline divider over warm canvas,
 * grouped nav, user chip pinned at the bottom. Routes outside the pilot
 * keep the legacy /app console — a subtle link returns to it.
 */
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/stores/auth';

const auth = useAuthStore();
const route = useRoute();
const router = useRouter();

const isAdmin = computed(() => auth.user?.role === 'SYSTEM_ADMIN');

const navGroups = computed(() => [
  {
    title: '常规',
    items: [
      { label: '我的 Key', to: '/app-new/keys', icon: 'key' },
      { label: '用量', to: '/app-new/usage', icon: 'chart' },
    ],
  },
  ...(isAdmin.value
    ? [
        {
          title: '管理',
          items: [{ label: '用户', to: '/app-new/users', icon: 'users' }],
        },
      ]
    : []),
]);

const userInitial = computed(() => {
  const name = auth.user?.username ?? '?';
  return name.slice(0, 1).toUpperCase();
});

async function handleLogout() {
  await auth.logout();
  await router.push({ name: 'login' });
}
</script>

<template>
  <div class="new-shell">
    <aside class="new-shell__rail">
      <div class="new-shell__brand">
        <span class="new-shell__brand-mark">M</span>
        <span class="new-shell__brand-name">MiQroGate</span>
        <span class="new-shell__brand-badge">试点</span>
      </div>

      <nav class="new-shell__nav" aria-label="主导航">
        <div v-for="group in navGroups" :key="group.title" class="new-shell__group">
          <p class="new-shell__group-title">{{ group.title }}</p>
          <router-link
            v-for="item in group.items"
            :key="item.to"
            :to="item.to"
            class="new-shell__nav-item"
            :class="{ 'new-shell__nav-item--active': route.path === item.to }"
          >
            <svg
              class="new-shell__nav-icon"
              width="16"
              height="16"
              viewBox="0 0 24 24"
              fill="none"
              aria-hidden="true"
            >
              <path
                v-if="item.icon === 'key'"
                d="M14.5 6.5a4 4 0 1 1-1.2 2.9m1.2-2.9 4.5 4.5m-6.4 6.4 1.9-1.9m-1.9 1.9-1.6 1.6H7l-1-1.9V14l2-2h2.3l1.2-1.2m2.9-3.2.3-.3"
                stroke="currentColor"
                stroke-width="1.6"
                stroke-linecap="round"
                stroke-linejoin="round"
              />
              <path
                v-else-if="item.icon === 'chart'"
                d="M4 20h16M6 16v-5m4 5V6m4 10v-8m4 8V9"
                stroke="currentColor"
                stroke-width="1.6"
                stroke-linecap="round"
              />
              <path
                v-else-if="item.icon === 'users'"
                d="M9 11a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7Zm0 2c-3.9 0-6 2.2-6 5v1h12v-1c0-2.8-2.1-5-6-5Zm7-1.2a3 3 0 0 0 0-5.6M20 19v-1c0-2.3-1.5-4-4-4.5"
                stroke="currentColor"
                stroke-width="1.6"
                stroke-linecap="round"
                stroke-linejoin="round"
              />
            </svg>
            <span class="new-shell__nav-label">{{ item.label }}</span>
          </router-link>
        </div>
      </nav>

      <div class="new-shell__foot">
        <router-link to="/app/keys" class="new-shell__legacy-link" title="返回旧版控制台">
          旧版控制台 →
        </router-link>
        <div class="new-shell__user">
          <span class="new-shell__user-avatar" aria-hidden="true">{{ userInitial }}</span>
          <div class="new-shell__user-meta">
            <span class="new-shell__user-name">{{ auth.user?.username }}</span>
            <span class="new-shell__user-role">{{
              auth.user?.role === 'SYSTEM_ADMIN' ? '系统管理员' : '用户'
            }}</span>
          </div>
          <button
            type="button"
            class="new-shell__logout"
            aria-label="退出登录"
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
      </div>
    </aside>

    <main class="new-shell__main">
      <RouterView />
    </main>
  </div>
</template>

<style scoped>
.new-shell {
  display: flex;
  min-height: 100vh;
  background: var(--ui-background);
  color: var(--ui-foreground);
}

.new-shell__rail {
  display: flex;
  flex-direction: column;
  width: var(--ui-sidebar-width);
  flex-shrink: 0;
  background: var(--ui-card);
  border-right: 1px solid var(--ui-border);
}

.new-shell__brand {
  display: flex;
  align-items: center;
  gap: var(--ui-space-2);
  height: var(--ui-header-height);
  padding: 0 var(--ui-space-5);
  border-bottom: 1px solid var(--ui-border-muted);
}

.new-shell__brand-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: var(--ui-radius-control);
  background: var(--ui-primary);
  color: #fff;
  font-size: 13px;
  font-weight: 700;
}

.new-shell__brand-name {
  font-size: var(--ui-font-size-base);
  font-weight: var(--ui-weight-semibold);
  letter-spacing: -0.01em;
}

.new-shell__brand-badge {
  margin-left: auto;
  font-size: 10px;
  font-weight: var(--ui-weight-medium);
  color: var(--ui-primary);
  background: var(--ui-primary-soft);
  border-radius: var(--ui-radius-pill);
  padding: 2px 8px;
}

.new-shell__nav {
  flex: 1;
  padding: var(--ui-space-4) var(--ui-space-3);
  overflow-y: auto;
}

.new-shell__group {
  margin-bottom: var(--ui-space-5);
}

.new-shell__group-title {
  margin: 0 0 var(--ui-space-1);
  padding: 0 var(--ui-space-2);
  font-size: 11px;
  font-weight: var(--ui-weight-semibold);
  letter-spacing: 0.04em;
  text-transform: uppercase;
  color: var(--ui-foreground-faint);
}

.new-shell__nav-item {
  display: flex;
  align-items: center;
  gap: var(--ui-space-2);
  height: 32px;
  padding: 0 var(--ui-space-2);
  border-radius: var(--ui-radius-control);
  color: var(--ui-foreground-secondary);
  font-size: var(--ui-font-size-sm);
  text-decoration: none;
  transition:
    background-color var(--ui-ease),
    color var(--ui-ease);
}

.new-shell__nav-item:hover {
  background: var(--ui-fill-hover);
  color: var(--ui-foreground);
}

.new-shell__nav-item--active {
  background: var(--ui-primary-soft);
  color: var(--ui-primary);
  font-weight: var(--ui-weight-medium);
}

.new-shell__nav-item--active .new-shell__nav-icon {
  color: var(--ui-primary);
}

.new-shell__nav-icon {
  color: var(--ui-foreground-faint);
  flex-shrink: 0;
}

.new-shell__foot {
  border-top: 1px solid var(--ui-border-muted);
  padding: var(--ui-space-3);
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-2);
}

.new-shell__legacy-link {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-faint);
  text-decoration: none;
  padding: 0 var(--ui-space-2);
}

.new-shell__legacy-link:hover {
  color: var(--ui-primary);
}

.new-shell__user {
  display: flex;
  align-items: center;
  gap: var(--ui-space-2);
  padding: var(--ui-space-2);
  border-radius: var(--ui-radius-control);
}

.new-shell__user-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border-radius: var(--ui-radius-control);
  background: var(--ui-muted);
  color: var(--ui-foreground-secondary);
  font-size: var(--ui-font-size-xs);
  font-weight: var(--ui-weight-semibold);
  flex-shrink: 0;
}

.new-shell__user-meta {
  display: flex;
  flex-direction: column;
  min-width: 0;
  flex: 1;
}

.new-shell__user-name {
  font-size: var(--ui-font-size-sm);
  font-weight: var(--ui-weight-medium);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.new-shell__user-role {
  font-size: 11px;
  color: var(--ui-foreground-faint);
}

.new-shell__logout {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border: none;
  border-radius: var(--ui-radius-control);
  background: transparent;
  color: var(--ui-foreground-faint);
  cursor: pointer;
  flex-shrink: 0;
}

.new-shell__logout:hover {
  background: var(--ui-fill-hover);
  color: var(--ui-danger-fg);
}

.new-shell__logout:focus-visible {
  outline: none;
  box-shadow: var(--ui-shadow-focus);
}

.new-shell__main {
  flex: 1;
  min-width: 0;
}
</style>
