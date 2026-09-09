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

export type BudgetLevel = 'NORMAL' | 'WARNING' | 'EXCEEDED';

export type ModelApprovalStatus = 'PENDING' | 'APPROVED' | 'REJECTED';

export type QuotaScopeType = 'USER' | 'PROJECT';
export type QuotaMetric = 'TOKENS' | 'REQUESTS';
export type QuotaPeriod = 'DAILY' | 'WEEKLY' | 'MONTHLY';
export type QuotaRuleStatus = 'ACTIVE' | 'DISABLED';
export type QuotaLevel = 'NORMAL' | 'WARNING' | 'EXCEEDED';

export type McpAclMode = 'NONE' | 'ALLOW' | 'DENY';

// ---- admin organization (G5.2) ----

export type UserStatusValue = 'ACTIVE' | 'DISABLED' | 'LOCKED';
export type TeamStatusValue = 'ACTIVE' | 'DISABLED';
export type ProjectStatusValue = 'ACTIVE' | 'DISABLED';
export type GrantStatusValue = 'ACTIVE' | 'DISABLED' | 'EXPIRED';

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

// ---- admin usage / export / deletion / webhook / alert / audit (G5.4) ----

export type AlertRuleType =
  | 'USAGE_MISSING_RATE'
  | 'UPSTREAM_ERROR_RATE'
  | 'BALANCE_UNAVAILABLE'
  | 'USAGE_SURGE'
  | 'BUDGET_THRESHOLD'
  | 'QUOTA_THRESHOLD'
  | 'MODEL_APPROVAL_SUBMITTED'
  | 'MODEL_APPROVAL_APPROVED'
  | 'MODEL_APPROVAL_REJECTED';
