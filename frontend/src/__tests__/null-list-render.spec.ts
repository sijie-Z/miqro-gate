/**
 * #PH20-C — a non-array body from a list endpoint must not throw during render.
 *
 * The generated types declare every list endpoint as `Promise<X[]>`, but the
 * cast in `src/api/http.ts` is unsound: whatever the body was, it was stored
 * as-is, so the first `list.length` / `.map` in a render function threw. This
 * backend does not emit such a body for list endpoints today (it serialises an
 * empty collection as `[]`), so the guard is hardening against the shape
 * drifting later. A throw no longer blanks the page either — since #833 the
 * router wraps views in an ErrorBoundary, so the user gets a recoverable error
 * card. That is still a broken route, which is what these tests pin down.
 *
 * Only `fetch` is stubbed here; the real api layer runs, which is what makes
 * this a regression test for the boundary guard rather than for a mock.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia } from 'pinia';

/** Every GET answers JSON `null` — a shape no list endpoint of this backend emits (see header). */
function stubNullFetch() {
  return vi.spyOn(globalThis, 'fetch').mockImplementation(
    async () =>
      new Response('null', {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
  );
}

const VIEWS: Array<[string, () => Promise<unknown>]> = [
  ['NextTeamsView', () => import('@/views/next/NextTeamsView.vue')],
  ['NextUsersView', () => import('@/views/next/NextUsersView.vue')],
  ['NextProjectsView', () => import('@/views/next/NextProjectsView.vue')],
  ['NextSkillsView', () => import('@/views/next/NextSkillsView.vue')],
  ['NextProvidersView', () => import('@/views/next/NextProvidersView.vue')],
  ['NextCostView', () => import('@/views/next/NextCostView.vue')],
  ['NextAdminWebhooksView', () => import('@/views/next/NextAdminWebhooksView.vue')],
  ['NextAdminSkillsView', () => import('@/views/next/NextAdminSkillsView.vue')],
  ['NextAdminServicesView', () => import('@/views/next/NextAdminServicesView.vue')],
  ['NextAdminDeletionsView', () => import('@/views/next/NextAdminDeletionsView.vue')],
];

describe('list views survive a null payload from the API (#PH20-C)', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  // The budget covers a cold module graph, not the assertion: every case awaits its own
  // view's dynamic import, and in a full parallel run that load alone measured 5040ms
  // against vitest's zero-margin 5s default — a required check went red on a boundary
  // that has nothing to do with what is being asserted (#1127). Raising the *global*
  // default was rejected: it would also delay the failure of a genuinely hung test.
  // Each graph loads once per file, so this is a ceiling rather than a cost.
  const COLD_LOAD_TIMEOUT_MS = 20_000;

  for (const [name, load] of VIEWS) {
    it(
      `${name} renders instead of throwing during render`,
      { timeout: COLD_LOAD_TIMEOUT_MS },
      async () => {
        stubNullFetch();
        const mod = (await load()) as { default: Parameters<typeof mount>[0] };
        const errors: unknown[] = [];
        const wrapper = mount(mod.default, {
          global: {
            plugins: [createPinia()],
            config: { errorHandler: (e) => errors.push(e) },
          },
        });
        await flushPromises();

        expect(
          errors.map((e) => String((e as Error)?.message ?? e)),
          `${name} must not throw while rendering a null list`,
        ).toEqual([]);
        wrapper.unmount();
      },
    );
  }
});
