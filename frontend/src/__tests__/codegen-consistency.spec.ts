import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';

/**
 * OpenAPI codegen step one (backlog F09 follow-up / 功能 4, stage 1):
 * docs/openapi/openapi-3.1.json is the machine-readable contract — the
 * control plane generates it (springdoc) and CI guards it against breaking
 * diffs. src/types/generated.ts is produced from it by openapi-typescript
 * (`npm run gen:types`). Handwritten src/types/api.ts stays the runtime
 * source for now, but its core DTOs must not carry fields the OpenAPI schema
 * does not declare — that would mean portal/API drift.
 *
 * The pair table maps handwritten names to generated schema names (they
 * differ for *View DTOs: McpServiceView → McpService, …). Textual member
 * parsing is fine because every paired type is a flat DTO.
 */
describe('codegen consistency (generated vs handwritten core types)', () => {
  const generated = readFileSync('src/types/generated.ts', 'utf-8');
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

  /** Top-level members of a components.schemas DTO (12-space indent). */
  function schemaMembers(schemaName: string): Set<string> {
    const schemas = generated.slice(generated.indexOf('schemas: {'));
    const block = new RegExp(`\\n {8}${schemaName}: \\{([\\s\\S]*?)\\n {8}\\}`).exec(schemas);
    if (!block) return new Set();
    const fields = new Set<string>();
    for (const line of block[1].split('\n')) {
      const m = /^ {12}([A-Za-z_$][\w$]*)\??:/.exec(line);
      if (m) fields.add(m[1]);
    }
    return fields;
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
      const spec = schemaMembers(schemaName);
      expect(api.size, `${apiName} should resolve in api.ts`).toBeGreaterThan(0);
      expect(
        spec.size,
        `${schemaName} should resolve in generated.ts — re-run npm run gen:types after spec changes`,
      ).toBeGreaterThan(0);
      const missing = [...api].filter((f) => !spec.has(f));
      expect(missing, `${apiName} has handwritten fields missing from the OpenAPI schema`).toEqual(
        [],
      );
    });
  }
});
