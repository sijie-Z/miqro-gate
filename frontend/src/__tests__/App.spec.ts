import { describe, expect, it, vi, beforeEach } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import App from '@/App.vue';
import router from '@/router';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import { useAuthStore } from '@/stores/auth';
import type { ProblemDetails } from '@/types/api';
// The /login route lazily imports its chunk. Under the 40-file parallel jsdom
// run a contended dynamic import can starve past the test timeout even with a
// condition wait (#332 follow-up; observed as a 15s timeout on router.push).
// Pre-importing the chunk at module scope pins it into the module cache, so
// navigation resolves immediately and the tests keep asserting UI state only.
import '@/views/next/NextLoginView.vue';
import '@/views/next/NextUnavailableView.vue';

const apiError = (status: number, code: string): ApiError =>
  new ApiError({
    type: 'about:blank',
    title: 'title',
    status,
    code,
    requestId: 'test',
  } as ProblemDetails);

describe('App', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.restoreAllMocks();
    // A real 401 is the only definitive "no session" answer (#583).
    vi.spyOn(api, 'me').mockRejectedValue(apiError(401, 'UNAUTHORIZED'));
  });

  // The route components are lazily imported; under the 40-file parallel jsdom
  // run the chunk can resolve well after flushPromises under CPU contention
  // (issue #332). Assert with a condition wait instead of a timing assumption.
  it('renders the login view when unauthenticated', { timeout: 15_000 }, async () => {
    await router.push('/login');
    await flushPromises();

    const wrapper = mount(App, {
      global: {
        plugins: [router],
      },
    });

    await vi.waitFor(
      () => {
        expect(wrapper.text()).toContain('MiQroGate');
        expect(wrapper.find('[data-testid="login-submit"]').exists()).toBe(true);
      },
      { timeout: 10_000 },
    );
  });

  it('redirects unknown paths to login when unauthenticated', async () => {
    await router.push('/app/keys');
    await flushPromises();

    const wrapper = mount(App, {
      global: {
        plugins: [router],
      },
    });

    await vi.waitFor(
      () => {
        expect(wrapper.find('[data-testid="login-submit"]').exists()).toBe(true);
      },
      { timeout: 10_000 },
    );
  });

  it('routes to the service-unavailable screen on a transient restore failure (#583)', async () => {
    // The guard only retries when it has to restore the session itself, so
    // simulate the post-retry state: backend unreachable, no identity yet.
    const auth = useAuthStore();
    auth.loaded = true;
    auth.serviceUnavailable = true;

    await router.push('/app/keys');
    await flushPromises();

    const wrapper = mount(App, {
      global: {
        plugins: [router],
      },
    });

    await vi.waitFor(
      () => {
        expect(wrapper.find('[data-testid="unavailable-page"]').exists()).toBe(true);
      },
      { timeout: 10_000 },
    );
    expect(router.currentRoute.value.name).toBe('unavailable');
    expect(router.currentRoute.value.query.redirect).toBe('/app/keys');
  });
});
