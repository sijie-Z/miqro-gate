/// <reference types="node" />
import { describe, expect, it } from 'vitest';
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

/**
 * #1120: a count is a conclusion about the data, so it may only be drawn from a
 * read that succeeded. Each count line in the console views (`共 {{ … }}`,
 * `基于当前 {{ … }}`, `（全部 {{ … }}`) must either go through
 * `countWhenLoaded()` or read a load-error flag itself — otherwise a failed read
 * renders "共 0 个", which is the counter face of the same family as #1065
 * (table empty states) and #1104 (stat cards).
 *
 * The guard exists so the next list page cannot quietly reintroduce it.
 */
describe('#1120 counter lines never claim zero after a failed load', () => {
  // Vitest runs with the frontend package as cwd (npm run test), so the view
  // sources resolve from there without touching ESM URL helpers.
  const dir = join(process.cwd(), 'src', 'views', 'next');

  /**
   * Lines whose enclosing `v-if`/`v-else` already enforces the rule, so the text
   * itself carries no guard. Each entry names the gate that makes it safe:
   * - NextUsageView pager: `v-if="records && records.total > 0"` — a failed read
   *   leaves `records` null, so the line never renders.
   * - NextUsersView counter: `<template v-if="loadError">—</template>` sits on the
   *   line above; the `v-else` branch (#1065) is the loaded state.
   * - NextProvidersView warning banner line: the banner's `v-if` requires
   *   `!loadError && unverifiedCount`.
   */
  const GATED_BY_ENCLOSING_V_IF: Array<[string, string]> = [
    ['NextUsageView.vue', '共 {{ records.total }}'],
    ['NextUsersView.vue', '共 {{ users.length }}'],
    ['NextProvidersView.vue', '共 {{ unverifiedCount }}'],
  ];

  it('every count line is gated on the load state', () => {
    const marks = ['共 {{', '基于当前 {{', '（全部 {{'];
    const offenders: string[] = [];
    for (const file of readdirSync(dir).filter((f) => f.endsWith('.vue'))) {
      const source = readFileSync(join(dir, file), 'utf8');
      source.split('\n').forEach((line, index) => {
        if (!marks.some((m) => line.includes(m))) return;
        const allowlisted = GATED_BY_ENCLOSING_V_IF.some(
          ([name, snippet]) => name === file && line.includes(snippet),
        );
        const gated =
          allowlisted ||
          line.includes('countWhenLoaded(') ||
          /loadError|Error \?|error \?/.test(line);
        if (!gated) offenders.push(`${file}:${index + 1} ${line.trim().slice(0, 100)}`);
      });
    }
    expect(offenders).toEqual([]);
  });
});
