<script setup lang="ts">
/**
 * UiEmptyState — centered block inviting action. Used by tables (slot
 * override) and standalone panels. Action content goes in the default slot
 * (usually a UiButton); title/description stay props so audits read cleanly.
 */
withDefaults(
  defineProps<{
    title?: string;
    description?: string;
    compact?: boolean;
  }>(),
  {
    title: '暂无数据',
    description: '',
    compact: false,
  },
);
</script>

<template>
  <div class="ui-empty" :class="{ 'ui-empty--compact': compact }">
    <div class="ui-empty__glyph" aria-hidden="true">
      <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
        <rect x="4" y="5" width="16" height="14" rx="2" stroke="currentColor" stroke-width="1.4" />
        <path d="M4 9h16" stroke="currentColor" stroke-width="1.4" />
      </svg>
    </div>
    <p class="ui-empty__title">{{ title }}</p>
    <p v-if="description" class="ui-empty__desc">{{ description }}</p>
    <div v-if="$slots.default" class="ui-empty__action">
      <slot />
    </div>
  </div>
</template>

<style scoped>
.ui-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: var(--ui-space-10) var(--ui-space-6);
}

.ui-empty--compact {
  padding: var(--ui-space-8) var(--ui-space-4);
}

.ui-empty__glyph {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border-radius: var(--ui-radius-control);
  background: var(--ui-muted);
  color: var(--ui-foreground-faint);
  margin-bottom: var(--ui-space-4);
}

.ui-empty__title {
  margin: 0;
  font-size: var(--ui-font-size-base);
  font-weight: var(--ui-weight-medium);
  color: var(--ui-foreground);
}

.ui-empty__desc {
  margin: var(--ui-space-1) 0 0;
  max-width: 420px;
  font-size: var(--ui-font-size-sm);
  line-height: var(--ui-line-height-base);
  color: var(--ui-foreground-secondary);
}

.ui-empty__action {
  margin-top: var(--ui-space-5);
}
</style>
