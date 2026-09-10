/** * /api/v1/auth and /api/v1/me endpoint clients (api-contract.md §3–§4). */ import {
  ApiError,
  del,
  downloadBlob,
  get,
  patch,
  post,
  put,
  uploadBytes,
} from './http';
import type {
  McpAclMode,
  ModelApprovalStatus,
  ProviderProductView,
  UsageGroupBy,
  UserRole,
  UserStatusValue,
} from '@/types/api';
import type {
  AgentView,
  AlertRule,
  ApiConsumerView,
  AuditEventView,
  BudgetView,
  ConfigEntryView,
  CreateVirtualKeyResponse,
  CredentialDetailView,
  CredentialView,
  ExportTask,
  InternalServiceView,
  McpAccessView,
  McpRouteRule,
  McpServiceView,
  McpToolView,
  MeGrantsResponse,
  ModelApprovalPage,
  ModelApprovalView,
  PriceSnapshotView,
  Project,
  Provider,
  QuotaDefaultTemplateView,
  QuotaRuleView,
  RoiReportView,
  SeatView,
  SkillView,
  SubscriptionView,
  Team,
  UpsertMcpRouteRuleRequest,
  AdminUser,
  CreateApiConsumerResponse,
  Grant,
  LoginResponse,
  McpToolRevisionRow,
  MemberView,
  ToolImportResult,
  UsageDeletionRequest,
  ModelCatalogRow,
  UsageRecordPage,
  UserCreatedResponse,
  UserProjectMembership,
  UserResponse,
  UsageSummary,
  ValidateCredentialResponse,
  VirtualKeyView,
  WebhookDelivery,
  WebhookEndpointView,
  McpAccessLogEntry,
  McpResiliencePolicy,
} from '@/types/generated-api';
import type { components } from '@/types/generated';

// Stage-2 codegen migration (batch 1): request DTOs now alias the OpenAPI
// schema types instead of handwritten duplicates.
type ConfigureQuotaDefaultTemplateRequest =
  components['schemas']['ConfigureQuotaDefaultTemplateRequest'];
type SetMcpAccessGrantsRequest = components['schemas']['SetMcpAccessGrantsRequest'];
type SubmitModelApprovalRequest = components['schemas']['SubmitModelApprovalRequest'];
type UpsertQuotaRuleRequest = components['schemas']['UpsertQuotaRuleRequest'];
// The spec leaves name optional here (server derives/validates); the schema is
// the authority after this migration.
type CreateVirtualKeyRequest = components['schemas']['CreateVirtualKeyRequest'];

// ---- auth ----

export interface OAuthProviderInfo {
  code: string;
  name: string;
}

export function publicOauthProviders(): Promise<OAuthProviderInfo[]> {
  return get<OAuthProviderInfo[]>('/api/v1/auth/oauth/providers');
}

export function login(username: string, password: string): Promise<LoginResponse> {
  return post<LoginResponse>('/api/v1/auth/login', { username, password });
}

