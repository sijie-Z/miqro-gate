/**
 * /api/v1/auth and /api/v1/me endpoint clients (api-contract.md §3–§4).
 */

import { get, post } from './http';
import type {
  CreateVirtualKeyRequest,
  CreateVirtualKeyResponse,
  LoginResponse,
  MeGrantsResponse,
  UsageGroupBy,
  UsageRecordPage,
  UsageSummary,
  UserResponse,
  VirtualKeyView,
} from '@/types/api';

// ---- auth ----

export function login(username: string, password: string): Promise<LoginResponse> {
  return post<LoginResponse>('/api/v1/auth/login', { username, password });
}

export function logout(): Promise<void> {
  return post<void>('/api/v1/auth/logout');
}

export function me(): Promise<UserResponse> {
  return get<UserResponse>('/api/v1/auth/me');
}

export function changePassword(currentPassword: string, newPassword: string): Promise<void> {
  return post<void>('/api/v1/auth/password', { currentPassword, newPassword });
}

// ---- self-service Virtual Keys ----

export function listVirtualKeys(): Promise<VirtualKeyView[]> {
  return get<VirtualKeyView[]>('/api/v1/me/virtual-keys');
}

export function getVirtualKey(id: string): Promise<VirtualKeyView> {
  return get<VirtualKeyView>(`/api/v1/me/virtual-keys/${id}`);
}

export function createVirtualKey(
  request: CreateVirtualKeyRequest,
): Promise<CreateVirtualKeyResponse> {
  return post<CreateVirtualKeyResponse>('/api/v1/me/virtual-keys', request);
}

export function rotateVirtualKey(id: string): Promise<CreateVirtualKeyResponse> {
  return post<CreateVirtualKeyResponse>(`/api/v1/me/virtual-keys/${id}/rotate`);
}

export function revokeVirtualKey(id: string): Promise<{ message: string }> {
  return post<{ message: string }>(`/api/v1/me/virtual-keys/${id}/revoke`);
}

export function myGrants(): Promise<MeGrantsResponse> {
  return get<MeGrantsResponse>('/api/v1/me/grants');
}

// ---- usage ----

export function usageSummary(
  groupBy?: UsageGroupBy,
  from?: string,
  to?: string,
): Promise<UsageSummary> {
  return get<UsageSummary>('/api/v1/me/usage/summary', { groupBy, from, to });
}

export function usageRecords(
  options: { from?: string; to?: string; page?: number; size?: number } = {},
): Promise<UsageRecordPage> {
  return get<UsageRecordPage>('/api/v1/me/usage/records', {
    from: options.from,
    to: options.to,
    page: options.page,
    size: options.size,
  });
}
