<script setup lang="ts">
/**
 * UiCheckbox — v2 design-system checkbox (antd v5 geometry on the Vben
 * palette): 16px box, 4px radius, #d9d9d9 hairline, #0960bd when checked,
 * 8px label gap, 14px text. Two modes:
 *  - boolean: v-model is `true`/`false` (single toggle)
 *  - group:   v-model is an array, each checkbox carries its own `value`
 * `checked` overrides the computed state for fully-controlled lists that
 * toggle through a central handler.
 */
import { computed, useAttrs } from 'vue';

const props = withDefaults(
  defineProps<{
    modelValue?: boolean | string[] | Set<string>;
    /** Group-mode value carried by this checkbox. */
    value?: string;
    /** Controlled override; when set, modelValue is ignored for display. */
    checked?: boolean;
    label?: string;
    disabled?: boolean;
  }>(),
  {
    modelValue: false,
    value: '',
    checked: undefined,
    label: '',
    disabled: false,
  },
);

const emit = defineEmits<{
  'update:modelValue': [value: boolean | string[] | Set<string>];
  change: [value: boolean | string[] | Set<string>];
}>();

defineOptions({ inheritAttrs: false });

const attrs = useAttrs();

const isChecked = computed(() => {
  if (props.checked !== undefined) return props.checked;
  const mv = props.modelValue;
  if (mv instanceof Set) return mv.has(props.value);
  if (Array.isArray(mv)) return mv.includes(props.value);
  return Boolean(mv);
});

function onChange(event: Event) {
  if (props.disabled) return;
  const next = (event.target as HTMLInputElement).checked;
  const mv = props.modelValue;
  let emitted: boolean | string[] | Set<string> = next;
  if (mv instanceof Set) {
    // Vue's native checkbox v-model mutates Sets in place — keep that contract.
    if (next) mv.add(props.value);
    else mv.delete(props.value);
    emitted = mv;
  } else if (Array.isArray(mv)) {
    const set = new Set(mv);
    if (next) set.add(props.value);
    else set.delete(props.value);
    emitted = [...set];
  }
  emit('update:modelValue', emitted);
  emit('change', emitted);
}
</script>

<template>
  <label class="ui-check" :class="{ 'ui-check--disabled': disabled }">
    <input
      class="ui-check__input"
      type="checkbox"
      :value="value"
      :checked="isChecked"
      :disabled="disabled"
      v-bind="attrs"
      @change="onChange"
    />
    <span class="ui-check__box" aria-hidden="true">
      <svg width="10" height="10" viewBox="0 0 12 12" fill="none">
        <path
          d="M2.2 6.4 4.8 9 9.8 3.4"
          stroke="currentColor"
          stroke-width="1.8"
          stroke-linecap="round"
          stroke-linejoin="round"
        />
      </svg>
    </span>
    <span v-if="label || $slots.default" class="ui-check__label"
      ><slot>{{ label }}</slot></span
    >
  </label>
</template>

<style scoped>
.ui-check {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: var(--ui-space-2);
  font-size: var(--ui-font-size-base);
  line-height: 22px;
  color: var(--ui-foreground);
  cursor: pointer;
  user-select: none;
}

.ui-check--disabled {
  color: var(--ui-foreground-faint);
  cursor: not-allowed;
}

/* The hidden input covers the whole control so every click lands on it
   directly — one native toggle, no label-forwarding double activations. */
.ui-check__input {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  margin: 0;
  opacity: 0;
  cursor: inherit;
}

.ui-check__box {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  border: 1px solid var(--ui-input-border);
  border-radius: 4px;
  background: var(--ui-card);
  color: var(--ui-foreground-inverse);
  flex-shrink: 0;
  transition:
    background-color var(--ui-ease),
    border-color var(--ui-ease),
    box-shadow var(--ui-ease);
}

.ui-check__box svg {
  opacity: 0;
  transition: opacity var(--ui-ease);
}

.ui-check:hover .ui-check__box {
  border-color: var(--ui-primary-hover);
}

.ui-check__input:checked + .ui-check__box {
  background: var(--ui-primary);
  border-color: var(--ui-primary);
}

.ui-check__input:checked + .ui-check__box svg {
  opacity: 1;
}

.ui-check__input:checked:hover + .ui-check__box {
  background: var(--ui-primary-hover);
  border-color: var(--ui-primary-hover);
}

.ui-check__input:focus-visible + .ui-check__box {
  box-shadow: var(--ui-shadow-focus);
}

.ui-check__input:disabled + .ui-check__box {
  background: var(--ui-muted);
  border-color: var(--ui-input-border);
}

.ui-check__input:disabled:checked + .ui-check__box {
  background: var(--ui-border-strong);
  border-color: var(--ui-border-strong);
}

.ui-check__label {
  min-width: 0;
}
</style>
