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
export type SkillView = components['schemas']['SkillView'];
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
