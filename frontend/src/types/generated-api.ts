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
