/**
 * Auth store: current user, session state and the must-change-password flow.
 * Route guards gate on this store; the backend remains the authority.
 */

import { computed, ref } from 'vue';
import { defineStore } from 'pinia';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import type { UserResponse } from '@/types/generated-api';

/**
 * Backoff between session-restore attempts (total ≈ 12s) — long enough to ride
 * out a control-plane container restart, which otherwise bounces an active
 * session to /login on refresh (#583).
 */
const RESTORE_RETRY_DELAYS_MS = [1000, 3000, 8000];

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => {
    setTimeout(resolve, ms);
  });
}

/** A 401 is the only definitive "the session is gone" signal. */
function isUnauthorized(error: unknown): boolean {
  return error instanceof ApiError && error.status === 401;
}

export const useAuthStore = defineStore('auth', () => {
  const user = ref<UserResponse | null>(null);
  const loaded = ref(false);
  /**
   * True when the session could not be restored because the backend was
   * unreachable (network error / 5xx / timeout) — as opposed to invalid.
   * The router shows the "service unavailable" screen for this state instead
   * of /login, because the server-side session may still be perfectly valid.
   */
  const serviceUnavailable = ref(false);

  const isAuthenticated = computed(() => user.value !== null);
  const mustChangePassword = computed(() => user.value?.mustChangePassword ?? false);

  /**
   * Restores the session from the server. Only a 401 clears the local
   * identity; transient failures (network blip, 502/504 while the control
   * plane restarts) are retried and, if they persist, surface as
   * `serviceUnavailable` — never as "logged out".
   */
  async function fetchMe(): Promise<void> {
    serviceUnavailable.value = false;
    for (let attempt = 0; ; attempt += 1) {
      try {
        user.value = await api.me();
        loaded.value = true;
        return;
      } catch (error) {
        if (isUnauthorized(error)) {
          user.value = null;
          loaded.value = true;
          return;
        }
        if (attempt < RESTORE_RETRY_DELAYS_MS.length) {
          await delay(RESTORE_RETRY_DELAYS_MS[attempt] ?? 0);
          continue;
        }
        // Backend unreachable: keep any identity we already have and flag the
        // outage instead of pretending the session expired.
        serviceUnavailable.value = true;
        loaded.value = true;
        return;
      }
    }
  }

  async function login(username: string, password: string): Promise<void> {
    const response = await api.login(username, password);
    // The login response carries the same profile fields; re-fetch to also
    // pick up session metadata without another round trip.
    user.value = {
      id: response.id,
      username: response.username,
      displayName: response.displayName,
      role: response.role,
      status: 'ACTIVE',
      mustChangePassword: response.mustChangePassword,
      sessionExpiresAt: response.sessionExpiresAt,
    };
    serviceUnavailable.value = false;
    loaded.value = true;
  }

  async function register(
    username: string,
    displayName: string | undefined,
    password: string,
  ): Promise<void> {
    const response = await api.register(username, displayName, password);
    user.value = {
      id: response.id,
      username: response.username,
      displayName: response.displayName,
      role: response.role,
      status: 'ACTIVE',
      mustChangePassword: response.mustChangePassword,
      sessionExpiresAt: response.sessionExpiresAt,
    };
    serviceUnavailable.value = false;
    loaded.value = true;
  }

  async function logout(): Promise<void> {
    try {
      await api.logout();
    } catch {
      // Local state is cleared regardless; the session may already be gone.
    } finally {
      user.value = null;
      serviceUnavailable.value = false;
      loaded.value = true;
    }
  }

  async function changePassword(currentPassword: string, newPassword: string): Promise<void> {
    await api.changePassword(currentPassword, newPassword);
    if (user.value) {
      user.value.mustChangePassword = false;
    }
  }

  return {
    user,
    loaded,
    serviceUnavailable,
    isAuthenticated,
    mustChangePassword,
    fetchMe,
    login,
    register,
    logout,
    changePassword,
  };
});
