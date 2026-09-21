/**
 * UiDrawer is the console's modal slide-over (member lists, model scopes, the
 * global settings drawer). Unlike UiDialog — which hands the modal contract to
 * radix — UiDrawer is hand-rolled, so it has to provide that contract itself:
 * move focus in on open, keep it inside while open, and hand it back to
 * whatever opened it on close.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, ref } from 'vue';
import {
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuPortal,
  DropdownMenuRoot,
  DropdownMenuTrigger,
} from 'radix-vue';
import UiDrawer from '@/ui/Drawer.vue';

/** Let queued macrotasks (radix's close-path autofocus) run. */
function tick(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

const Host = defineComponent({
  components: { UiDrawer },
  setup() {
    const open = ref(false);
    return { open };
  },
  template: `
    <button id="trigger" type="button" @click="open = true">打开成员抽屉</button>
    <button id="other" type="button">页面上的其他按钮</button>
    <UiDrawer v-model:open="open" title="成员">
      <button id="inside" type="button">抽屉内按钮</button>
    </UiDrawer>
  `,
});

function panel(): HTMLElement {
  const el = document.body.querySelector<HTMLElement>('.ui-drawer__panel');
  if (!el) throw new Error('drawer panel is not rendered');
  return el;
}

function press(target: Element, key: string, shiftKey = false): void {
  target.dispatchEvent(
    new KeyboardEvent('keydown', { key, shiftKey, bubbles: true, cancelable: true }),
  );
}

/** Open the drawer the way a keyboard user does: focus the trigger, activate it. */
async function openDrawer() {
  const wrapper = mount(Host, { attachTo: document.body });
  const trigger = document.body.querySelector<HTMLElement>('#trigger');
  if (!trigger) throw new Error('#trigger missing');
  trigger.focus();
  trigger.click();
  await wrapper.vm.$nextTick();
  await wrapper.vm.$nextTick();
  return { wrapper, trigger };
}

describe('UiDrawer modal focus contract', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });

  it('moves focus into the panel when it opens', async () => {
    await openDrawer();
    expect(document.activeElement).toBe(panel());
  });

  it('marks the panel as a modal dialog', async () => {
    await openDrawer();
    expect(panel().getAttribute('role')).toBe('dialog');
    expect(panel().getAttribute('aria-modal')).toBe('true');
  });

  it('keeps Tab inside the panel', async () => {
    await openDrawer();
    const inside = document.body.querySelector<HTMLElement>('#inside');
    if (!inside) throw new Error('#inside missing');
    const close = panel().querySelector<HTMLElement>('.ui-drawer__close');
    if (!close) throw new Error('close button missing');

    // Tabbing from the panel itself enters the first control…
    press(panel(), 'Tab');
    expect(document.activeElement).toBe(close);

    // …and tabbing off the last control wraps instead of leaving the drawer.
    inside.focus();
    press(inside, 'Tab');
    expect(document.activeElement).toBe(close);
  });

  it('returns focus to the trigger when it closes', async () => {
    const { trigger } = await openDrawer();
    expect(document.activeElement).toBe(panel());

    press(panel(), 'Escape');
    await flushPromises();

    expect(document.body.querySelector('.ui-drawer__panel')).toBeNull();
    expect(document.activeElement).toBe(trigger);
  });

  it('returns focus to the trigger when the close button is used', async () => {
    const { trigger } = await openDrawer();
    const close = panel().querySelector<HTMLElement>('.ui-drawer__close');
    if (!close) throw new Error('close button missing');

    close.click();
    await flushPromises();

    expect(document.activeElement).toBe(trigger);
  });

  it('returns focus to the trigger when the overlay is used', async () => {
    const { trigger } = await openDrawer();
    const overlay = document.body.querySelector<HTMLElement>('.ui-drawer__overlay');
    if (!overlay) throw new Error('overlay missing');

    overlay.click();
    await flushPromises();

    expect(document.activeElement).toBe(trigger);
  });

  it('detaches the focus guard even when the document global is gone', async () => {
    vi.useFakeTimers();
    const { wrapper } = await openDrawer();
    const doc = document;
    const removeListener = vi.spyOn(doc, 'removeEventListener');
    try {
      // Simulates a test file that never unmounts its drawer: vitest tears
      // jsdom down with the settle timer still pending, and the callback must
      // not reach for a global that is already gone — that throws *after* every
      // test passed, failing the run with exit code 1 and no failing test to
      // point at (exactly how this reached CI).
      (globalThis as { document?: Document }).document = undefined;
      vi.advanceTimersByTime(500);
      expect(removeListener).toHaveBeenCalledWith('focusin', expect.any(Function), true);
    } finally {
      // Undo everything unconditionally: a throw above is the failure this test
      // hunts for, and it must not become the reason later cases fail (leaked
      // fake timers starve the next test's macrotasks into a timeout).
      (globalThis as { document?: Document }).document = doc;
      removeListener.mockRestore();
      vi.useRealTimers();
      wrapper.unmount();
    }
  });
});

