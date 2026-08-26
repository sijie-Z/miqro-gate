/**
 * API DTO types, mirroring the Control Plane JSON contract
 * (docs/api-contract.md §3–§4). Hand-written in lockstep with the Java DTOs;
 * CI generates the OpenAPI client as the machine-readable source of truth.
 */

/** RFC 9457 problem+json error body from the Control Plane. */
export interface ProblemDetails {
  type: string;
  title: string;
  status: number;
  code: string;
  detail?: string;
  requestId: string;
  fieldErrors?: Array<{ field: string; code: string }>;
}

export type UserRole = 'SYSTEM_ADMIN' | 'USER';
export type UserStatus = 'ACTIVE' | 'DISABLED' | 'LOCKED';
export type VirtualKeyStatus = 'ACTIVE' | 'ROTATING' | 'REVOKED' | 'DISABLED';
export type VirtualKeyPurpose = 'CLAUDE_CODE' | 'CLAUDE_DESKTOP' | 'CODEX' | 'CUSTOM';
export type CacheLevel = 'UPSTREAM' | 'COALESCED' | 'L1_HIT' | 'L2_HIT';
export type UsageGroupBy = 'project' | 'virtual_key' | 'cache_level' | 'day';

export interface UserResponse {
  id: string;
  username: string;
  displayName: string;
  role: UserRole;
  status: UserStatus;
  mustChangePassword: boolean;
  lastLoginAt?: string;
  sessionExpiresAt: string;
}

export interface LoginResponse {
  id: string;
  username: string;
  displayName: string;
  role: UserRole;
  mustChangePassword: boolean;
  sessionExpiresAt: string;
}

export interface CreateVirtualKeyRequest {
  name: string;
  projectId: string;
  providerProductId: string;
  credentialGrantId: string;
  purpose: VirtualKeyPurpose;
  allowedModels?: string[];
}

export interface CreateVirtualKeyResponse {
  id: string;
  /** Plaintext Virtual Key — present exactly once, in this response only. */
  secret: string;
  baseUrl: string;
  display: string;
  shownOnce: boolean;
  createdAt: string;
  version: number;
}

export interface VirtualKeyView {
  id: string;
  name: string;
  purpose: VirtualKeyPurpose;
  status: VirtualKeyStatus;
  displayPrefix: string;
  lastFour: string;
  /** Masked display string, e.g. mqk_live_…8f2a — never the plaintext. */
  display: string;
  modelIds: string[];
  projectId: string;
  projectTag: string;
  cachePolicy: string;
  baseUrl: string;
  createdAt: string;
  lastUsedAt?: string;
  revokedAt?: string;
}

export interface MeGrantsResponse {
  projects: Array<{ id: string; code: string; name: string; projectTag: string }>;
  grants: Array<{ id: string; projectId: string; providerProductId: string; models: string[] }>;
  purposes: VirtualKeyPurpose[];
}

export interface UsageCost {
  upstreamPaid: string;
  gatewayObserved: string;
  projectAllocated: string;
  savedByGatewayCache: string;
}

export interface UsageRequests {
  upstream: number;
  coalesced: number;
  l1Hit: number;
  l2Hit: number;
}

export interface UsageTokens {
  input: number;
  output: number;
  cacheRead: number;
  cacheCreation: number;
}

export interface UsageGroup {
  groupKey: string;
  label: string;
  requests: UsageRequests;
  tokens: UsageTokens;
  cost: UsageCost;
}

export interface UsageSummary {
  groupBy: UsageGroupBy;
  groups: UsageGroup[];
  totals: UsageGroup;
}

export interface UsageRecord {
  occurredAt: string;
  modelId: string;
  cacheLevel: CacheLevel;
  inputTokens?: number;
  outputTokens?: number;
  cacheReadInputTokens?: number;
  cacheCreationInputTokens?: number;
  totalTokens?: number;
  latencyMs?: number;
  upstreamStatusCode?: number;
  providerRequestId?: string;
  gatewayRequestId?: string;
  isComplete: boolean;
  usageMissing: boolean;
  virtualKeyId: string;
}

export interface UsageRecordPage {
  items: UsageRecord[];
  page: number;
  size: number;
  total: number;
}

// ---- admin organization (G5.2) ----

export type UserStatusValue = 'ACTIVE' | 'DISABLED' | 'LOCKED';
export type TeamStatusValue = 'ACTIVE' | 'DISABLED';
export type ProjectStatusValue = 'ACTIVE' | 'DISABLED';
export type GrantStatusValue = 'ACTIVE' | 'DISABLED' | 'EXPIRED';

export interface AdminUser {
  id: string;
  username: string;
  displayName: string;
  role: UserRole;
  status: UserStatusValue;
  mustChangePassword: boolean;
  lastLoginAt?: string;
  createdAt: string;
}

export interface UserCreatedResponse {
  user: AdminUser;
  temporaryPassword: string;
}

export interface Team {
  id: string;
  name: string;
  description?: string;
  status: TeamStatusValue;
  createdAt: string;
}

export interface Project {
  id: string;
  code: string;
  name: string;
  status: ProjectStatusValue;
  projectTag?: string;
  createdAt: string;
}

export interface MemberView {
  userId: string;
  username: string;
  displayName?: string;
  createdAt: string;
}

export interface Grant {
  id: string;
  projectId: string;
  providerProductId: string;
  upstreamCredentialId: string;
  status: GrantStatusValue;
  createdAt: string;
}

export interface CredentialSummary {
  id: string;
  name: string;
  subscriptionId: string;
  status: string;
}

export interface ProviderProductView {
  id: string;
  providerSlug: string;
  providerName: string;
  productCode: string;
  displayName: string;
  billingMode: string;
  protocols: string;
  baseUrlHost: string;
  implementationStatus: string;
  balanceAuthority: string;
}

export interface Provider {
  id: string;
  slug: string;
  displayName: string;
  status: string;
}

export interface SubscriptionView {
  id: string;
  providerProductId: string;
  productName: string;
  name: string;
  billingMode: string;
  planScope: string;
  subscriptionPrice?: number;
  currency?: string;
  quotaTotal?: number;
  quotaUnit?: string;
  status: string;
  createdAt: string;
}

export interface SeatView {
  id: string;
  subscriptionId: string;
  externalSeatRef?: string;
  assignedUserId?: string;
  username?: string;
  userDisplay?: string;
  displayName?: string;
  seatStatus: string;
  createdAt: string;
}
