<script setup lang="ts">
/**
 * UsageCaliberTip — quiet, dismissible note explaining the usage-numbering
 * window (vendor research fact: provider consoles update ~T+1 while this
 * gateway meters every call immediately). One-time dismiss per browser.
 */
import { onMounted, ref } from 'vue';

const STORAGE_KEY = 'miqrokey.usage-caliber-tip-dismissed';
const visible = ref(true);

onMounted(() => {
  try {
    if (localStorage.getItem(STORAGE_KEY) === '1') visible.value = false;
  } catch {
    // storage unavailable — keep showing
  }
});

function dismiss() {
  visible.value = false;
  try {
    localStorage.setItem(STORAGE_KEY, '1');
  } catch {
    // non-fatal
  }
}
</script>

<template>
  <div v-if="visible" class="ui-caliber-tip" data-testid="usage-caliber-tip" role="note">
    <svg class="ui-caliber-tip__icon" width="15" height="15" viewBox="0 0 16 16" fill="none" aria-hidden="true">
      <circle cx="8" cy="8" r="6.5" stroke="currentColor" stroke-width="1.4" />
      <path d="M8 7.4v3.4M8 5.2v.1" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
    </svg>
    <p class="ui-caliber-tip__text">
      本地用量按 Key 即时记账；供应商官方控制台/账单约 T+1 更新——对账看到数字差异属正常窗口，以本页明细为准。
    </p>
    <button
      type="button"
      class="ui-caliber-tip__close"
      aria-label="关闭提示"
      data-testid="usage-caliber-dismiss"
      @click="dismiss"
    >
      <svg width="12" height="12" viewBox="0 0 16 16" fill="none" aria-hidden="true">
        <path d="m4 4 8 8m0-8-8 8" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
      </svg>
    </button>
  </div>
</template>

<style scoped>
.ui-caliber-tip {
  display: flex;
  align-items: flex-start;
  gap: var(--ui-space-2);
  padding: var(--ui-space-3) var(--ui-space-4);
  margin-bottom: var(--ui-space-4);
  border: 1px solid var(--ui-info-bg);
  border-radius: var(--ui-radius-control);
  background: var(--ui-info-bg);
  color: var(--ui-foreground-secondary);
}

.ui-caliber-tip__icon {
  flex-shrink: 0;
  margin-top: 1px;
  color: var(--ui-info-fg);
}

.ui-caliber-tip__text {
  flex: 1;
  margin: 0;
  font-size: var(--ui-font-size-sm);
  line-height: var(--ui-line-height-base);
}

.ui-caliber-tip__close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border: none;
  border-radius: var(--ui-radius-control);
  background: transparent;
  color: var(--ui-foreground-faint);
  cursor: pointer;
}

.ui-caliber-tip__close:hover {
  background: rgba(22, 119, 255, 0.12);
  color: var(--ui-foreground);
}

.ui-caliber-tip__close:focus-visible {
  outline: none;
  box-shadow: var(--ui-shadow-focus);
}
</style>