export function register(
  username: string,
  displayName: string | undefined,
  password: string,
): Promise<LoginResponse> {
  return post<LoginResponse>('/api/v1/auth/register', { username, displayName, password });
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

// ---- self-service model approvals (model-approval workflow) ----

export function submitModelApproval(
  request: SubmitModelApprovalRequest,
): Promise<ModelApprovalView> {
  return post<ModelApprovalView>('/api/v1/me/model-approvals', request);
}

export function listMyModelApprovals(): Promise<ModelApprovalView[]> {
  return get<ModelApprovalView[]>('/api/v1/me/model-approvals');
}

// ---- self-service quota visibility (F04) ----

export function listMyQuotaRules(): Promise<QuotaRuleView[]> {
  return get<QuotaRuleView[]>('/api/v1/me/quota-rules');
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

export function adminUserProjectMemberships(userId: string): Promise<UserProjectMembership[]> {
  return get<UserProjectMembership[]>(`/api/v1/admin/users/${userId}/project-memberships`);
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

// ---- admin model-approval queue ----

export function listModelApprovals(
  params: {
    status?: ModelApprovalStatus;
    size?: number;
    before?: string;
  } = {},
): Promise<ModelApprovalPage> {
  return get<ModelApprovalPage>('/api/v1/admin/model-approvals', params);
}

export function approveModelApproval(id: string, reviewNote?: string): Promise<ModelApprovalView> {
  return post<ModelApprovalView>(
    `/api/v1/admin/model-approvals/${id}/approve`,
    reviewNote ? { reviewNote } : {},
  );
}

export function rejectModelApproval(id: string, reviewNote?: string): Promise<ModelApprovalView> {
  return post<ModelApprovalView>(
    `/api/v1/admin/model-approvals/${id}/reject`,
    reviewNote ? { reviewNote } : {},
  );
}

// ---- admin quota rules (usage quota plans, alerting-only) ----

export function listQuotaRules(): Promise<QuotaRuleView[]> {
  return get<QuotaRuleView[]>('/api/v1/admin/quota-rules');
}

export function putQuotaRule(request: UpsertQuotaRuleRequest): Promise<QuotaRuleView> {
  return put<QuotaRuleView>('/api/v1/admin/quota-rules', request);
}

export function deleteQuotaRule(id: string): Promise<void> {
  return del<void>(`/api/v1/admin/quota-rules/${id}`);
}

// ---- admin default quota template (Tencent doc 135489) ----

export function getQuotaDefaultTemplate(): Promise<QuotaDefaultTemplateView> {
  return get<QuotaDefaultTemplateView>('/api/v1/admin/quota-default-template');
}

export function putQuotaDefaultTemplate(
  request: ConfigureQuotaDefaultTemplateRequest,
): Promise<QuotaDefaultTemplateView> {
  return put<QuotaDefaultTemplateView>('/api/v1/admin/quota-default-template', request);
}

export function enableQuotaDefaultTemplate(): Promise<QuotaDefaultTemplateView> {
  return post<QuotaDefaultTemplateView>('/api/v1/admin/quota-default-template/enable');
}

export function disableQuotaDefaultTemplate(): Promise<QuotaDefaultTemplateView> {
  return post<QuotaDefaultTemplateView>('/api/v1/admin/quota-default-template/disable');
}

// ---- admin cache-ROI report (P5.4) ----

export function getRoiReport(from?: string, to?: string): Promise<RoiReportView> {
  return get<RoiReportView>('/api/v1/admin/usage/roi', { from, to });
}

// ---- MCP two-level access control (Tencent doc 134890) ----

export function listMcpAccessLogs(params?: {
  service?: string;
  consumer?: string;
}): Promise<McpAccessLogEntry[]> {
  const query: Record<string, string> = {};
  if (params?.service) query.service = params.service;
  if (params?.consumer) query.consumer = params.consumer;
  return get<McpAccessLogEntry[]>('/api/v1/admin/mcp-access-logs', query);
}

export function getMcpServiceAccess(serviceId: string): Promise<McpAccessView> {
  return get<McpAccessView>(`/api/v1/admin/mcp-services/${serviceId}/access`);
}

export function setMcpAccessMode(serviceId: string, mode: McpAclMode): Promise<McpAccessView> {
  return put<McpAccessView>(`/api/v1/admin/mcp-services/${serviceId}/access/mode`, { mode });
}

export function setMcpAccessGrants(
  serviceId: string,
  request: SetMcpAccessGrantsRequest,
): Promise<McpAccessView> {
  return put<McpAccessView>(`/api/v1/admin/mcp-services/${serviceId}/access/grants`, request);
}

export function clearMcpAccessGrants(serviceId: string, toolId?: string): Promise<McpAccessView> {
  const query = toolId ? `?toolId=${encodeURIComponent(toolId)}` : '';
  return del<McpAccessView>(`/api/v1/admin/mcp-services/${serviceId}/access/grants${query}`);
}

export function listCredentials(): Promise<CredentialView[]> {
  return get<CredentialView[]>('/api/v1/admin/credentials');
}

export function getCredential(id: string): Promise<CredentialDetailView> {
  return get<CredentialDetailView>(`/api/v1/admin/credentials/${id}`);
}

export function createCredential(body: {
  name: string;
  subscriptionId: string;
  secret: string;
}): Promise<CredentialView> {
  return post<CredentialView>('/api/v1/admin/credentials', body);
}

export function validateCredential(
  id: string,
  body: { secret: string },
): Promise<ValidateCredentialResponse> {
  return post<ValidateCredentialResponse>(`/api/v1/admin/credentials/${id}/validate`, body);
}

export function rotateCredential(id: string, body: { secret: string }): Promise<CredentialView> {
  return post<CredentialView>(`/api/v1/admin/credentials/${id}/rotate`, body);
}

export function disableCredential(id: string): Promise<{ message: string }> {
  return post<{ message: string }>(`/api/v1/admin/credentials/${id}/disable`);
}

export function listPrices(): Promise<PriceSnapshotView[]> {
  return get<PriceSnapshotView[]>('/api/v1/admin/prices');
}

export function createPrice(body: {
  providerProductId: string;
  modelId: string;
  tokenType: string;
  currency: string;
  unitPrice: string;
  source: string;
}): Promise<PriceSnapshotView> {
  return post<PriceSnapshotView>('/api/v1/admin/prices', body);
}

export function listApiConsumers(): Promise<ApiConsumerView[]> {
  return get<ApiConsumerView[]>('/api/v1/admin/api-consumers');
}

export function createApiConsumer(
  name: string,
  expiresAt?: string,
): Promise<CreateApiConsumerResponse> {
  return post<CreateApiConsumerResponse>('/api/v1/admin/api-consumers', { name, expiresAt });
}

export function disableApiConsumer(id: string): Promise<ApiConsumerView> {
  return post<ApiConsumerView>(`/api/v1/admin/api-consumers/${id}/disable`);
}

/** Issue #338 (I5): per-consumer MCP call overview from the access log. */
export interface ApiConsumerActivity {
  consumerId: string;
  windowHours: number;
  totalCalls: number;
  forwarded: number;
  denied: number;
  failed: number;
  lastCallAt: string | null;
  topTools: Array<{ name: string; calls: number }>;
  topServices: Array<{ name: string; calls: number }>;
}

export function adminConsumerActivity(id: string, hours = 24): Promise<ApiConsumerActivity> {
  return get<ApiConsumerActivity>(`/api/v1/admin/api-consumers/${id}/activity`, { hours });
}

/** Replaces the channel scope: null = full access, empty array = no channels. */
export function updateApiConsumerScope(
  id: string,
  capabilities: string[] | null,
): Promise<ApiConsumerView> {
  return patch<ApiConsumerView>(`/api/v1/admin/api-consumers/${id}/scope`, { capabilities });
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

// ---- SkillHub (P2.4) ----

export function listSkills(q?: string, tags?: string[]): Promise<SkillView[]> {
  return get<SkillView[]>(`/api/v1/skills${skillQuery(q, tags)}`);
}

/** Keyword (name/description/ID) + tag filters shared by the market and admin lists. */
function skillQuery(q?: string, tags?: string[]): string {
  const params = new URLSearchParams();
  const keyword = q?.trim();
  if (keyword) params.set('q', keyword);
  for (const tag of tags ?? []) params.append('tags', tag);
  const qs = params.toString();
  return qs ? `?${qs}` : '';
}

export function getSkill(id: string): Promise<SkillView> {
  return get<SkillView>(`/api/v1/skills/${id}`);
}

/** Downloads the skill package; throws ApiError (403 SKILL_DOWNLOAD_FORBIDDEN). */
export async function downloadSkill(id: string, filename: string): Promise<void> {
  const blob = await downloadBlob(`/api/v1/skills/${id}/download`);
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `${filename}.zip`;
  a.click();
  URL.revokeObjectURL(url);
}

export function adminListSkills(q?: string, tags?: string[]): Promise<SkillView[]> {
  return get<SkillView[]>(`/api/v1/admin/skills${skillQuery(q, tags)}`);
}

export function adminUploadSkill(version: string, zip: Blob): Promise<SkillView> {
  return uploadBytes<SkillView>(`/api/v1/admin/skills?version=${encodeURIComponent(version)}`, zip);
}

export function adminArchiveSkill(id: string): Promise<SkillView> {
  return post<SkillView>(`/api/v1/admin/skills/${id}/archive`);
}

export function adminSetSkillAccess(
  id: string,
  scopes: Array<{ scopeType: string; scopeId: string }>,
): Promise<void> {
  return put<void>(`/api/v1/admin/skills/${id}/access`, scopes);
}

// ---- agents (P3.1) ----

export function adminListAgents(): Promise<AgentView[]> {
  return get<AgentView[]>('/api/v1/admin/agents');
}

export function adminCreateAgent(body: {
  name: string;
  description?: string;
  credentialId: string;
}): Promise<AgentView> {
  return post<AgentView>('/api/v1/admin/agents', body);
}

export function adminDisableAgent(id: string): Promise<AgentView> {
  return post<AgentView>(`/api/v1/admin/agents/${id}/disable`);
}

export function adminAgentUsage(id: string): Promise<UsageSummary> {
  return get<UsageSummary>(`/api/v1/admin/agents/${id}/usage`);
}

// ---- internal services (P3.2) ----

export function adminListServices(): Promise<InternalServiceView[]> {
  return get<InternalServiceView[]>('/api/v1/admin/services');
}

export function adminCreateService(body: {
  name: string;
  kind?: string;
  description?: string;
  baseUrl: string;
}): Promise<InternalServiceView> {
  return post<InternalServiceView>('/api/v1/admin/services', body);
}

export function adminDisableService(id: string): Promise<InternalServiceView> {
  return post<InternalServiceView>(`/api/v1/admin/services/${id}/disable`);
}

/** #326: re-enable a disabled registry service (mirror of disable). */
export function adminEnableService(id: string): Promise<InternalServiceView> {
  return post<InternalServiceView>(`/api/v1/admin/services/${id}/enable`);
}

/** #326: partial health-probe configuration update (mirror of the MCP endpoint). */
export function adminUpdateServiceHealthConfig(
  id: string,
  body: {
    checkIntervalSeconds?: number;
    checkTimeoutSeconds?: number;
    failThreshold?: number;
    recoverThreshold?: number;
    checkPath?: string;
  },
): Promise<InternalServiceView> {
  return post<InternalServiceView>(`/api/v1/admin/services/${id}/health-config`, body);
}

// ---- global config (P3.3) ----

export function adminListConfigs(group?: string): Promise<ConfigEntryView[]> {
  const params = group ? `?group=${encodeURIComponent(group)}` : '';
  return get<ConfigEntryView[]>(`/api/v1/admin/configs${params}`);
}

export function adminPutConfig(body: {
  group: string;
  key: string;
  value: string;
  description?: string;
}): Promise<ConfigEntryView> {
  return put<ConfigEntryView>('/api/v1/admin/configs', body);
}

export function adminDeleteConfig(group: string, key: string): Promise<void> {
  return del<void>(`/api/v1/admin/configs/${encodeURIComponent(group)}/${encodeURIComponent(key)}`);
}

// ---- MCP services (P3.4) ----

export function adminListMcpServices(): Promise<McpServiceView[]> {
  return get<McpServiceView[]>('/api/v1/admin/mcp-services');
}

export function adminCreateMcpService(body: {
  name: string;
  description?: string;
  endpoint: string;
  transport?: string;
  checkIntervalSeconds?: number;
  checkTimeoutSeconds?: number;
  failThreshold?: number;
  recoverThreshold?: number;
  checkPath?: string;
}): Promise<McpServiceView> {
  return post<McpServiceView>('/api/v1/admin/mcp-services', body);
}

export function adminSetMcpStatus(id: string, status: string): Promise<McpServiceView> {
  return post<McpServiceView>(`/api/v1/admin/mcp-services/${id}/status?status=${status}`);
}

export interface McpResilienceDraft {
  retryEnabled?: boolean;
  retryMax?: number;
  retryConditions?: string[];
  idempotencyConfirmed?: boolean;
  breakerEnabled?: boolean;
  breakerWindowSeconds?: number;
  breakerMinRequests?: number;
  breakerErrorEnabled?: boolean;
  breakerErrorRatio?: number;
  breakerErrorStatusCodes?: number[];
  breakerSlowEnabled?: boolean;
  breakerSlowCallMs?: number;
  breakerSlowRatio?: number;
  breakerOpenSeconds?: number;
  breakerProbeCount?: number;
  breakerProbeSuccess?: number;
  breakerSkipRetry?: boolean;
}

export function getMcpServiceResilience(id: string): Promise<McpResiliencePolicy> {
  return get<McpResiliencePolicy>(`/api/v1/admin/mcp-services/${id}/resilience`);
}

export function putMcpServiceResilience(
  id: string,
  body: McpResilienceDraft,
): Promise<McpResiliencePolicy> {
  return put<McpResiliencePolicy>(`/api/v1/admin/mcp-services/${id}/resilience`, body);
}

export function adminUpdateMcpHealthConfig(
  id: string,
  body: {
    checkIntervalSeconds?: number;
    checkTimeoutSeconds?: number;
    failThreshold?: number;
    recoverThreshold?: number;
    checkPath?: string;
  },
): Promise<McpServiceView> {
  return post<McpServiceView>(`/api/v1/admin/mcp-services/${id}/health-config`, body);
}

/**
 * #320 upstream backend auth: VISITOR clears any stored secret; API_KEY
 * requires a non-blank write-only secret (never returned by any read surface).
 */
export function adminSetMcpBackendAuth(
  id: string,
  body: { mode: 'VISITOR' | 'API_KEY'; secret?: string },
): Promise<McpServiceView> {
  return put<McpServiceView>(`/api/v1/admin/mcp-services/${id}/backend-auth`, body);
}

export function adminListMcpTools(serviceId: string): Promise<McpToolView[]> {
  return get<McpToolView[]>(`/api/v1/admin/mcp-services/${serviceId}/tools`);
}

/** Tools/list sync report (#344, doc 03): per-item diff, previewed or applied. */
export interface McpToolSyncReport {
  dryRun: boolean;
  upstreamToolCount: number;
  added: string[];
  updated: string[];
  unchanged: number;
  absentUpstream: string[];
  skipped: Array<{ toolName: string; reason: string }>;
}

export function adminSyncMcpTools(serviceId: string, dryRun = false): Promise<McpToolSyncReport> {
  return post<McpToolSyncReport>(
    `/api/v1/admin/mcp-services/${serviceId}/tools/sync?dryRun=${dryRun}`,
  );
}

export function adminCreateMcpTool(
  serviceId: string,
  body: { toolName: string; description?: string; method?: string; path: string },
): Promise<McpToolView> {
  return post<McpToolView>(`/api/v1/admin/mcp-services/${serviceId}/tools`, body);
}

export function adminSetMcpToolStatus(
  serviceId: string,
  toolId: string,
  status: string,
): Promise<McpToolView> {
  return post<McpToolView>(
    `/api/v1/admin/mcp-services/${serviceId}/tools/${toolId}/status?status=${status}`,
  );
}

// ---- F16 tool definition versioning (V33) ----

export function adminListToolRevisions(
  serviceId: string,
  toolId: string,
): Promise<McpToolRevisionRow[]> {
  return get<McpToolRevisionRow[]>(
    `/api/v1/admin/mcp-services/${serviceId}/tools/${toolId}/revisions`,
  );
}

export function adminActivateToolRevision(
  serviceId: string,
  toolId: string,
  revision: number,
): Promise<McpToolRevisionRow> {
  return post<McpToolRevisionRow>(
    `/api/v1/admin/mcp-services/${serviceId}/tools/${toolId}/revisions/${revision}/activate`,
  );
}

export function adminPublishToolRevision(
  serviceId: string,
  toolId: string,
  body: { description?: string; method?: string; path?: string },
): Promise<McpToolRevisionRow> {
  return post<McpToolRevisionRow>(
    `/api/v1/admin/mcp-services/${serviceId}/tools/${toolId}/revisions`,
    body,
  );
}

export function adminImportMcpTools(serviceId: string, spec: unknown): Promise<ToolImportResult> {
  return post<ToolImportResult>(`/api/v1/admin/mcp-services/${serviceId}/tools/import`, spec);
}

// ---- F18 model catalog manual maintenance (V34) ----

export function adminListModels(productId?: string, source?: string): Promise<ModelCatalogRow[]> {
  const params = new URLSearchParams();
  if (productId) params.set('providerProductId', productId);
  if (source) params.set('source', source);
  const qs = params.toString();
  return get<ModelCatalogRow[]>(`/api/v1/admin/models${qs ? `?${qs}` : ''}`);
}

export function adminCreateModel(
  productId: string,
  body: { modelId: string; displayName?: string; contextWindow?: number; maxOutputTokens?: number },
): Promise<ModelCatalogRow> {
  return post<ModelCatalogRow>('/api/v1/admin/models', { providerProductId: productId, ...body });
}

export function adminDeleteModel(rowId: string): Promise<void> {
  return del<void>(`/api/v1/admin/models/${rowId}`);
}

/** Model probe (#346, I4, doc 05): admin-triggered official /models fetch. */
export interface ModelProbeReport {
  providerProductId: string;
  productCode: string;
  modelCount: number;
  probedAt: string;
  models: Array<{ modelId: string; displayName: string }>;
}

/** Last probe outcome for a product; all-null when never probed. */
export interface ModelProbeStatus {
  status: 'SUCCEEDED' | 'FAILED' | null;
  error: string | null;
  modelCount: number | null;
  probedAt: string | null;
}

export function adminProbeModels(providerProductId: string): Promise<ModelProbeReport> {
  return post<ModelProbeReport>('/api/v1/admin/models/probe', { providerProductId });
}

export function adminModelProbeStatus(providerProductId: string): Promise<ModelProbeStatus> {
  return get<ModelProbeStatus>('/api/v1/admin/models/probe-status', { providerProductId });
}

// ---- MCP route rules (F11, Tencent doc 135482) ----

export function adminListMcpRouteRules(serviceId: string): Promise<McpRouteRule[]> {
  return get<McpRouteRule[]>(`/api/v1/admin/mcp-services/${serviceId}/route-rules`);
}

export function adminCreateMcpRouteRule(
  serviceId: string,
  body: UpsertMcpRouteRuleRequest,
): Promise<McpRouteRule> {
  return post<McpRouteRule>(`/api/v1/admin/mcp-services/${serviceId}/route-rules`, body);
}

export function adminUpdateMcpRouteRule(
  serviceId: string,
  ruleId: string,
  body: UpsertMcpRouteRuleRequest,
): Promise<McpRouteRule> {
  return patch<McpRouteRule>(`/api/v1/admin/mcp-services/${serviceId}/route-rules/${ruleId}`, body);
}

export function adminSetMcpRouteStatus(
  serviceId: string,
  ruleId: string,
  status: 'ENABLED' | 'DISABLED',
): Promise<McpRouteRule> {
  return post<McpRouteRule>(
    `/api/v1/admin/mcp-services/${serviceId}/route-rules/${ruleId}/status?status=${status}`,
  );
}

export function adminDeleteMcpRouteRule(serviceId: string, ruleId: string): Promise<void> {
  return del<void>(`/api/v1/admin/mcp-services/${serviceId}/route-rules/${ruleId}`);
}

// ---- admin usage / export / deletion / webhook / alert / audit (G5.4) ----

// ---- admin budget (G8.2) ----

export function adminBudgets(month?: string): Promise<BudgetView[]> {
  const params = month ? `?month=${encodeURIComponent(month)}` : '';
  return get<BudgetView[]>(`/api/v1/admin/budgets${params}`);
}

export function putProjectBudget(
  projectId: string,
  body: { month: string; amount: number; currency?: string; alertThresholdPct?: number },
): Promise<BudgetView> {
  return put<BudgetView>(`/api/v1/admin/projects/${projectId}/budget`, body);
}

export function deleteProjectBudget(projectId: string, month?: string): Promise<void> {
  const params = month ? `?month=${encodeURIComponent(month)}` : '';
  return del(`/api/v1/admin/projects/${projectId}/budget${params}`);
}

export function adminUsageSummary(query: {
  groupBy?: string;
  from?: string;
  to?: string;
  userId?: string;
  projectId?: string;
  virtualKeyId?: string;
  credentialId?: string;
  subscriptionId?: string;
  providerProductId?: string;
  modelId?: string;
}): Promise<UsageSummary> {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value) params.set(key, value);
  }
  return get<UsageSummary>(`/api/v1/admin/usage/summary?${params.toString()}`);
}

export function adminUsageRecords(query: {
  from?: string;
  to?: string;
  page?: number;
  size?: number;
  userId?: string;
  projectId?: string;
  modelId?: string;
}): Promise<UsageRecordPage> {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== '') params.set(key, String(value));
  }
  return get<UsageRecordPage>(`/api/v1/admin/usage/records?${params.toString()}`);
}

