<script setup lang="ts">
/**
 * UiStatusBadge — status indicator. Two render modes:
 *  - 'dot' (default for table cells): colored dot + tinted text on the bare
 *    row background, no pill chrome — the PostHog console pattern.
 *  - 'pill': soft tinted capsule, used on panels/summary rows.
 * Tones map to --ui-<tone>-fg/bg pairs; the dot never carries information
 * alone (label always present).
 */
import { computed } from 'vue';

const props = withDefaults(
  defineProps<{
    tone?: 'success' | 'warning' | 'danger' | 'neutral' | 'info';
    label?: string;
    variant?: 'pill' | 'dot';
    /** Show only the dot (e.g. inside table cells that already have text). */
    dotOnly?: boolean;
  }>(),
  {
    tone: 'neutral',
    label: '',
    variant: 'dot',
    dotOnly: false,
  },
);

const classes = computed(() => [
  'ui-badge',
  `ui-badge--${props.variant}`,
  `ui-badge--${props.tone}`,
]);
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
  font-size: var(--ui-font-size-xs);
  font-weight: var(--ui-weight-medium);
  white-space: nowrap;
}

.ui-badge__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  flex-shrink: 0;
}

/* dot variant — bare tinted text, no capsule */
.ui-badge--dot {
  gap: 7px;
  line-height: 1;
}

.ui-badge--dot .ui-badge__dot {
  width: 7px;
  height: 7px;
}

/* pill variant — soft tinted capsule */
.ui-badge--pill {
  gap: var(--ui-space-2);
  height: 22px;
  padding: 0 var(--ui-space-2);
  border-radius: var(--ui-radius-pill);
  line-height: 1;
}

.ui-badge--success {
  color: var(--ui-success-fg);
}

.ui-badge--success .ui-badge__dot {
  background: var(--ui-success-fg);
}

.ui-badge--pill.ui-badge--success {
  background: var(--ui-success-bg);
}

.ui-badge--warning {
  color: var(--ui-warning-fg);
}

.ui-badge--warning .ui-badge__dot {
  background: var(--ui-warning-fg);
}

.ui-badge--pill.ui-badge--warning {
  background: var(--ui-warning-bg);
}

.ui-badge--danger {
  color: var(--ui-danger-fg);
}

.ui-badge--danger .ui-badge__dot {
  background: var(--ui-danger-fg);
}

.ui-badge--pill.ui-badge--danger {
  background: var(--ui-danger-bg);
}

.ui-badge--info {
  color: var(--ui-info-fg);
}

.ui-badge--info .ui-badge__dot {
  background: var(--ui-info-fg);
}

.ui-badge--pill.ui-badge--info {
  background: var(--ui-info-bg);
}

.ui-badge--neutral {
  color: var(--ui-neutral-fg);
}

.ui-badge--neutral .ui-badge__dot {
  background: var(--ui-neutral-fg);
}

.ui-badge--pill.ui-badge--neutral {
  background: var(--ui-neutral-bg);
}
</style>
