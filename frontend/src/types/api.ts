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

export interface InternalServiceView {
  id: string;
  name: string;
  kind: string;
  description?: string;
  baseUrl: string;
  status: string;
  createdAt: string;
}

export interface ConfigEntryView {
  id: string;
  groupName: string;
  key: string;
  value: string;
  description?: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface McpServiceView {
  id: string;
  name: string;
  description?: string;
  endpoint: string;
  transport: string;
  status: string;
  healthStatus: string;
  healthCheckedAt: string | null;
  checkIntervalSeconds: number;
  checkTimeoutSeconds: number;
  failThreshold: number;
  recoverThreshold: number;
  checkPath: string;
  createdAt: string;
}

export interface McpToolView {
  id: string;
  mcpServiceId: string;
  toolName: string;
  description?: string;
  method: string;
  path: string;
  status: string;
  createdAt: string;
}

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

export interface MeGrantsResponse {
  projects: Array<{ id: string; code: string; name: string; projectTag: string }>;
  grants: Array<{ id: string; projectId: string; providerProductId: string; models: string[] }>;
  purposes: VirtualKeyPurpose[];
}

export type ModelApprovalStatus = 'PENDING' | 'APPROVED' | 'REJECTED';

export type QuotaScopeType = 'USER' | 'PROJECT';
export type QuotaMetric = 'TOKENS' | 'REQUESTS';
export type QuotaPeriod = 'DAILY' | 'WEEKLY' | 'MONTHLY';
export type QuotaRuleStatus = 'ACTIVE' | 'DISABLED';
export type QuotaLevel = 'NORMAL' | 'WARNING' | 'EXCEEDED';

/** Global default quota strategy (Tencent doc 135489) — nulls before first config. */
export interface QuotaDefaultTemplateView {
  enabled: boolean;
  metric: QuotaMetric | null;
  period: QuotaPeriod | null;
  limitValue: number | null;
  version: number;
  updatedAt: string | null;
}

export interface RoiReportView {
  from: string;
  to: string;
  totals: {
    upstreamRequests: number;
    coalescedRequests: number;
    l1Hits: number;
    l2Hits: number;
    hitRatePct: number;
    paidCost: number;
    savedCost: number;
    savedPct: number;
  };
  byDay: Array<{
    date: string;
    upstreamRequests: number;
    hitRequests: number;
    hitRatePct: number;
    paidCost: number;
    savedCost: number;
  }>;
}

export type McpAclMode = 'NONE' | 'ALLOW' | 'DENY';

export interface McpAccessView {
  serviceId: string;
  serviceName: string;
  mode: McpAclMode;
  serverConsumers: Array<{ id: string; name: string }>;
  tools: Array<{
    toolId: string;
    toolName: string;
    /** Override mode; null = inherit the server rule. */
    mode: McpAclMode | null;
    consumers: Array<{ id: string; name: string }>;
  }>;
}

// ---- MCP route rules (F11, Tencent doc 135482) ----

export interface McpHeaderCondition {
  name: string;
  mode: 'EXACT' | 'PREFIX' | 'REGEX';
  value: string;
}

export interface McpRouteRule {
  id: string;
  mcpServiceId: string;
  name: string;
  description?: string;
  priority: number;
  /** Null matcher fields mean unrestricted. */
  pathMode?: 'EXACT' | 'PREFIX' | 'REGEX' | null;
  pathValue?: string | null;
  hostMode?: 'EXACT' | 'PREFIX' | 'REGEX' | null;
  hostValue?: string | null;
  /** Comma-joined method whitelist (e.g. "GET,POST"); null = unrestricted. */
  methods?: string | null;
  headerConditions: McpHeaderCondition[];
  status: 'ENABLED' | 'DISABLED';
  version: number;
  createdAt: string;
}

export interface UpsertMcpRouteRuleRequest {
  name: string;
  description?: string;
  priority?: number;
  pathMode?: 'EXACT' | 'PREFIX' | 'REGEX' | null;
  pathValue?: string | null;
  hostMode?: 'EXACT' | 'PREFIX' | 'REGEX' | null;
  hostValue?: string | null;
  methods?: string[] | null;
  headers?: McpHeaderCondition[] | null;
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

export interface UserProjectMembership {
  projectId: string;
  projectCode: string;
  projectName: string;
  projectStatus: ProjectStatusValue;
  joinedAt: string;
}

export interface Grant {
  id: string;
  projectId: string;
  providerProductId: string;
  upstreamCredentialId: string;
  status: GrantStatusValue;
  createdAt: string;
}

export interface ApiConsumerView {
  id: string;
  name: string;
  keyPrefix: string;
  status: string;
  createdAt: string;
}

export interface CreateApiConsumerResponse {
  consumer: ApiConsumerView;
  apiKey: string;
  shownOnce: boolean;
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

// ---- admin usage / export / deletion / webhook / alert / audit (G5.4) ----

export interface ExportTask {
  id: string;
  format: 'CSV' | 'JSONL';
  periodFrom: string;
  periodTo: string;
  status: 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'EXPIRED';
  sha256?: string;
  rowCount?: number;
  byteCount?: number;
  errorMessage?: string;
  createdAt: string;
  finishedAt?: string;
  expiresAt?: string;
}

export interface UsageDeletionRequest {
  id: string;
  periodFrom: string;
  periodTo: string;
  previewCount: number;
  status: 'PENDING_CONFIRMATION' | 'CONFIRMED' | 'EXECUTED' | 'EXPIRED';
  deletedCount?: number;
  executedAt?: string;
  expiresAt: string;
  createdAt: string;
}

export interface WebhookEndpointView {
  id: string;
  name: string;
  url: string;
  enabled: boolean;
  timeoutMs: number;
  createdAt: string;
}

export interface WebhookDelivery {
  id: string;
  eventId: string;
  endpointId: string;
  attempt: number;
  httpStatus?: number;
  nextRetryAt?: string;
  errorMessage?: string;
  createdAt: string;
}

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

export interface AlertRule {
  id: string;
  name: string;
  type: AlertRuleType;
  scopeJson?: string;
  threshold: number;
  dedupeMinutes: number;
  enabled: boolean;
  webhookEndpointId?: string;
  createdAt: string;
}

