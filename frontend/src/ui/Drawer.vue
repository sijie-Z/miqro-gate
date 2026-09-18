<script setup lang="ts">
/**
 * UiDrawer — right slide-over panel for secondary surfaces (member lists,
 * model scopes). Self-drawn: fixed right column over a dimmed overlay with
 * Escape/overlay close; animation is a simple enter transition. The panel
 * content scrolls; footer slot stays pinned at the bottom.
 */
import { nextTick, onBeforeUnmount, ref, useAttrs, watch, onUnmounted } from 'vue';

const props = withDefaults(
  defineProps<{
    open: boolean;
    title: string;
    width?: string;
    dismissible?: boolean;
  }>(),
  {
    width: '420px',
    dismissible: true,
  },
);

const emit = defineEmits<{
  'update:open': [value: boolean];
  close: [];
}>();

defineOptions({ inheritAttrs: false });

const attrs = useAttrs();
const panel = ref<HTMLElement | null>(null);

function dismiss() {
  if (!props.dismissible) return;
  emit('update:open', false);
  emit('close');
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && props.open) {
    dismiss();
  }
}

watch(
  () => props.open,
  async (openNow) => {
    if (openNow) {
      await nextTick();
      panel.value?.focus();
    }
  },
);

onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown));

// See Dialog.vue: clear the modal pointer lock left behind by a close that
// races the exit path, once no modal layer remains open.
onUnmounted(() => {
  if (!document.querySelector('[role="dialog"][data-state="open"]')) {
    document.body.style.pointerEvents = '';
  }
});
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="ui-drawer">
      <div class="ui-drawer__overlay" @click="dismiss" />
      <aside
        ref="panel"
        class="ui-drawer__panel"
        :style="{ width }"
        tabindex="-1"
        role="dialog"
        :aria-label="title"
        v-bind="attrs"
        @keydown="onKeydown"
      >
        <header class="ui-drawer__head">
          <h2 class="ui-drawer__title">{{ title }}</h2>
          <button
            v-if="dismissible"
            type="button"
            class="ui-drawer__close"
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
          </button>
        </header>
        <div class="ui-drawer__body">
          <slot />
        </div>
        <footer v-if="$slots.footer" class="ui-drawer__foot">
          <slot name="footer" />
        </footer>
      </aside>
    </div>
  </Teleport>
</template>

<style scoped>
.ui-drawer__overlay {
  position: fixed;
  inset: 0;
  background: rgba(17, 17, 19, 0.36);
  z-index: 1700;
}

/* Enter-only, scoped to the open state (see Dialog.vue for the radix
   close-waits-for-animation gotcha). */
.ui-drawer__overlay[data-state='open'] {
  animation: ui-drawer-fade 200ms linear;
}

.ui-drawer__panel {
  position: fixed;
  top: 0;
  right: 0;
  bottom: 0;
  display: flex;
  flex-direction: column;
  max-width: calc(100vw - var(--ui-space-8));
  background: var(--ui-card);
  border-left: 1px solid var(--ui-border);
  box-shadow: var(--ui-shadow-dialog);
  outline: none;
  z-index: 1701;
}

.ui-drawer__panel[data-state='open'] {
  animation: ui-drawer-slide 200ms var(--ui-ease-enter);
}

.ui-drawer__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ui-space-4);
  /* Vben/antd drawer header metrics: 16px block, 24px inline (issue #579). */
  padding: var(--ui-space-4) var(--ui-space-6);
  border-bottom: 1px solid var(--ui-border);
  flex-shrink: 0;
}

.ui-drawer__title {
  margin: 0;
  font-size: var(--ui-font-size-lg);
  font-weight: var(--ui-weight-semibold);
}

.ui-drawer__close {
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
}

.ui-drawer__close:hover {
  background: var(--ui-fill-hover);
  color: var(--ui-foreground);
}

.ui-drawer__close:focus-visible {
  outline: none;
  box-shadow: var(--ui-shadow-focus);
}

.ui-drawer__body {
  flex: 1;
  overflow-y: auto;
  padding: var(--ui-space-5);
  font-size: var(--ui-font-size-sm);
  line-height: var(--ui-line-height-base);
  color: var(--ui-foreground);
}

.ui-drawer__foot {
  display: flex;
  justify-content: flex-end;
  gap: var(--ui-space-2);
  padding: var(--ui-space-4) var(--ui-space-5);
  border-top: 1px solid var(--ui-border);
  flex-shrink: 0;
}

@keyframes ui-drawer-fade {
  from {
    opacity: 0;
  }
}

@keyframes ui-drawer-slide {
  from {
    transform: translateX(24px);
    opacity: 0;
  }
}
</style>
