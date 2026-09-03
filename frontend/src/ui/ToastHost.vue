<script setup lang="ts">
/**
 * UiToastHost — fixed bottom-right stack. Mount once in App.vue; the module
 * bus in ./toast.ts is app-global, so duplicate mounts would duplicate toasts.
 */
import { toastState, dismissToast } from './toast';
</script>

<template>
  <Teleport to="body">
    <div class="ui-toast-host" aria-live="polite" role="status" data-testid="ui-toast-host">
      <TransitionGroup name="ui-toast">
        <div
          v-for="item in toastState.items"
          :key="item.id"
          class="ui-toast"
          :class="`ui-toast--${item.tone}`"
          :data-testid="`ui-toast-${item.tone}`"
        >
          <span class="ui-toast__icon" aria-hidden="true">
            <svg
              v-if="item.tone === 'success'"
              width="14"
              height="14"
              viewBox="0 0 16 16"
              fill="none"
            >
              <circle cx="8" cy="8" r="6.5" stroke="currentColor" stroke-width="1.3" />
              <path
                d="M5.2 8.2 7.2 10.2 11 5.8"
                stroke="currentColor"
                stroke-width="1.5"
                stroke-linecap="round"
                stroke-linejoin="round"
              />
            </svg>
            <svg
              v-else-if="item.tone === 'error'"
              width="14"
              height="14"
              viewBox="0 0 16 16"
              fill="none"
            >
              <circle cx="8" cy="8" r="6.5" stroke="currentColor" stroke-width="1.3" />
              <path
                d="M8 5v3.4M8 10.6v.2"
                stroke="currentColor"
                stroke-width="1.5"
                stroke-linecap="round"
              />
            </svg>
            <svg v-else width="14" height="14" viewBox="0 0 16 16" fill="none">
              <circle cx="8" cy="8" r="6.5" stroke="currentColor" stroke-width="1.3" />
              <path
                d="M8 7.2v3.6M8 4.8v.2"
                stroke="currentColor"
                stroke-width="1.5"
                stroke-linecap="round"
              />
            </svg>
          </span>
          <span class="ui-toast__message">{{ item.message }}</span>
          <button
            v-if="item.closable"
            type="button"
            class="ui-toast__close"
            aria-label="关闭提示"
            @click="dismissToast(item.id)"
          >
            <svg width="12" height="12" viewBox="0 0 16 16" fill="none" aria-hidden="true">
              <path
                d="M4 4 12 12M12 4 4 12"
                stroke="currentColor"
                stroke-width="1.6"
                stroke-linecap="round"
              />
            </svg>
          </button>
        </div>
      </TransitionGroup>
    </div>
  </Teleport>
</template>

<style scoped>
.ui-toast-host {
  position: fixed;
  right: var(--ui-space-6);
  bottom: var(--ui-space-6);
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-2);
  z-index: 3000;
  max-width: min(420px, calc(100vw - 2 * var(--ui-space-6)));
}

.ui-toast {
  display: flex;
  align-items: flex-start;
  gap: var(--ui-space-3);
  padding: var(--ui-space-3) var(--ui-space-4);
  border-radius: var(--ui-radius-control);
  background: #232326;
  border: 1px solid rgba(255, 255, 255, 0.08);
  color: #f5f5f4;
  font-size: var(--ui-font-size-sm);
  line-height: var(--ui-line-height-base);
  box-shadow: var(--ui-shadow-popper);
}

.ui-toast--error {
  border-color: rgba(189, 20, 38, 0.55);
}

.ui-toast__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  margin-top: 1px;
}

.ui-toast--success .ui-toast__icon {
  color: #7cd992;
}

.ui-toast--info .ui-toast__icon {
  color: #9db4ff;
}

.ui-toast--error .ui-toast__icon {
  color: #ff8a94;
}

.ui-toast__message {
  flex: 1;
  min-width: 0;
  word-break: break-word;
}

.ui-toast__close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 20px;
  height: 20px;
  margin: -2px -4px 0 0;
  border: none;
  border-radius: 4px;
  background: transparent;
  color: rgba(245, 245, 244, 0.6);
  cursor: pointer;
}

.ui-toast__close:hover {
  background: rgba(255, 255, 255, 0.12);
  color: #fff;
}

.ui-toast__close:focus-visible {
  outline: none;
  box-shadow: 0 0 0 2px rgba(255, 255, 255, 0.5);
}

.ui-toast-enter-active,
.ui-toast-leave-active {
  transition:
    opacity var(--ui-ease),
    transform var(--ui-ease);
}

.ui-toast-enter-from,
.ui-toast-leave-to {
  opacity: 0;
  transform: translateY(6px);
}
</style>
