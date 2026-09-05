import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';

/**
 * OpenAPI codegen step one (backlog F09 follow-up / 功能 4, stage 1):
 * docs/openapi/openapi-3.1.json is the machine-readable contract (springdoc
 * generates it; CI guards it against breaking diffs; openapi-typescript
 * renders src/types/generated.ts from it via `npm run gen:types`).
 * Handwritten types/api.ts stays the runtime source for now, but its DTOs
 * must not carry fields the OpenAPI schema does not declare — that would
 * mean portal/API drift.
 *
 * Pair derivation is automatic so the guard never goes stale: every exported
 * `interface` in api.ts is matched against the schema by exact name, or by
 * the *View-suffix convention (McpServiceView → McpService). Type aliases
 * and enums have no members and are skipped. Members come from the JSON
 * source, so the spec is independent of the generated file formatting.
 */
describe('codegen consistency (openapi schema vs handwritten core types)', () => {
  const spec = JSON.parse(readFileSync('../docs/openapi/openapi-3.1.json', 'utf-8'));
  const handwritten = readFileSync('src/types/api.ts', 'utf-8');
  const schemas = new Set(Object.keys(spec.components?.schemas ?? {}));

  const exportedInterfaces = new Set(
    [...handwritten.matchAll(/^export interface (\w+) \{/gm)].map((m) => m[1]),
  );

  // Known naming exceptions to the *View-suffix convention.
  const EXCEPTIONS: Record<string, string> = {
    ProviderProductView: 'ProductView', // the admin list view, not the catalog DTO
  };

  const PAIRS: Array<[apiName: string, schemaName: string]> = [...exportedInterfaces]
    .filter((n) => EXCEPTIONS[n] || schemas.has(n) || (n.endsWith('View') && schemas.has(n.slice(0, -4))))
    .map((n) => {
      if (EXCEPTIONS[n]) return [n, EXCEPTIONS[n]];
      return schemas.has(n) ? [n, n] : [n, n.slice(0, -4)];
    });

  expect(PAIRS.length).toBeGreaterThan(20);

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

  for (const [apiName, schemaName] of PAIRS) {
    it(`core DTO ${apiName} → ${schemaName}: handwritten fields are declared by the OpenAPI schema`, () => {
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
