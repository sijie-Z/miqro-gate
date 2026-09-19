/**
 * Guards the harness rule from #963: every wrapper is unmounted once its test
 * ends, so a component's timers and listeners never outlive the jsdom
 * environment.
 *
 * What it protects against is nastier than it looks. A spec that forgets to
 * unmount leaves the component's pending timer queued while vitest tears its
 * environment down; the callback then throws from a global that no longer
 * exists — *after* every test passed, so the run fails with exit code 1 and no
 * failing test to point at (that is how a drawer settle timer reached CI on
 * 2026-09-19). Removing `enableAutoUnmount(afterEach)` from `setup.ts` turns
 * the second case below red.
 */
import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import { defineComponent, onUnmounted } from 'vue';

let unmounted = false;

const Probe = defineComponent({
  setup() {
    // Long enough to stay pending past the end of the run: this is the kind of
    // timer that outlives a teardown when nothing unmounts the component.
    const timer = setTimeout(() => undefined, 10 * 60_000);
    onUnmounted(() => {
      clearTimeout(timer);
      unmounted = true;
    });
    return () => null;
  },
});

describe('test harness auto-unmount (#963)', () => {
  it('mounts a component without asking to unmount it', () => {
    const wrapper = mount(Probe);
    expect(wrapper.vm).toBeTruthy();
  });

  it('has already unmounted the previous test’s wrapper', () => {
    expect(unmounted).toBe(true);
  });
});
