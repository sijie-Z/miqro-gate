<script setup lang="ts">
/**
 * UiSwitch — v2 design-system toggle (antd switch geometry as measured on
 * v2.vben.pro): 45×22 track, 100px radius, rgba(0,0,0,0.25) off / brand blue
 * on, 18px white knob held 2px from each edge, 150ms travel. The hidden native
 * checkbox covers the whole control so every click toggles exactly once;
 * a `label` prop or the default slot renders text beside the track. Extra
 * attrs (data-testid, aria-*) fall through to the input.
 */
import { useAttrs } from 'vue';

const props = withDefaults(
  defineProps<{
    modelValue: boolean;
    disabled?: boolean;
    label?: string;
  }>(),
  {
    disabled: false,
    label: '',
  },
);

const emit = defineEmits<{
  'update:modelValue': [value: boolean];
}>();

defineOptions({ inheritAttrs: false });

const attrs = useAttrs();

function onChange(event: Event) {
  if (props.disabled) return;
  emit('update:modelValue', (event.target as HTMLInputElement).checked);
}
</script>

<template>
  <label class="ui-switch" :class="{ 'ui-switch--disabled': disabled }">
    <input
      class="ui-switch__input"
      type="checkbox"
      role="switch"
      :checked="modelValue"
      :disabled="disabled"
      v-bind="attrs"
      @change="onChange"
    />
    <span class="ui-switch__track" aria-hidden="true">
      <span class="ui-switch__knob" />
    </span>
    <span v-if="label || $slots.default" class="ui-switch__label"
      ><slot>{{ label }}</slot></span
    >
  </label>
</template>

<style scoped>
.ui-switch {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: var(--ui-space-2);
  font-size: var(--ui-font-size-base);
  line-height: 22px;
  color: var(--ui-foreground);
  cursor: pointer;
  user-select: none;
  flex-shrink: 0;
}

.ui-switch--disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

/* The hidden input covers the whole control so every click lands on it
   directly — one native toggle, no label-forwarding double activations. */
.ui-switch__input {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  margin: 0;
  opacity: 0;
  cursor: inherit;
}

.ui-switch__track {
  position: relative;
  display: inline-block;
  width: 45px;
  height: 22px;
  border-radius: 100px;
  background: rgba(0, 0, 0, 0.25);
  flex-shrink: 0;
  transition: background-color 150ms ease;
}

.ui-switch__knob {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: var(--ui-card);
  transition: left 150ms ease;
}

.ui-switch__input:checked + .ui-switch__track {
  background: var(--ui-primary);
}

/* Knob parks 2px from the right edge, mirroring the 2px left inset. */
.ui-switch__input:checked + .ui-switch__track .ui-switch__knob {
  left: calc(100% - 20px);
}

.ui-switch__input:focus-visible + .ui-switch__track {
  box-shadow: var(--ui-shadow-focus);
}

.ui-switch__label {
  min-width: 0;
}
</style>
