/**
 * #PH20-C — list endpoints that answer `null` must not blank the page.
 *
 * The generated types declare every list endpoint as `Promise<X[]>`, but the
 * cast in `src/api/http.ts` is unsound: a Jackson backend serialises an empty
 * collection as `null`, so `list.value = await api.listX()` stored `null` and
 * the very first `list.length` in the render function threw — the whole route
 * went blank instead of showing the empty state.
 *
 * Only `fetch` is stubbed here; the real api layer runs, which is what makes
 * this a regression test for the boundary guard rather than for a mock.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia } from 'pinia';

/** Every GET answers JSON `null` — the worst case a Jackson backend can send. */
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

  for (const [name, load] of VIEWS) {
    it(`${name} renders instead of throwing during render`, async () => {
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
    });
  }
});
