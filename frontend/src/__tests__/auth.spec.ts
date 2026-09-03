import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import * as api from '@/api';
import { useAuthStore } from '@/stores/auth';
import type { UserResponse } from '@/types/api';

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

describe('auth store', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.restoreAllMocks();
  });

  it('starts unauthenticated until fetchMe resolves', async () => {
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
});
