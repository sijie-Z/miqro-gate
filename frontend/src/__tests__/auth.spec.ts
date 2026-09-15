import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import { useAuthStore } from '@/stores/auth';
import type { ProblemDetails } from '@/types/api';
import type { UserResponse } from '@/types/generated-api';

const user = (overrides: Partial<UserResponse> = {}): UserResponse => ({
  id: 'u1',
  username: 'alice',
  displayName: 'Alice',
  role: 'USER',
  status: 'ACTIVE',
  mustChangePassword: false,
  sessionExpiresAt: '2026-08-26T00:00:00Z',
  ...overrides,
});

const apiError = (status: number, code: string): ApiError =>
  new ApiError({
    type: 'about:blank',
    title: 'title',
    status,
    code,
    requestId: 'r1',
  } as ProblemDetails);

describe('auth store', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.restoreAllMocks();
  });

  it('starts unauthenticated until fetchMe resolves', async () => {
    vi.spyOn(api, 'me').mockRejectedValue(apiError(401, 'UNAUTHORIZED'));
    const store = useAuthStore();
    expect(store.isAuthenticated).toBe(false);

    await store.fetchMe();
    expect(store.loaded).toBe(true);
    expect(store.isAuthenticated).toBe(false);
  });

  it('populates the user on login and flags forced password change', async () => {
    vi.spyOn(api, 'login').mockResolvedValue({
      id: 'u1',
      username: 'alice',
      displayName: 'Alice',
      role: 'USER',
      mustChangePassword: true,
      sessionExpiresAt: '2026-08-26T00:00:00Z',
    });

    const store = useAuthStore();
    await store.login('alice', 'temp1234A');

    expect(store.isAuthenticated).toBe(true);
    expect(store.mustChangePassword).toBe(true);
    expect(store.user?.username).toBe('alice');
  });

  it('registers a new account and populates the user immediately', async () => {
    vi.spyOn(api, 'register').mockResolvedValue({
      id: 'u9',
      username: 'newbie',
      displayName: '新同学',
      role: 'USER',
      mustChangePassword: false,
      sessionExpiresAt: '2026-08-26T00:00:00Z',
    });

    const store = useAuthStore();
    await store.register('newbie', '新同学', 'StrongPass2026!');

    expect(api.register).toHaveBeenCalledWith('newbie', '新同学', 'StrongPass2026!');
    expect(store.isAuthenticated).toBe(true);
    expect(store.user?.username).toBe('newbie');
    expect(store.mustChangePassword).toBe(false);
  });

  it('clears the user on logout even if the API call fails', async () => {
    vi.spyOn(api, 'logout').mockRejectedValue(new Error('network'));
    const store = useAuthStore();
    store.user = user();

    await store.logout();

    expect(store.isAuthenticated).toBe(false);
  });

  it('clears the forced-password flag after changePassword succeeds', async () => {
    vi.spyOn(api, 'changePassword').mockResolvedValue(undefined);
    const store = useAuthStore();
    store.user = user({ mustChangePassword: true });

    await store.changePassword('old', 'newPass123A');

    expect(store.mustChangePassword).toBe(false);
    expect(api.changePassword).toHaveBeenCalledWith('old', 'newPass123A');
  });

  // ---- #583: session restore must not treat transient failures as logout ----

  it('treats a 401 as a definitive logout without retrying', async () => {
    const meMock = vi.spyOn(api, 'me').mockRejectedValue(apiError(401, 'SESSION_EXPIRED'));
    const store = useAuthStore();

    await store.fetchMe();

    expect(meMock).toHaveBeenCalledTimes(1);
    expect(store.isAuthenticated).toBe(false);
    expect(store.serviceUnavailable).toBe(false);
    expect(store.loaded).toBe(true);
  });

  it('retries transient failures and flags service unavailability instead of logging out', async () => {
    vi.useFakeTimers();
    try {
      const meMock = vi.spyOn(api, 'me').mockRejectedValue(apiError(502, 'HTTP_ERROR'));
      const store = useAuthStore();

      const pending = store.fetchMe();
      await vi.advanceTimersByTimeAsync(12_000);
      await pending;

      expect(meMock).toHaveBeenCalledTimes(4); // initial + 3 backoff retries
      expect(store.serviceUnavailable).toBe(true);
      expect(store.loaded).toBe(true);
      expect(store.isAuthenticated).toBe(false);
    } finally {
      vi.useRealTimers();
    }
  });

  it('keeps an existing identity through an outage', async () => {
    vi.useFakeTimers();
    try {
      vi.spyOn(api, 'me').mockRejectedValue(apiError(0, 'NETWORK_ERROR'));
      const store = useAuthStore();
      store.user = user();

      const pending = store.fetchMe();
      await vi.advanceTimersByTimeAsync(12_000);
      await pending;

      expect(store.isAuthenticated).toBe(true); // NOT logged out
      expect(store.serviceUnavailable).toBe(true);
    } finally {
      vi.useRealTimers();
    }
  });

  it('rides out a brief outage: retry after backoff restores the session', async () => {
    vi.useFakeTimers();
    try {
      vi.spyOn(api, 'me')
        .mockRejectedValueOnce(apiError(502, 'HTTP_ERROR'))
        .mockResolvedValueOnce(user({ username: 'alice' }));
      const store = useAuthStore();

      const pending = store.fetchMe();
      await vi.advanceTimersByTimeAsync(1_000);
      await pending;

      expect(store.isAuthenticated).toBe(true);
      expect(store.user?.username).toBe('alice');
      expect(store.serviceUnavailable).toBe(false);
    } finally {
      vi.useRealTimers();
    }
  });

  it('clears the outage flag on a fresh login', async () => {
    vi.spyOn(api, 'login').mockResolvedValue({
      id: 'u1',
      username: 'alice',
      displayName: 'Alice',
      role: 'USER',
      mustChangePassword: false,
      sessionExpiresAt: '2026-08-26T00:00:00Z',
    });
    const store = useAuthStore();
    store.serviceUnavailable = true;
    store.loaded = true;

    await store.login('alice', 'pw');

    expect(store.serviceUnavailable).toBe(false);
    expect(store.isAuthenticated).toBe(true);
  });
});