export function createExport(
  format: 'CSV' | 'JSONL',
  from: string,
  to: string,
): Promise<ExportTask> {
  return post<ExportTask>(
    `/api/v1/admin/exports?format=${format}&from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`,
  );
}

export function exportStatus(id: string): Promise<ExportTask> {
  return get<ExportTask>(`/api/v1/admin/exports/${id}`);
}

export function exportRecent(): Promise<ExportTask[]> {
  return get<ExportTask[]>('/api/v1/admin/exports?limit=20');
}

export function deletionPreview(from: string, to: string): Promise<{ count: number }> {
  return get<{ count: number }>(
    `/api/v1/admin/usage-deletions/preview?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`,
  );
}

export function createDeletion(
  from: string,
  to: string,
): Promise<{
  id: string;
  previewCount: number;
  confirmToken: string;
  expiresAt: string;
}> {
  return post<{ id: string; previewCount: number; confirmToken: string; expiresAt: string }>(
    `/api/v1/admin/usage-deletions?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`,
  );
}

export function confirmDeletion(id: string, confirmToken: string): Promise<UsageDeletionRequest> {
  return post<UsageDeletionRequest>(`/api/v1/admin/usage-deletions/${id}/confirm`, {
    confirmToken,
  });
}

export function deletionRecent(): Promise<UsageDeletionRequest[]> {
  return get<UsageDeletionRequest[]>('/api/v1/admin/usage-deletions?limit=20');
}