/**
 * Most drawers are actually opened from a radix dropdown row action (e.g.
 * NextUsersView's kebab → 项目成员, NextUsersView.vue:595). Radix hands focus
 * back to its trigger from a close-path autofocus *timer*, which lands after
 * the drawer's own nextTick focus-in — so a one-shot focus on open loses the
 * race and focus ends up on the trigger sitting behind the overlay. From
 * there Escape and Tab both miss the panel, since keydown is bound to it.
 */
const KebabHost = defineComponent({
  components: {
    UiDrawer,
    DropdownMenuRoot,
    DropdownMenuTrigger,
    DropdownMenuContent,
    DropdownMenuPortal,
    DropdownMenuItem,
  },
  setup() {
    const open = ref(false);
    return { open };
  },
  template: `
    <DropdownMenuRoot>
      <DropdownMenuTrigger data-testid="kebab" aria-label="操作">更多</DropdownMenuTrigger>
      <DropdownMenuPortal>
        <DropdownMenuContent>
          <DropdownMenuItem data-testid="item" @select="open = true">项目成员</DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenuPortal>
    </DropdownMenuRoot>
    <UiDrawer v-model:open="open" title="成员">
      <button id="inside" type="button">抽屉内按钮</button>
    </UiDrawer>
  `,
});

describe('UiDrawer opened from a radix menu item', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });

  it('holds focus inside the panel once the menu has finished closing', async () => {
    mount(KebabHost, { attachTo: document.body });
    const kebab = document.body.querySelector<HTMLElement>('[data-testid="kebab"]');
    if (!kebab) throw new Error('kebab trigger missing');
    kebab.click();
    await flushPromises();

    const item = document.body.querySelector<HTMLElement>('[data-testid="item"]');
    if (!item) throw new Error('menu item missing');
    item.focus();
    item.click();

    // Flush the drawer's focus-in, then radix's close-path autofocus timer.
    await flushPromises();
    await tick();
    await tick();
    await flushPromises();

    const panelEl = document.body.querySelector<HTMLElement>('.ui-drawer__panel');
    const active = document.activeElement as HTMLElement | null;
    // The panel's accessible name is the drawer title; the kebab trigger's is
    // 操作 — so the raw diff below names whichever one actually holds focus.
    expect({
      tag: active?.tagName,
      name: active?.getAttribute('aria-label'),
      insidePanel: panelEl?.contains(active) ?? false,
    }).toEqual({ tag: 'ASIDE', name: '成员', insidePanel: true });

    // And closing it hands focus back to the kebab, not to the menu item that
    // unmounted with the menu.
    press(panelEl as HTMLElement, 'Escape');
    await flushPromises();
    await tick();

    expect(document.body.querySelector('.ui-drawer__panel')).toBeNull();
    expect(document.activeElement).toBe(kebab);
  });
});
