<script setup lang="ts">
/**
 * UiStatusBadge — status indicator. Two render modes:
 *  - 'pill' (default, matches the Vben/antd console language): rectangle tag
 *    with tinted fill, coloured text and a hairline border.
 *  - 'dot': bare coloured dot + tinted text on the row background (denser
 *    alternative for tight lists).
 * The label is always present; colour never carries the meaning alone.
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
    variant: 'pill',
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

/* pill variant — Vben/antd tag geometry: rectangle, hairline border */
.ui-badge--pill {
  gap: 6px;
  height: 22px;
  padding: 0 7px;
  border-radius: 4px;
  border: 1px solid transparent;
  line-height: 1;
}

.ui-badge--dot.ui-badge--success {
  color: var(--ui-success-fg);
}

.ui-badge--dot.ui-badge--warning {
  color: var(--ui-warning-fg);
}

.ui-badge--dot.ui-badge--danger {
  color: var(--ui-danger-fg);
}

.ui-badge--dot.ui-badge--info {
  color: var(--ui-info-fg);
}

.ui-badge--dot.ui-badge--neutral {
  color: var(--ui-neutral-fg);
}

.ui-badge--success .ui-badge__dot {
  background: var(--ui-success-fg);
}

.ui-badge--warning .ui-badge__dot {
  background: var(--ui-warning-fg);
}

.ui-badge--danger .ui-badge__dot {
  background: var(--ui-danger-fg);
}

.ui-badge--info .ui-badge__dot {
  background: var(--ui-info-fg);
}

.ui-badge--neutral .ui-badge__dot {
  background: var(--ui-neutral-fg);
}

/* Pill/tag variant: Vben/antd tag — tinted fill, coloured text, hairline
   border mixed from the tone colour. */
.ui-badge--pill.ui-badge--success {
  background: var(--ui-success-bg);
  color: var(--ui-success-fg);
  border-color: color-mix(in srgb, var(--ui-success-fg) 28%, white);
}

.ui-badge--pill.ui-badge--warning {
  background: var(--ui-warning-bg);
  color: var(--ui-warning-fg);
  border-color: color-mix(in srgb, var(--ui-warning-fg) 28%, white);
}

.ui-badge--pill.ui-badge--danger {
  background: var(--ui-danger-bg);
  color: var(--ui-danger-fg);
  border-color: color-mix(in srgb, var(--ui-danger-fg) 28%, white);
}

.ui-badge--pill.ui-badge--info {
  background: var(--ui-info-bg);
  color: var(--ui-info-fg);
  border-color: color-mix(in srgb, var(--ui-info-fg) 28%, white);
}

.ui-badge--pill.ui-badge--neutral {
  background: var(--ui-neutral-bg);
  color: var(--ui-neutral-fg);
  border-color: color-mix(in srgb, var(--ui-neutral-fg) 28%, white);
}

.ui-badge--pill .ui-badge__dot {
  display: none;
}
</style>
