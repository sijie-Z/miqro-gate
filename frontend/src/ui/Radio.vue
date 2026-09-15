<script setup lang="ts">
/**
 * UiRadio — v2 design-system radio (antd v5 geometry on the Vben palette):
 * 16px circle, #d9d9d9 hairline, checked = #0960bd border + 8px #0960bd dot
 * on white, 8px label gap, 14px text. Group usage: shared v-model string,
 * each radio carries its own `value`.
 */
import { computed, useAttrs } from 'vue';

const props = withDefaults(
  defineProps<{
    modelValue?: string;
    /** Value this radio writes into the group model. */
    value: string;
    label?: string;
    disabled?: boolean;
  }>(),
  {
    modelValue: '',
    label: '',
    disabled: false,
  },
);

const emit = defineEmits<{
  'update:modelValue': [value: string];
  change: [value: string];
}>();

defineOptions({ inheritAttrs: false });

const attrs = useAttrs();

const isChecked = computed(() => props.modelValue === props.value);

function onChange(event: Event) {
  if (props.disabled) return;
  if ((event.target as HTMLInputElement).checked) {
    emit('update:modelValue', props.value);
    emit('change', props.value);
  }
}
</script>

<template>
  <label class="ui-radio" :class="{ 'ui-radio--disabled': disabled }">
    <input
      class="ui-radio__input"
      type="radio"
      :value="value"
      :checked="isChecked"
      :disabled="disabled"
      v-bind="attrs"
      @change="onChange"
    />
    <span class="ui-radio__dot" aria-hidden="true" />
    <span v-if="label || $slots.default" class="ui-radio__label"><slot>{{ label }}</slot></span>
  </label>
</template>

<style scoped>
.ui-radio {
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

.ui-radio--disabled {
  color: var(--ui-foreground-faint);
  cursor: not-allowed;
}

/* The hidden input covers the whole control so every click lands on it
   directly — one native toggle, no label-forwarding double activations. */
.ui-radio__input {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  margin: 0;
  opacity: 0;
  cursor: inherit;
}

.ui-radio__dot {
  position: relative;
  display: inline-block;
  width: 16px;
  height: 16px;
  border: 1px solid var(--ui-input-border);
  border-radius: 50%;
  background: var(--ui-card);
  flex-shrink: 0;
  transition:
    background-color var(--ui-ease),
    border-color var(--ui-ease),
    box-shadow var(--ui-ease);
}

.ui-radio__dot::after {
  content: '';
  position: absolute;
  inset: 3px;
  border-radius: 50%;
  background: var(--ui-primary);
  transform: scale(0);
  transition: transform var(--ui-ease);
}

.ui-radio:hover .ui-radio__dot {
  border-color: var(--ui-primary-hover);
}

.ui-radio__input:checked + .ui-radio__dot {
  border-color: var(--ui-primary);
}

.ui-radio__input:checked + .ui-radio__dot::after {
  transform: scale(1);
}

.ui-radio__input:focus-visible + .ui-radio__dot {
  box-shadow: var(--ui-shadow-focus);
}

.ui-radio__input:disabled + .ui-radio__dot {
  background: var(--ui-muted);
  border-color: var(--ui-input-border);
}

.ui-radio__label {
  min-width: 0;
}
</style>
