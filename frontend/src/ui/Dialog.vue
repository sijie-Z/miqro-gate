<script setup lang="ts">
/**
 * UiDialog — v2 design-system modal dialog on radix-vue Dialog primitives
 * (focus trap, aria-labelledby/describedby, esc & overlay handling, portal).
 * `dismissible=false` removes the close button and blocks esc/overlay close —
 * used for one-shot secrets that need an explicit acknowledgement.
 */
import {
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogOverlay,
  DialogPortal,
  DialogRoot,
  DialogTitle,
} from 'radix-vue';
import { useAttrs } from 'vue';

const props = withDefaults(
  defineProps<{
    open: boolean;
    title: string;
    description?: string;
    width?: string;
    dismissible?: boolean;
  }>(),
  {
    description: '',
    width: '480px',
    dismissible: true,
  },
);

const emit = defineEmits<{
  'update:open': [value: boolean];
  close: [];
}>();

defineOptions({ inheritAttrs: false });

const attrs = useAttrs();

function dismiss() {
  if (!props.dismissible) return;
  emit('update:open', false);
  emit('close');
}
</script>

<template>
  <DialogRoot :open="open" :modal="true" @update:open="dismissible && emit('update:open', $event)">
    <DialogPortal>
      <DialogOverlay class="ui-dialog__overlay" />
      <DialogContent class="ui-dialog__content" :style="{ width }" v-bind="attrs">
        <header class="ui-dialog__head">
          <div>
            <DialogTitle class="ui-dialog__title">{{ title }}</DialogTitle>
            <DialogDescription v-if="description" class="ui-dialog__desc">{{
              description
            }}</DialogDescription>
          </div>
          <DialogClose
            v-if="dismissible"
            class="ui-dialog__close"
            aria-label="关闭"
            @click="dismiss"
          >
            <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
              <path
                d="M4 4 12 12M12 4 4 12"
                stroke="currentColor"
                stroke-width="1.6"
                stroke-linecap="round"
              />
            </svg>
          </DialogClose>
        </header>
        <div class="ui-dialog__body">
          <slot />
        </div>
        <footer v-if="$slots.footer" class="ui-dialog__foot">
          <slot name="footer" />
        </footer>
      </DialogContent>
    </DialogPortal>
  </DialogRoot>
</template>

<style scoped>
.ui-dialog__overlay {
  position: fixed;
  inset: 0;
  background: rgba(17, 17, 19, 0.42);
  animation: ui-dialog-fade var(--ui-ease);
  z-index: 1500;
}

.ui-dialog__content {
  position: fixed;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  max-width: calc(100vw - 2 * var(--ui-space-8));
  max-height: calc(100vh - 2 * var(--ui-space-12));
  overflow: auto;
  background: var(--ui-card);
  border: 1px solid var(--ui-border);
  border-radius: var(--ui-radius-dialog);
  box-shadow: var(--ui-shadow-dialog);
  padding: var(--ui-space-5);
  outline: none;
  z-index: 1501;
  animation: ui-dialog-rise 160ms ease;
}

.ui-dialog__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--ui-space-4);
  margin-bottom: var(--ui-space-4);
}

.ui-dialog__title {
  margin: 0;
  font-size: var(--ui-font-size-lg);
  font-weight: var(--ui-weight-semibold);
  line-height: var(--ui-line-height-lg);
  color: var(--ui-foreground);
}

.ui-dialog__desc {
  margin: var(--ui-space-1) 0 0;
  font-size: var(--ui-font-size-sm);
  line-height: var(--ui-line-height-base);
  color: var(--ui-foreground-secondary);
}

.ui-dialog__close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: none;
  border-radius: var(--ui-radius-control);
  background: transparent;
  color: var(--ui-foreground-faint);
  cursor: pointer;
  flex-shrink: 0;
}

.ui-dialog__close:hover {
  background: var(--ui-fill-hover);
  color: var(--ui-foreground);
}

.ui-dialog__close:focus-visible {
  outline: none;
  box-shadow: var(--ui-shadow-focus);
}

.ui-dialog__body {
  font-size: var(--ui-font-size-sm);
  line-height: var(--ui-line-height-base);
  color: var(--ui-foreground);
}

.ui-dialog__foot {
  display: flex;
  justify-content: flex-end;
  gap: var(--ui-space-2);
  margin-top: var(--ui-space-5);
}

@keyframes ui-dialog-fade {
  from {
    opacity: 0;
  }
}

@keyframes ui-dialog-rise {
  from {
    opacity: 0;
    transform: translate(-50%, -48%);
  }
}
</style>
