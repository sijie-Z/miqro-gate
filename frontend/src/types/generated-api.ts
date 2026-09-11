/**
 * Curated type aliases over the openapi-typescript output
 * (src/types/generated.ts <- docs/openapi/openapi-3.1.json).
 *
 * Stage-2 codegen migration hub: as handwritten DTOs in src/types/api.ts are
 * replaced by their schema counterparts, their aliases land here and every
 * consumer switches its import source. The schema is the authority; the
 * generated View fields are all optional (springdoc does not emit required
 * for response classes), so consumers must tolerate undefined where the
 * handwritten type declared required fields.
 */

import type { components } from './generated';

export type UsageRecord = components['schemas']['UsageRecordView'];
export type UsageRecordPage = components['schemas']['UsageRecordPage'];
export type RoiReportView = components['schemas']['RoiReportView'];
// webhook deliveries: the backend service record is named DeliveryAttempt
export type WebhookDelivery = components['schemas']['DeliveryAttempt'];
// route rules (F11): the schema names the nested controller record
// 'UpsertRequest'; the alias keeps the domain name the consumers use.
export type McpRouteRule = components['schemas']['McpRouteRule'];
export type UpsertMcpRouteRuleRequest = components['schemas']['UpsertRequest'];
export type SkillView = components['schemas']['SkillView'];
// skill version management (I14): revision history view (metadata only)
export type SkillRevisionView = components['schemas']['SkillRevisionView'];
export type AgentView = components['schemas']['AgentView'];
export type BudgetView = components['schemas']['BudgetView'];
export type VirtualKeyView = components['schemas']['VirtualKeyView'];
export type CreateVirtualKeyResponse = components['schemas']['CreateVirtualKeyResponse'];
export type ModelApprovalView = components['schemas']['ModelApprovalView'];
export type ModelApprovalPage = components['schemas']['ModelApprovalPage'];
export type QuotaRuleView = components['schemas']['QuotaRuleView'];
export type UsageSummary = components['schemas']['UsageSummary'];
export type PriceSnapshotView = components['schemas']['PriceSnapshotView'];
export type CredentialView = components['schemas']['CredentialView'];
export type CredentialVersionView = components['schemas']['CredentialVersionView'];
export type CredentialDetailView = components['schemas']['CredentialDetailView'];
export type ValidateCredentialResponse = components['schemas']['ValidateCredentialResponse'];
export type SubscriptionView = components['schemas']['SubscriptionView'];
export type SeatView = components['schemas']['SeatView'];
export type AuditEventView = components['schemas']['AuditEventView'];
export type MeGrantsResponse = components['schemas']['MeGrantsResponse'];
export type QuotaDefaultTemplateView = components['schemas']['QuotaDefaultTemplateView'];
export type McpAccessView = components['schemas']['McpAccessView'];
export type Team = components['schemas']['Team'];
export type Project = components['schemas']['Project'];
export type ApiConsumerView = components['schemas']['ApiConsumerView'];
export type Provider = components['schemas']['Provider'];
export type ExportTask = components['schemas']['ExportTask'];
export type WebhookEndpointView = components['schemas']['WebhookEndpointView'];
export type AlertRule = components['schemas']['AlertRule'];
export type InternalServiceView = components['schemas']['InternalService'];
export type ConfigEntryView = components['schemas']['ConfigEntry'];
export type McpServiceView = components['schemas']['McpService'];
export type McpToolView = components['schemas']['McpTool'];
export type McpAccessLogEntry = components['schemas']['McpAccessLogEntry'];
export type McpResiliencePolicy = components['schemas']['McpResiliencePolicy'];
// auth envelope (issue #265): content schemas from springdoc annotations on
// /api/v1/auth — LoginResponse/UserResponse previously had no schema at all.
export type LoginResponse = components['schemas']['LoginResponse'];
export type UserResponse = components['schemas']['UserResponse'];
// admin users (issue: admin list contract modeled as AdminUserView — the
// domain User record minus passwordHash, so no schema advertises it)
export type AdminUser = components['schemas']['AdminUserView'];
export type UserCreatedResponse = components['schemas']['UserCreated'];
// step 2 (issue #278): FE display names alias backend-named schemas.
// MemberView covers both team and project member endpoints (identical shape;
// schema name taken from the team side).
export type McpHeaderCondition = components['schemas']['McpHeaderCondition'];
export type MemberView = components['schemas']['TeamMemberView'];
export type UserProjectMembership = components['schemas']['UserProjectMembershipView'];
export type McpToolRevisionRow = components['schemas']['McpToolRevision'];
export type ModelCatalogRow = components['schemas']['ModelCatalogView'];
// step 3 (issue #284): FE display names alias backend-named schemas.
export type Grant = components['schemas']['ProjectProviderGrant'];
// legacy FE name describes the deletion *task* entity the endpoints return
export type UsageDeletionRequest = components['schemas']['UsageDeletion'];
export type ToolImportSkip = components['schemas']['ImportSkip'];
export type ToolImportResult = components['schemas']['ImportResult'];
// step 4 (issue #286): usage summary nested shapes + consumer create response.
export type UsageCost = components['schemas']['Cost'];
export type UsageRequests = components['schemas']['Requests'];
export type UsageTokens = components['schemas']['Tokens'];
export type UsageGroup = components['schemas']['GroupSummary'];
export type CreateApiConsumerResponse = components['schemas']['CreateApiConsumerResponse'];
