<script setup lang="ts">
/**
 * NextUnavailableView (#583) — shown when the session could not be restored
 * because the backend was unreachable (network blip, control-plane restart),
 * as opposed to a definitive logout. Retrying re-runs the session restore
 * without forcing a fresh login; the server-side session is usually intact.
 */
import { ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/stores/auth';
import { UiButton } from '@/ui';

const auth = useAuthStore();
const route = useRoute();
const router = useRouter();
const retrying = ref(false);

/** Only same-origin paths may come back through the redirect query. */
function redirectTarget(): string {
  const target = route.query.redirect;
  return typeof target === 'string' && target.startsWith('/') && !target.startsWith('//')
    ? target
    : '/app/overview';
}

async function retry(): Promise<void> {
  retrying.value = true;
  try {
    await auth.fetchMe();
  } finally {
    retrying.value = false;
  }
  if (auth.isAuthenticated) {
    await router.replace(redirectTarget());
  } else if (!auth.serviceUnavailable) {
    // The backend answered and the session is really gone → the login page is
    // now the honest destination.
    await router.replace({ name: 'login', query: { redirect: redirectTarget() } });
  }
  // Still unavailable: stay on this screen so the user can retry again.
}
</script>

<template>
  <div class="unavailable" data-testid="unavailable-page">
    <div class="unavailable__card">
      <h1 class="unavailable__title">服务暂时不可用</h1>
      <p class="unavailable__desc">
        无法连接到 MiQroGate
        服务——可能是后端正在重启或网络抖动。你的登录状态可能仍然有效：服务恢复后点「重试」即可继续，无需重新登录。
      </p>
      <UiButton
        variant="primary"
        :loading="retrying"
        data-testid="unavailable-retry"
        @click="retry"
      >
        重试
      </UiButton>
    </div>
  </div>
</template>

<style scoped>
.unavailable {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--ui-space-6);
  background: var(--ui-muted);
}

.unavailable__card {
  max-width: 460px;
  padding: var(--ui-space-8);
  background: var(--ui-card);
  border: 1px solid var(--ui-border);
  border-radius: var(--ui-radius-panel);
  box-shadow: var(--ui-shadow-card);
  text-align: center;
}

.unavailable__title {
  margin: 0 0 var(--ui-space-3);
  font-size: var(--ui-font-size-xl);
  font-weight: var(--ui-weight-semibold);
  color: var(--ui-foreground);
}

.unavailable__desc {
  margin: 0 0 var(--ui-space-5);
  font-size: var(--ui-font-size-sm);
  line-height: var(--ui-line-height-lg);
  color: var(--ui-foreground-secondary);
}
</style>