export function listWebhooks(): Promise<WebhookEndpointView[]> {
  return get<WebhookEndpointView[]>('/api/v1/admin/webhooks');
}

export function createWebhook(body: {
  name: string;
  url: string;
  secret: string;
  timeoutMs?: number;
}): Promise<WebhookEndpointView> {
  return post<WebhookEndpointView>('/api/v1/admin/webhooks', body);
}

export function updateWebhook(
  id: string,
  body: { name?: string; enabled?: boolean; timeoutMs?: number },
): Promise<WebhookEndpointView> {
  return patch<WebhookEndpointView>(`/api/v1/admin/webhooks/${id}`, body);
}

export function deleteWebhook(id: string): Promise<void> {
  return del<void>(`/api/v1/admin/webhooks/${id}`);
}

export function testWebhook(id: string): Promise<{ httpStatus?: number; errorMessage?: string }> {
  return post<{ httpStatus?: number; errorMessage?: string }>(`/api/v1/admin/webhooks/${id}/test`);
}

export function webhookDeliveries(id: string): Promise<WebhookDelivery[]> {
  return get<WebhookDelivery[]>(`/api/v1/admin/webhooks/${id}/deliveries?limit=20`);
}

export function listAlertRules(): Promise<AlertRule[]> {
  return get<AlertRule[]>('/api/v1/admin/alert-rules');
}

