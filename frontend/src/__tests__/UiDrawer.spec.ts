/**
 * UiDrawer is the console's modal slide-over (member lists, model scopes, the
 * global settings drawer). Unlike UiDialog — which hands the modal contract to
 * radix — UiDrawer is hand-rolled, so it has to provide that contract itself:
 * move focus in on open, keep it inside while open, and hand it back to
 * whatever opened it on close.
 */
import { beforeEach, describe, expect, it } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, ref } from 'vue';
import UiDrawer from '@/ui/Drawer.vue';

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
});
