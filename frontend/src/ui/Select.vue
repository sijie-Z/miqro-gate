<script setup lang="ts">
/**
 * UiSelect — v2 design-system select built on radix-vue's headless Select
 * (a11y: listbox semantics, keyboard nav, focus management, portals).
 * Visual styling is entirely ours (hairline trigger, popper panel).
 * Options come from props; value is the option's `value`.
 */
import { computed, ref, useAttrs } from 'vue';
import {
  SelectContent,
  SelectItem,
  SelectItemIndicator,
  SelectItemText,
  SelectPortal,
  SelectRoot,
  SelectTrigger,
  SelectValue,
  SelectViewport,
} from 'radix-vue';

export interface UiSelectOption {
  value: string;
  label: string;
  disabled?: boolean;
  hint?: string;
}

const props = withDefaults(
  defineProps<{
    modelValue?: string;
    options: UiSelectOption[];
    placeholder?: string;
    disabled?: boolean;
    error?: string;
    label?: string;
    /** Fixed trigger width in px/rem; default fills its inline container. */
    width?: string;
    loading?: boolean;
    required?: boolean;
  }>(),
  {
    modelValue: '',
    placeholder: '请选择',
    disabled: false,
    error: '',
    label: '',
    width: '',
    loading: false,
    required: false,
  },
);

const emit = defineEmits<{
  'update:modelValue': [value: string];
  change: [value: string];
}>();

defineOptions({ name: 'UiSelect', inheritAttrs: false });

const attrs = useAttrs();
const open = ref(false);

function pick(value: string) {
  emit('update:modelValue', value);
  emit('change', value);
}

const triggerClasses = computed(() => ({
  'ui-select__trigger': true,
  'ui-select__trigger--open': open.value,
  'ui-select__trigger--error': Boolean(props.error),
}));
</script>

<template>
  <div class="ui-select">
    <label v-if="label" class="ui-select__label">
      {{ label }}<span v-if="required" class="ui-select__required" aria-hidden="true"> *</span>
    </label>
    <SelectRoot
      :model-value="modelValue || undefined"
      :disabled="disabled || loading"
      @update:model-value="pick"
      @update:open="open = $event"
    >
      <SelectTrigger :class="triggerClasses" :style="width ? { width } : {}" v-bind="attrs">
        <span class="ui-select__value">
          <span v-if="loading" class="ui-select__loading-hint">加载中…</span>
          <SelectValue v-else :placeholder="placeholder" />
        </span>
        <svg
          class="ui-select__chevron"
          :class="{ 'ui-select__chevron--up': open }"
          width="14"
          height="14"
          viewBox="0 0 16 16"
          fill="none"
          aria-hidden="true"
        >
          <path
            d="M4 6.5 8 10.5 12 6.5"
            stroke="currentColor"
            stroke-width="1.5"
            stroke-linecap="round"
            stroke-linejoin="round"
          />
        </svg>
      </SelectTrigger>
      <SelectPortal>
        <SelectContent
          class="ui-select__content"
          :side-offset="4"
          :align="'start'"
          position="popper"
        >
          <SelectViewport class="ui-select__viewport">
            <SelectItem
              v-for="option in options"
              :key="option.value"
              :value="option.value"
              :disabled="option.disabled"
              class="ui-select__item"
            >
              <span class="ui-select__item-label">
                <SelectItemText>{{ option.label }}</SelectItemText>
                <span v-if="option.hint" class="ui-select__item-hint">{{ option.hint }}</span>
              </span>
              <SelectItemIndicator class="ui-select__item-check">
                <svg width="12" height="12" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                  <path
                    d="M3.5 8.5 6.5 11.5 12.5 4.5"
                    stroke="currentColor"
                    stroke-width="1.8"
                    stroke-linecap="round"
                    stroke-linejoin="round"
                  />
                </svg>
              </SelectItemIndicator>
            </SelectItem>
          </SelectViewport>
        </SelectContent>
      </SelectPortal>
    </SelectRoot>
    <p v-if="error" class="ui-select__error">{{ error }}</p>
  </div>
</template>

<style scoped>
.ui-select {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-1);
}

.ui-select__label {
  font-size: var(--ui-font-size-xs);
  font-weight: var(--ui-weight-medium);
  color: var(--ui-foreground);
  line-height: var(--ui-line-height-sm);
}

.ui-select__required {
  color: var(--ui-danger-fg);
}

.ui-select__trigger {
  display: inline-flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ui-space-2);
  height: var(--ui-control-height);
  padding: 0 var(--ui-space-3);
  min-width: 160px;
  border: 1px solid var(--ui-input-border);
  border-radius: var(--ui-radius-control);
  background: var(--ui-card);
  color: var(--ui-foreground);
  font-family: inherit;
  font-size: var(--ui-font-size-sm);
  cursor: pointer;
  transition:
    border-color var(--ui-ease),
    box-shadow var(--ui-ease);
}

.ui-select__trigger:hover:not(:disabled):not(:focus-visible) {
  border-color: var(--ui-border-strong);
}

.ui-select__trigger:focus-visible,
.ui-select__trigger--open {
  outline: none;
  border-color: var(--ui-primary);
  box-shadow: var(--ui-shadow-focus);
}

.ui-select__trigger:disabled {
  background: var(--ui-muted);
  color: var(--ui-foreground-faint);
  cursor: not-allowed;
}

.ui-select__trigger--error {
  border-color: var(--ui-danger-fg);
}

.ui-select__value {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: inherit;
}

.ui-select__value :deep([data-placeholder]) {
  color: var(--ui-foreground-faint);
  font-weight: var(--ui-weight-regular);
}

.ui-select__loading-hint {
  color: var(--ui-foreground-faint);
}

.ui-select__chevron {
  flex-shrink: 0;
  color: var(--ui-foreground-faint);
  transition: transform var(--ui-ease);
}

.ui-select__chevron--up {
  transform: rotate(180deg);
}

.ui-select__content {
  background: var(--ui-card);
  border: 1px solid var(--ui-border);
  border-radius: var(--ui-radius-control);
  box-shadow: var(--ui-shadow-popper);
  padding: var(--ui-space-1);
  z-index: 2000;
  min-width: var(--radix-select-trigger-width);
  max-height: 320px;
  overflow: hidden;
}

.ui-select__viewport {
  overflow-y: auto;
  padding: 0;
}

.ui-select__item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ui-space-3);
  padding: var(--ui-space-2) var(--ui-space-3);
  border-radius: calc(var(--ui-radius-control) - 2px);
  font-size: var(--ui-font-size-sm);
  color: var(--ui-foreground);
  cursor: pointer;
  user-select: none;
  outline: none;
}

.ui-select__item[data-highlighted] {
  background: var(--ui-fill-hover);
}

.ui-select__item[data-disabled] {
  color: var(--ui-foreground-faint);
  cursor: not-allowed;
}

.ui-select__item-label {
  display: flex;
  flex-direction: column;
  gap: 0;
  min-width: 0;
}

.ui-select__item-hint {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-faint);
}

.ui-select__item-check {
  color: var(--ui-primary);
  flex-shrink: 0;
}

.ui-select__error {
  margin: 0;
  font-size: var(--ui-font-size-xs);
  color: var(--ui-danger-fg);
  line-height: var(--ui-line-height-sm);
}
</style>
