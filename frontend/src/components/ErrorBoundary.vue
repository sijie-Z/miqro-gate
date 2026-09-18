<script setup lang="ts">
/**
 * ErrorBoundary (#833): a render-time crash anywhere below used to leave a
 * blank content area with no way out — the only recovery paths were the
 * post-deploy chunk reload and the service-unavailable screen. This boundary
 * captures the error, stops it from unmounting the app, and offers retry /
 * reload / back-to-overview. Resets automatically on navigation, so moving to
 * another page clears the failure state.
 */
import { onErrorCaptured, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { UiButton } from '@/ui';

const error = ref<Error | null>(null);
const route = useRoute();
const router = useRouter();

onErrorCaptured((err) => {
  error.value = err instanceof Error ? err : new Error(String(err));
  console.error('[error-boundary]', err);
  return false; // swallowed here; the fallback below takes over
});

// Navigating away clears the failure so the next page renders normally.
watch(
  () => route.fullPath,
  () => {
    error.value = null;
  },
);

function retry() {
  error.value = null;
}

function reload() {
  window.location.reload();
}

function goOverview() {
  error.value = null;
  void router.push({ name: 'overview' });
}
</script>

<template>
  <div v-if="error" class="error-boundary" data-testid="error-boundary">
    <div class="ui-panel error-boundary__card">
      <h2 class="error-boundary__title">页面出错了</h2>
      <p class="error-boundary__desc">
        页面渲染时遇到问题。可以先重试；若反复出现，请把下方信息反馈给管理员。
      </p>
      <pre class="error-boundary__detail ui-mono">{{ error.message }}</pre>
      <div class="error-boundary__actions">
        <UiButton variant="primary" data-testid="error-retry" @click="retry">重试</UiButton>
        <UiButton variant="secondary" data-testid="error-reload" @click="reload"
          >重新加载</UiButton
        >
        <UiButton variant="ghost" data-testid="error-overview" @click="goOverview"
          >返回总览</UiButton
        >
      </div>
    </div>
  </div>
  <slot v-else />
</template>

<style scoped>
.error-boundary {
  display: flex;
  justify-content: center;
  padding: var(--ui-space-12) var(--ui-space-4);
}

.error-boundary__card {
  max-width: 560px;
  width: 100%;
  padding: var(--ui-space-6);
  text-align: center;
}

.error-boundary__title {
  margin: 0;
  font-size: var(--ui-font-size-lg);
  font-weight: var(--ui-weight-semibold);
  color: var(--ui-foreground);
}

.error-boundary__desc {
  margin: var(--ui-space-2) 0 0;
  font-size: var(--ui-font-size-sm);
  line-height: var(--ui-line-height-base);
  color: var(--ui-foreground-secondary);
}

.error-boundary__detail {
  margin: var(--ui-space-4) 0 0;
  padding: var(--ui-space-3);
  border-radius: var(--ui-radius-control);
  background: var(--ui-muted);
  color: var(--ui-foreground-faint);
  font-size: 12px;
  text-align: left;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 160px;
  overflow: auto;
}

.error-boundary__actions {
  display: flex;
  justify-content: center;
  gap: var(--ui-space-2);
  margin-top: var(--ui-space-5);
  flex-wrap: wrap;
}
</style>
