import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';

/**
 * OpenAPI codegen step one (backlog F09 follow-up / 功能 4, stage 1):
 * docs/openapi/openapi-3.1.json is the machine-readable contract (springdoc
 * generates it; CI guards it against breaking diffs; openapi-typescript
 * renders src/types/generated.ts from it via `npm run gen:types`).
 * Handwritten types/api.ts stays the runtime source for now, but its core
 * DTOs must not carry fields the OpenAPI schema does not declare — that
 * would mean portal/API drift. Members are read from the JSON source
 * directly, so this spec is independent of the generated file formatting.
 *
 * The pair table maps handwritten names to schema names (*View DTOs map to
 * their backend record: McpServiceView → McpService, …).
 */
describe('codegen consistency (openapi schema vs handwritten core types)', () => {
  const spec = JSON.parse(readFileSync('../docs/openapi/openapi-3.1.json', 'utf-8'));
  const handwritten = readFileSync('src/types/api.ts', 'utf-8');

  const PAIRS: Record<string, string> = {
    VirtualKeyView: 'VirtualKeyView',
    McpServiceView: 'McpService',
    McpToolView: 'McpTool',
    McpAccessView: 'McpAccessView',
    AlertRule: 'AlertRule',
    ExportTask: 'ExportTask',
    QuotaRuleView: 'QuotaRuleView',
    SubscriptionView: 'SubscriptionView',
    UsageRecordPage: 'UsageRecordPage',
    CredentialView: 'CredentialView',
    SkillView: 'SkillView',
    AgentView: 'AgentView',
    ProviderProductView: 'ProductView',
    ConfigEntryView: 'ConfigEntry',
    ApiConsumerView: 'ApiConsumerView',
    WebhookEndpointView: 'WebhookEndpointView',
    AuditEventView: 'AuditEventView',
  };

  function schemaMembers(schemaName: string): Set<string> {
    const schema = spec.components?.schemas?.[schemaName];
    if (!schema?.properties) return new Set();
    return new Set(Object.keys(schema.properties));
  }

  function apiMembers(name: string): Set<string> {
    const block = new RegExp(`export interface ${name} \\{([\\s\\S]*?)\\n\\}`).exec(handwritten);
    if (!block) return new Set();
    const fields = new Set<string>();
    for (const line of block[1].split('\n')) {
      const m = /^\s{2}([A-Za-z_$][\w$]*)\??:/.exec(line);
      if (m) fields.add(m[1]);
    }
    return fields;
  }

  for (const [apiName, schemaName] of Object.entries(PAIRS)) {
    it(`core DTO ${apiName}: handwritten fields are declared by the OpenAPI schema`, () => {
      const api = apiMembers(apiName);
      const specFields = schemaMembers(schemaName);
      expect(api.size, `${apiName} should resolve in api.ts`).toBeGreaterThan(0);
      expect(
        specFields.size,
        `${schemaName} should resolve in docs/openapi/openapi-3.1.json`,
      ).toBeGreaterThan(0);
      const missing = [...api].filter((f) => !specFields.has(f));
      expect(missing, `${apiName} has handwritten fields missing from the OpenAPI schema`).toEqual(
        [],
      );
    });
  }
});
