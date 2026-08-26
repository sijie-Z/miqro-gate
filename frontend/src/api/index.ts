/**
 * /api/v1/auth and /api/v1/me endpoint clients (api-contract.md §3–§4).
 */

import { del, get, patch, post } from './http';
import type {
  AdminUser,
  CredentialSummary,
  CreateVirtualKeyRequest,
  CreateVirtualKeyResponse,
  Grant,
  LoginResponse,
  MeGrantsResponse,
  MemberView,
  Provider,
  ProviderProductView,
  SeatView,
  Project,
  Team,
  SubscriptionView,
  UsageGroupBy,
  UsageRecordPage,
  UsageSummary,
  UserCreatedResponse,
  UserResponse,
  UserRole,
  UserStatusValue,
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

// ---- admin organization (G5.2) ----

export function listUsers(): Promise<AdminUser[]> {
  return get<AdminUser[]>('/api/v1/admin/users');
}

export function createUser(body: {
  username: string;
  displayName?: string;
  role?: UserRole;
}): Promise<UserCreatedResponse> {
  return post<UserCreatedResponse>('/api/v1/admin/users', body);
}

export function updateUserStatus(id: string, status: UserStatusValue): Promise<AdminUser> {
  return patch<AdminUser>(`/api/v1/admin/users/${id}`, { status });
}

export function resetUserPassword(id: string): Promise<UserCreatedResponse> {
  return post<UserCreatedResponse>(`/api/v1/admin/users/${id}/reset-password`);
}

export function revokeUserSessions(id: string): Promise<void> {
  return post<void>(`/api/v1/admin/users/${id}/revoke-sessions`);
}

export function listTeams(): Promise<Team[]> {
  return get<Team[]>('/api/v1/admin/teams');
}

export function createTeam(body: { name: string; description?: string }): Promise<Team> {
  return post<Team>('/api/v1/admin/teams', body);
}

export function listTeamMembers(teamId: string): Promise<MemberView[]> {
  return get<MemberView[]>(`/api/v1/admin/teams/${teamId}/members`);
}

export function addTeamMember(teamId: string, userId: string): Promise<void> {
  return post<void>(`/api/v1/admin/teams/${teamId}/members`, { userId });
}

export function removeTeamMember(teamId: string, userId: string): Promise<void> {
  return del<void>(`/api/v1/admin/teams/${teamId}/members/${userId}`);
}

export function listProjects(): Promise<Project[]> {
  return get<Project[]>('/api/v1/admin/projects');
}

export function createProject(body: {
  code: string;
  name: string;
  projectTag?: string;
}): Promise<Project> {
  return post<Project>('/api/v1/admin/projects', body);
}

export function listProjectMembers(projectId: string): Promise<MemberView[]> {
  return get<MemberView[]>(`/api/v1/admin/projects/${projectId}/members`);
}

export function addProjectMember(projectId: string, userId: string): Promise<void> {
  return post<void>(`/api/v1/admin/projects/${projectId}/members`, { userId });
}

export function removeProjectMember(projectId: string, userId: string): Promise<void> {
  return del<void>(`/api/v1/admin/projects/${projectId}/members/${userId}`);
}

export function listGrants(): Promise<Grant[]> {
  return get<Grant[]>('/api/v1/admin/grants');
}

export function createGrant(body: {
  projectId: string;
  providerProductId: string;
  credentialId: string;
  models: string[];
}): Promise<Grant> {
  return post<Grant>('/api/v1/admin/grants', body);
}

export function grantModels(grantId: string): Promise<string[]> {
  return get<string[]>(`/api/v1/admin/grants/${grantId}/models`);
}

export function updateGrantModels(grantId: string, models: string[]): Promise<Grant> {
  return post<Grant>(`/api/v1/admin/grants/${grantId}/models`, { models });
}

export function disableGrant(grantId: string): Promise<void> {
  return del<void>(`/api/v1/admin/grants/${grantId}`);
}

export function listCredentials(): Promise<CredentialSummary[]> {
  return get<CredentialSummary[]>('/api/v1/admin/credentials');
}

// ---- admin provider/Plan (G5.3) ----

export function listProviderProducts(): Promise<ProviderProductView[]> {
  return get<ProviderProductView[]>('/api/v1/admin/provider-products');
}

export function listProviders(): Promise<Provider[]> {
  return get<Provider[]>('/api/v1/admin/provider-products/providers');
}

export function listSubscriptions(): Promise<SubscriptionView[]> {
  return get<SubscriptionView[]>('/api/v1/admin/subscriptions');
}

export function createSubscription(body: {
  providerProductId: string;
  name: string;
  billingMode: string;
  planScope: string;
  subscriptionPrice?: number;
  currency?: string;
  quotaTotal?: number;
  quotaUnit?: string;
}): Promise<SubscriptionView> {
  return post<SubscriptionView>('/api/v1/admin/subscriptions', body);
}

export function listSeats(subscriptionId: string): Promise<SeatView[]> {
  return get<SeatView[]>(`/api/v1/admin/subscriptions/${subscriptionId}/seats`);
}

export function createSeat(
  subscriptionId: string,
  body: { externalSeatRef?: string; displayName?: string; assignedUserId?: string },
): Promise<SeatView> {
  return post<SeatView>(`/api/v1/admin/subscriptions/${subscriptionId}/seats`, body);
}

export function updateSeat(
  subscriptionId: string,
  seatId: string,
  body: { assignedUserId?: string; status?: string; displayName?: string },
): Promise<SeatView> {
  return patch<SeatView>(`/api/v1/admin/subscriptions/${subscriptionId}/seats/${seatId}`, body);
}