export function createAlertRule(body: {
  name: string;
  type: string;
  threshold: number;
  dedupeMinutes?: number;
  webhookEndpointId?: string;
  scopeJson?: string;
}): Promise<AlertRule> {
  return post<AlertRule>('/api/v1/admin/alert-rules', body);
}

export function updateAlertRule(
  id: string,
  body: {
    name?: string;
    threshold?: number;
    dedupeMinutes?: number;
    enabled?: boolean;
    webhookEndpointId?: string;
    scopeJson?: string;
  },
): Promise<AlertRule> {
  return patch<AlertRule>(`/api/v1/admin/alert-rules/${id}`, body);
}

export function deleteAlertRule(id: string): Promise<void> {
  return del<void>(`/api/v1/admin/alert-rules/${id}`);
}

/** Audit record query filters shared by the list and CSV export endpoints. */
export interface AuditQuery {
  size?: number;
  action?: string;
  targetType?: string;
  actorId?: string;
  /** ISO-8601 instants (UTC), same semantics as the backend TIME_RANGE_INVALID check. */
  from?: string;
  to?: string;
}

export function auditEvents(query: AuditQuery): Promise<AuditEventView[]> {
  const params = new URLSearchParams();
  if (query.size) params.set('size', String(query.size));
  if (query.action) params.set('action', query.action);
  if (query.targetType) params.set('targetType', query.targetType);
  if (query.actorId) params.set('actorId', query.actorId);
  if (query.from) params.set('from', query.from);
  if (query.to) params.set('to', query.to);
  return get<AuditEventView[]>(`/api/v1/admin/audit-events?${params.toString()}`);
}

