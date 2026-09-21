import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import { useAuthStore } from '@/stores/auth';
import NextUnavailableView from '@/views/next/NextUnavailableView.vue';
import type { ProblemDetails } from '@/types/api';
import type { UserResponse } from '@/types/generated-api';

const { replace } = vi.hoisted(() => ({ replace: vi.fn() }));

vi.mock('vue-router', () => ({
  useRoute: () => ({ query: { redirect: '/app/keys' } }),
  useRouter: () => ({ replace }),
}));

const apiError = (status: number, code: string): ApiError =>
  new ApiError({
    type: 'about:blank',
    title: 'title',
    status,
    code,
    requestId: 'r1',
  } as ProblemDetails);

const user = (): UserResponse => ({
  id: 'u1',
  username: 'alice',
  displayName: 'Alice',
  role: 'USER',
  status: 'ACTIVE',
  mustChangePassword: false,
  sessionExpiresAt: '2026-08-26T00:00:00Z',
});

describe('NextUnavailableView (#583)', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.restoreAllMocks();
    replace.mockClear();
  });

  it('retry after recovery navigates back to the requested page', async () => {
    vi.spyOn(api, 'me').mockResolvedValue(user());
    const wrapper = mount(NextUnavailableView);

    await wrapper.find('button').trigger('click');
    await flushPromises();

    const auth = useAuthStore();
    expect(auth.isAuthenticated).toBe(true);
    expect(replace).toHaveBeenCalledWith('/app/keys');
  });

  it('retry with a definitively expired session falls through to the login page', async () => {
    vi.spyOn(api, 'me').mockRejectedValue(apiError(401, 'SESSION_EXPIRED'));
    const wrapper = mount(NextUnavailableView);

    await wrapper.find('button').trigger('click');
    await flushPromises();

    expect(replace).toHaveBeenCalledWith({ name: 'login', query: { redirect: '/app/keys' } });
  });

  it('retry while the backend is still down stays on this screen', async () => {
    vi.useFakeTimers();
    try {
      vi.spyOn(api, 'me').mockRejectedValue(apiError(0, 'NETWORK_ERROR'));
      const wrapper = mount(NextUnavailableView);

      const click = wrapper.find('button').trigger('click');
      await vi.advanceTimersByTimeAsync(12_000);
      await click;
      await flushPromises();

      const auth = useAuthStore();
      expect(auth.serviceUnavailable).toBe(true);
      expect(replace).not.toHaveBeenCalled();
    } finally {
      vi.useRealTimers();
    }
  });
});
