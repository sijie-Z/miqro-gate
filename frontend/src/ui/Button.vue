<script setup lang="ts">
/**
 * UiButton — v2 design-system button.
 * Variants: primary (solid brand blue) / secondary (white + border) /
 * ghost (text, hover fill) / danger (solid red). Sizes: sm / md / lg.
 * Renders a native <button>; all extra attrs (data-testid, type, tabindex…)
 * fall through to the element. Native button attrs are merged explicitly so
 * that type="button" is the default (forms never submit accidentally).
 */
import { computed, useAttrs } from 'vue';

const props = withDefaults(
  defineProps<{
    variant?: 'primary' | 'secondary' | 'ghost' | 'danger';
    size?: 'sm' | 'md' | 'lg';
    loading?: boolean;
    disabled?: boolean;
    block?: boolean;
    nativeType?: 'button' | 'submit' | 'reset';
  }>(),
  {
    variant: 'secondary',
    size: 'md',
    loading: false,
    disabled: false,
    block: false,
    nativeType: 'button',
  },
);

defineOptions({ inheritAttrs: false });

const attrs = useAttrs();

const classes = computed(() => [
  'ui-btn',
  `ui-btn--${props.variant}`,
  `ui-btn--${props.size}`,
  { 'ui-btn--block': props.block },
]);

const disabledState = computed(() => props.disabled || props.loading);

function onClick(event: MouseEvent) {
  if (disabledState.value) {
    event.preventDefault();
    event.stopPropagation();
  }
}
</script>

<template>
  <button
    :class="classes"
    :type="nativeType"
    :disabled="disabledState"
    :aria-busy="loading || undefined"
    v-bind="attrs"
    @click="onClick"
  >
    <span v-if="loading" class="ui-btn__spinner" aria-hidden="true" />
    <slot />
  </button>
</template>

<style scoped>
.ui-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--ui-space-2);
  border-radius: var(--ui-radius-control);
  border: 1px solid transparent;
  font-family: inherit;
  font-weight: var(--ui-weight-medium);
  line-height: 1;
  cursor: pointer;
  user-select: none;
  white-space: nowrap;
  transition:
    background-color var(--ui-ease),
    border-color var(--ui-ease),
    color var(--ui-ease),
    box-shadow var(--ui-ease);
}

.ui-btn:focus-visible {
  outline: none;
  box-shadow: var(--ui-shadow-focus);
}

.ui-btn:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

.ui-btn--sm {
  height: 28px;
  padding: 0 var(--ui-space-3);
  font-size: var(--ui-font-size-xs);
}

.ui-btn--md {
  height: var(--ui-control-height);
  padding: 0 var(--ui-space-4);
  font-size: var(--ui-font-size-sm);
}

.ui-btn--lg {
  height: var(--ui-control-height-lg);
  padding: 0 var(--ui-space-5);
  font-size: var(--ui-font-size-base);
}

.ui-btn--block {
  width: 100%;
}

.ui-btn--primary {
  background: var(--ui-primary);
  color: var(--ui-foreground-inverse);
}

.ui-btn--primary:hover:not(:disabled) {
  background: var(--ui-primary-hover);
}

.ui-btn--primary:active:not(:disabled) {
  background: var(--ui-primary-active);
}

.ui-btn--secondary {
  background: var(--ui-card);
  border-color: var(--ui-input-border);
  color: var(--ui-foreground);
}

.ui-btn--secondary:hover:not(:disabled) {
  background: var(--ui-muted);
}

.ui-btn--ghost {
  background: transparent;
  color: var(--ui-foreground-secondary);
}

.ui-btn--ghost:hover:not(:disabled) {
  background: var(--ui-fill-hover);
  color: var(--ui-foreground);
}

.ui-btn--danger {
  background: var(--ui-danger-fg);
  color: var(--ui-foreground-inverse);
}

.ui-btn--danger:hover:not(:disabled) {
  background: #9e0f1f;
}

.ui-btn__spinner {
  width: 12px;
  height: 12px;
  border: 2px solid currentColor;
  border-right-color: transparent;
  border-radius: 50%;
  animation: ui-btn-spin 0.7s linear infinite;
  opacity: 0.85;
}

@keyframes ui-btn-spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
