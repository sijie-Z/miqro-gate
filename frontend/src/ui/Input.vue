<script setup lang="ts">
/**
 * UiInput — v2 design-system text field.
 * Label + control + optional error/hint under one field unit. Extra attrs
 * (data-testid, autocomplete, maxlength…) fall through to the <input>.
 * Use .passive="true" to skip the error text styling for plain inputs
 * without labels.
 */
import { computed, useAttrs } from 'vue';

const props = withDefaults(
  defineProps<{
    modelValue?: string;
    label?: string;
    placeholder?: string;
    type?: string;
    disabled?: boolean;
    required?: boolean;
    error?: string;
    hint?: string;
    /** Compact control (32px default; page titles sit next to 36px actions). */
    large?: boolean;
    /** Optional fixed width for the field (e.g. filter bars). */
    width?: string;
  }>(),
  {
    modelValue: '',
    label: '',
    placeholder: '',
    type: 'text',
    disabled: false,
    required: false,
    error: '',
    hint: '',
    large: false,
    width: '',
  },
);

const emit = defineEmits<{
  'update:modelValue': [value: string];
  change: [value: string];
  enter: [];
}>();

defineOptions({ inheritAttrs: false });

const attrs = useAttrs();

const rootClasses = computed(() => ({
  'ui-field': true,
  'ui-field--error': Boolean(props.error),
  'ui-field--disabled': props.disabled,
  'ui-field--large': props.large,
}));

function onInput(event: Event) {
  const value = (event.target as HTMLInputElement).value;
  emit('update:modelValue', value);
  emit('change', value);
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter') {
    emit('enter');
  }
}
</script>

<template>
  <div :class="rootClasses" :style="width ? { width } : {}">
    <label v-if="label" class="ui-field__label">
      {{ label }}<span v-if="required" class="ui-field__required" aria-hidden="true"> *</span>
    </label>
    <span class="ui-field__control">
      <input
        class="ui-field__input"
        :class="{ 'ui-field__input--suffix': $slots.suffix }"
        :type="type"
        :value="modelValue"
        :placeholder="placeholder"
        :disabled="disabled"
        :aria-invalid="error ? true : undefined"
        v-bind="attrs"
        @input="onInput"
        @keydown="onKeydown"
      />
      <span v-if="$slots.suffix" class="ui-field__suffix">
        <slot name="suffix" />
      </span>
    </span>
    <p v-if="error" class="ui-field__error" data-testid="field-error">{{ error }}</p>
    <p v-else-if="hint" class="ui-field__hint">{{ hint }}</p>
  </div>
</template>

<style scoped>
.ui-field {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-1);
}

.ui-field__label {
  font-size: var(--ui-font-size-xs);
  font-weight: var(--ui-weight-medium);
  color: var(--ui-foreground);
  line-height: var(--ui-line-height-sm);
}

.ui-field__required {
  color: var(--ui-danger-fg);
}

.ui-field__control {
  position: relative;
  display: block;
}

.ui-field__input {
  width: 100%;
  height: var(--ui-control-height);
  padding: 0 var(--ui-space-3);
  border: 1px solid var(--ui-input-border);
  border-radius: var(--ui-radius-control);
  background: var(--ui-card);
  color: var(--ui-foreground);
  font-family: inherit;
  font-size: var(--ui-font-size-sm);
  line-height: var(--ui-line-height-base);
  transition:
    border-color var(--ui-ease),
    box-shadow var(--ui-ease),
    background-color var(--ui-ease);
}

.ui-field__input--suffix {
  padding-right: 34px;
}

.ui-field__suffix {
  position: absolute;
  top: 0;
  right: 0;
  height: var(--ui-control-height);
  display: flex;
  align-items: center;
  padding-right: var(--ui-space-2);
  color: var(--ui-foreground-faint);
}

.ui-field--large .ui-field__control .ui-field__input,
.ui-field--large .ui-field__suffix {
  height: var(--ui-control-height-lg);
}

.ui-field--large .ui-field__input {
  font-size: var(--ui-font-size-base);
}

.ui-field__input::placeholder {
  color: color-mix(in srgb, var(--ui-foreground) 36%, transparent);
}

.ui-field__input:hover:not(:disabled):not(:focus) {
  border-color: var(--ui-border-strong);
}

.ui-field__input:focus {
  outline: none;
  border-color: var(--ui-primary);
  box-shadow: var(--ui-shadow-focus);
}

.ui-field__input:disabled {
  background: var(--ui-muted);
  color: var(--ui-foreground-faint);
  cursor: not-allowed;
}

.ui-field--error .ui-field__input {
  border-color: var(--ui-danger-fg);
}

.ui-field--error .ui-field__input:focus {
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--ui-danger-fg) 25%, transparent);
}

.ui-field__error {
  margin: 0;
  font-size: var(--ui-font-size-xs);
  color: var(--ui-danger-fg);
  line-height: var(--ui-line-height-sm);
}

.ui-field__hint {
  margin: 0;
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-faint);
  line-height: var(--ui-line-height-sm);
}
</style>
