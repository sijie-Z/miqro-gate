/**
 * Auth store: current user, session state and the must-change-password flow.
 * Route guards gate on this store; the backend remains the authority.
 */

import { computed, ref } from 'vue';
import { defineStore } from 'pinia';
import * as api from '@/api';
import type { UserResponse } from '@/types/api';

export const useAuthStore = defineStore('auth', () => {
  const user = ref<UserResponse | null>(null);
  const loaded = ref(false);

  const isAuthenticated = computed(() => user.value !== null);
  const mustChangePassword = computed(() => user.value?.mustChangePassword ?? false);

  async function fetchMe(): Promise<void> {
    try {
      user.value = await api.me();
    } catch {
      user.value = null;
    } finally {
      loaded.value = true;
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
    loaded.value = true;
  }

  async function logout(): Promise<void> {
    try {
      await api.logout();
    } catch {
      // Local state is cleared regardless; the session may already be gone.
    } finally {
      user.value = null;
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
    isAuthenticated,
    mustChangePassword,
    fetchMe,
    login,
    register,
    logout,
    changePassword,
  };
});
