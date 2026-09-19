<script setup lang="ts">
/**
 * UiDrawer — right slide-over panel for secondary surfaces (member lists,
 * model scopes). Self-drawn: fixed right column over a dimmed overlay with
 * Escape/overlay close; animation is a simple enter transition. The panel
 * content scrolls; footer slot stays pinned at the bottom.
 */
import { nextTick, onUnmounted, ref, useAttrs, watch } from 'vue';

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

/** Focus targets inside the panel, in document order. */
const FOCUSABLE =
  'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/** The control that had focus before the drawer opened; focus returns there. */
let restoreFocusTo: HTMLElement | null = null;

/** The element holding focus, unless that is just the document body. */
function currentFocus(): HTMLElement | null {
  const active = document.activeElement;
  return active instanceof HTMLElement && active !== document.body ? active : null;
}

/** Matches the panel's enter animation; see watchForStrayFocus(). */
const SETTLE_MS = 250;
let detachSettle: (() => void) | null = null;

/**
 * Pull focus back into the panel while the drawer is settling. The opener is
 * usually a radix menu item, and radix's close path moves focus back to its
 * own trigger on macrotask timers that can land after the drawer focused the
 * panel — once to the item, once to the trigger — so a single focus-in on open
 * loses the race. Bounded by SETTLE_MS rather than standing, so a dialog the
 * user opens on top of the drawer later is not fought.
 */
function watchForStrayFocus() {
  detachSettle?.();
  // Re-focusing the panel is itself a focus-in, and radix answers one that
  // lands outside its own layer by claiming focus back — synchronously. Let
  // that answer re-enter this handler and the two trade focus inside a single
  // call until the stack overflows, so stand down for events this handler
  // caused. It also gives the right priority: a layer that claims focus in
  // response to us is a layer above the drawer, and keeps it.
  let refocusing = false;
  const onFocusIn = (event: FocusEvent) => {
    if (refocusing || !props.open || !panel.value) return;
    const target = event.target as Node | null;
    if (target && panel.value.contains(target)) return;
    // Whatever just took focus is the real opener, and unlike the menu item it
    // is still in the document when the drawer closes.
    restoreFocusTo = currentFocus() ?? restoreFocusTo;
    refocusing = true;
    try {
      panel.value.focus();
    } finally {
      refocusing = false;
    }
  };
  document.addEventListener('focusin', onFocusIn, true);
  const timer = setTimeout(() => {
    off();
  }, SETTLE_MS);
  function off() {
    clearTimeout(timer);
    document.removeEventListener('focusin', onFocusIn, true);
    if (detachSettle === off) detachSettle = null;
  }
  detachSettle = off;
}

/** Focusable controls currently inside the panel, in document order. */
function focusableItems(): HTMLElement[] {
  return panel.value ? Array.from(panel.value.querySelectorAll<HTMLElement>(FOCUSABLE)) : [];
}

function dismiss() {
  if (!props.dismissible) return;
  emit('update:open', false);
  emit('close');
}

function onKeydown(event: KeyboardEvent) {
  if (!props.open) return;
  if (event.key === 'Escape') {
    dismiss();
    return;
  }
  if (event.key !== 'Tab') return;
  // The panel is teleported into <body> and there is no native dialog element
  // behind it, so nothing stops Tab from walking into the page underneath —
  // cycle the panel's own focus targets instead.
  const items = focusableItems();
  const first = items[0];
  const last = items[items.length - 1];
  if (!first || !last) {
    // Nothing focusable inside: hold focus on the panel itself rather than
    // letting Tab walk into the page behind the overlay.
    event.preventDefault();
    panel.value?.focus();
    return;
  }
  const index = items.indexOf(document.activeElement as HTMLElement);
  if (index === -1) {
    event.preventDefault();
    (event.shiftKey ? last : first).focus();
  } else if (event.shiftKey && index === 0) {
    event.preventDefault();
    last.focus();
  } else if (!event.shiftKey && index === items.length - 1) {
    event.preventDefault();
    first.focus();
  }
}

watch(
  () => props.open,
  async (openNow, wasOpen) => {
    if (openNow) {
      restoreFocusTo = currentFocus();
      await nextTick();
      panel.value?.focus();
      // The opener is usually a radix menu item (NextUsersView.vue:595), and
      // radix's menu close path hands focus back to its own trigger from
      // macrotask timers — more than one of them, the last landing after the
      // focus-in above, which would leave focus on the trigger behind the
      // overlay. Pull strays back for the settle window; the guard detaches
      // itself, so layers the user opens later are left alone.
      watchForStrayFocus();
      return;
    }
    if (!wasOpen) return;
    detachSettle?.();
    const target = restoreFocusTo;
    restoreFocusTo = null;
    await nextTick();
    // The trigger can be gone by then (row re-rendered, route changed) — a
    // detached node silently swallows focus, so check before calling.
    if (target?.isConnected) target.focus();
  },
  { immediate: true },
);

// See Dialog.vue: clear the modal pointer lock left behind by a close that
// races the exit path, once no modal layer remains open.
onUnmounted(() => {
  detachSettle?.();
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
        aria-modal="true"
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
