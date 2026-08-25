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
