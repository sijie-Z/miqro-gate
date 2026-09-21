<script setup lang="ts">
/**
 * UiButton — v2 design-system button.
 * Variants: primary (solid brand blue) / secondary (white + border) /
 * ghost (text, hover fill) / danger (solid red) / link, link-danger (row
 * operations rendered as visible text links — Tencent/Aliyun console
 * convention; the ink comes from the global .ui-link-action class so plain
 * anchors and radix dropdown triggers can share it). Sizes: sm / md / lg.
 * Renders a native <button>; all extra attrs (data-testid, type, tabindex…)
 * fall through to the element. Native button attrs are merged explicitly so
 * that type="button" is the default (forms never submit accidentally).
 */
import { computed, useAttrs } from 'vue';

const props = withDefaults(
  defineProps<{
    variant?: 'primary' | 'secondary' | 'ghost' | 'danger' | 'link' | 'link-danger';
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

const isLink = computed(() => props.variant === 'link' || props.variant === 'link-danger');

const classes = computed(() => [
  'ui-btn',
  `ui-btn--${props.variant}`,
  `ui-btn--${props.size}`,
  {
    'ui-btn--block': props.block,
    'ui-link-action': isLink.value,
    'ui-link-action--danger': props.variant === 'link-danger',
  },
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
  /* antd v5 buttons are regular weight (v2.pro live: 400 at every size). */
  font-weight: var(--ui-weight-regular);
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
  height: 24px;
  padding: 0 7px;
  font-size: var(--ui-font-size-base);
}

.ui-btn--md {
  height: var(--ui-control-height);
  padding: 0 15px;
  font-size: var(--ui-font-size-base);
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
  /* antd primary-button depth (as on v2.vben.pro) */
  box-shadow: 0 2px 0 rgba(0, 155, 228, 0.11);
}

.ui-btn--primary:hover:not(:disabled) {
  background: var(--ui-primary-hover);
}

.ui-btn--primary:active:not(:disabled) {
  background: var(--ui-primary-active);
}

.ui-btn--secondary {
  background: var(--ui-card);
  border-color: #cececd; /* v2.pro live: antd default border in Vben theme */
  color: #606266; /* v2.pro live: antd default button ink */
  /* antd default-button bottom edge (v2.pro live) */
  box-shadow: 0 2px 0 rgba(0, 0, 0, 0.02);
}

/* antd hover keeps the white fill and tints border + text together. */
.ui-btn--secondary:hover:not(:disabled) {
  border-color: var(--ui-primary-hover);
  color: var(--ui-primary-hover);
}

.ui-btn--secondary:active:not(:disabled) {
  border-color: var(--ui-primary-active);
  color: var(--ui-primary-active);
}

.ui-btn--ghost {
  background: transparent;
  color: var(--ui-foreground-secondary);
}

.ui-btn--ghost:hover:not(:disabled) {
  background: var(--ui-fill-hover);
  color: var(--ui-foreground);
}

.ui-btn--ghost:active:not(:disabled) {
  background: var(--ui-fill-selected);
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
