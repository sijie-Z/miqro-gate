<script setup lang="ts">
/**
 * UiStatusBadge — muted pill with tone dot. Tones map to --ui-<tone>-fg/bg
 * pairs; dots never carry information alone (label always present).
 */
import { computed } from 'vue';

const props = withDefaults(
  defineProps<{
    tone?: 'success' | 'warning' | 'danger' | 'neutral' | 'info';
    label?: string;
    /** Show only the dot (e.g. inside table cells that already have text). */
    dotOnly?: boolean;
  }>(),
  {
    tone: 'neutral',
    label: '',
    dotOnly: false,
  },
);

const classes = computed(() => [`ui-badge`, `ui-badge--${props.tone}`]);
</script>

<template>
  <span :class="classes" data-testid="status-badge">
    <span class="ui-badge__dot" aria-hidden="true" />
    <span v-if="!dotOnly" class="ui-badge__label">{{ label }}</span>
    <slot v-if="!dotOnly && !label" />
  </span>
</template>

<style scoped>
.ui-badge {
  display: inline-flex;
  align-items: center;
  gap: var(--ui-space-2);
  height: 22px;
  padding: 0 var(--ui-space-2);
  border-radius: var(--ui-radius-pill);
  font-size: var(--ui-font-size-xs);
  font-weight: var(--ui-weight-medium);
  line-height: 1;
  white-space: nowrap;
}

.ui-badge__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  flex-shrink: 0;
}

.ui-badge--success {
  color: var(--ui-success-fg);
  background: var(--ui-success-bg);
}

.ui-badge--success .ui-badge__dot {
  background: currentColor;
}

.ui-badge--warning {
  color: var(--ui-warning-fg);
  background: var(--ui-warning-bg);
}

.ui-badge--warning .ui-badge__dot {
  background: currentColor;
}

.ui-badge--danger {
  color: var(--ui-danger-fg);
  background: var(--ui-danger-bg);
}

.ui-badge--danger .ui-badge__dot {
  background: currentColor;
}

.ui-badge--info {
  color: var(--ui-info-fg);
  background: var(--ui-info-bg);
}

.ui-badge--info .ui-badge__dot {
  background: currentColor;
}

.ui-badge--neutral {
  color: var(--ui-neutral-fg);
  background: var(--ui-neutral-bg);
}

.ui-badge--neutral .ui-badge__dot {
  background: currentColor;
}
</style>