export interface AuditCsvExport {
  csv: string;
  truncated: boolean;
}

/**
 * Downloads the filtered audit chain as a compliance CSV. The backend truncates
 * at 50k rows and declares it via {@code X-MiQroKey-Truncated}; the caller
 * surfaces that instead of silently handing over an incomplete file.
 */
export async function exportAuditCsv(query: Omit<AuditQuery, 'size'>): Promise<AuditCsvExport> {
  const params = new URLSearchParams();
  if (query.action) params.set('action', query.action);
  if (query.targetType) params.set('targetType', query.targetType);
  if (query.actorId) params.set('actorId', query.actorId);
  if (query.from) params.set('from', query.from);
  if (query.to) params.set('to', query.to);
  const response = await fetch(`/api/v1/admin/audit-events/export?${params.toString()}`, {
    headers: { Accept: 'text/csv' },
    credentials: 'include',
  });
  if (!response.ok) {
    let details:
      { detail?: string; code?: string; status?: number; requestId?: string } | undefined;
    try {
      details = (await response.json()) as typeof details;
    } catch {
      // Not JSON — generic error below.
    }
    throw new ApiError({
      type: 'about:blank',
      title: '导出失败',
      status: response.status,
      code: details?.code ?? 'HTTP_ERROR',
      detail: details?.detail,
      requestId: details?.requestId ?? '',
    });
  }
  return {
    csv: await response.text(),
    truncated: response.headers.get('X-MiQroKey-Truncated') === 'true',
  };
}

