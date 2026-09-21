// jsdom lacks several browser APIs that TDesign (and friends) use at
// mount time. Without these, e.g. the select popup never binds its
// trigger listeners and option clicks silently do nothing.
import { afterEach, vi } from 'vitest';
import { enableAutoUnmount } from '@vue/test-utils';

// Unmount every wrapper once its test ends (#963). A spec that forgets to
// unmount otherwise leaves the component's timers and listeners pending past
// the end of the file, and vitest tears jsdom down underneath them: the
// callback then throws from a global that is gone *after* every test passed —
// exit code 1 with no failing test to point at. `auto-unmount.spec.ts` is the
// guard; the components themselves still capture their host in timer cleanup
// (Drawer.vue), because the same race exists wherever a timer outlives a page.
enableAutoUnmount(afterEach);

class ResizeObserverMock {
  private readonly callback: ResizeObserverCallback;

  constructor(callback: ResizeObserverCallback) {
    this.callback = callback;
  }

  observe(target: Element): void {
    this.callback(
      [
        {
          target,
          contentRect: { width: 0, height: 0, top: 0, left: 0, bottom: 0, right: 0, x: 0, y: 0 },
          borderBoxSize: [],
          contentBoxSize: [],
          devicePixelContentBoxSize: [],
        } as unknown as ResizeObserverEntry,
      ],
      this as unknown as ResizeObserver,
    );
  }

  unobserve(): void {}

  disconnect(): void {}
}

class IntersectionObserverMock {
  observe(): void {}

  unobserve(): void {}

  disconnect(): void {}

  takeRecords(): IntersectionObserverEntry[] {
    return [];
  }

  root = null;
  rootMargin = '';
  thresholds = [];
}

if (typeof globalThis.ResizeObserver === 'undefined') {
  globalThis.ResizeObserver = ResizeObserverMock as unknown as typeof ResizeObserver;
}
if (typeof globalThis.IntersectionObserver === 'undefined') {
  globalThis.IntersectionObserver =
    IntersectionObserverMock as unknown as typeof IntersectionObserver;
}
if (typeof window.matchMedia !== 'function') {
  window.matchMedia = vi.fn().mockImplementation((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  })) as unknown as typeof window.matchMedia;
}
if (typeof Element.prototype.scrollTo !== 'function') {
  Element.prototype.scrollTo = () => {};
}