// ---------------------------------------------------------------------------
// Bill reconciliation (F19, coverage-matrix I2). The endpoints return Maps, so
// the DTOs live here next to their clients (same pattern as ApiConsumerActivity).
// ---------------------------------------------------------------------------

export type ReconciliationStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED';
export type ReconciliationVerdict =
  | 'MATCHED'
  | 'PARTIAL'
  | 'UNMATCHED_PROVIDER'
  | 'UNMATCHED_LOCAL';

/** Report metadata view; identical shape for create, list entries and GET /{id}. */
export interface ReconciliationReport {
  id: string;
  providerCode: string;
  currency: string;
  windowFrom: string;
  windowTo: string;
  status: ReconciliationStatus;
  uploadSha256?: string | null;
  uploadBytes?: number | null;
  totalRows?: number | null;
  matched?: number | null;
  partialBuckets?: number | null;
  unmatchedProvider?: number | null;
  unmatchedLocal?: number | null;
  lineErrorCount?: number | null;
  amountDiff?: string | null;
  errorMessage?: string | null;
  createdBy?: string | null;
  createdAt: string;
  finishedAt?: string | null;
}

/** One four-state detail row; `detail` carries per-verdict context fields. */
export interface ReconciliationRow {
  rowNo: number;
  verdict: ReconciliationVerdict;
  matchedBy?: string | null;
  providerRowRef?: string | null;
  localRef?: string | null;
  detail?: Record<string, unknown> | null;
}

export function listReconciliations(limit = 20): Promise<{ reports: ReconciliationReport[] }> {
  return get<{ reports: ReconciliationReport[] }>('/api/v1/admin/reconciliations', { limit });
}

export function reconciliationReport(id: string): Promise<ReconciliationReport> {
  return get<ReconciliationReport>(`/api/v1/admin/reconciliations/${id}`);
}

/** One cursor page of detail rows; `nextCursor` is '' once the slice is exhausted. */
export function reconciliationRows(
  id: string,
  query: { state?: string; cursor?: string | number; limit?: number } = {},
): Promise<{ rows: ReconciliationRow[]; nextCursor: string | number }> {
  return get<{ rows: ReconciliationRow[]; nextCursor: string | number }>(
    `/api/v1/admin/reconciliations/${id}/rows`,
    query,
  );
}

export function createReconciliation(
  params: { providerCode: string; currency: string; windowFrom: string; windowTo: string },
  content: Blob,
): Promise<ReconciliationReport> {
  const qs = new URLSearchParams(params).toString();
  return uploadBytes<ReconciliationReport>(`/api/v1/admin/reconciliations?${qs}`, content);
}
