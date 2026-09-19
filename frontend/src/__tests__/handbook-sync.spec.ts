/// <reference types="node" />
import { describe, expect, it } from 'vitest';
import { readdirSync, readFileSync } from 'node:fs';

/**
 * Handbook vendoring guard (#899).
 *
 * docs/user-guide/ is the single source of truth; the console ships a vendored
 * copy under src/content/handbook/ because the DEPLOY build context only
 * carries backend/ + frontend/ + deploy/ — an earlier revision that read the
 * docs tree directly built green locally/CI and shipped an EMPTY handbook in
 * production. Editing either copy without running `npm run sync:handbook`
 * must fail this suite.
 */
const SOURCE = '../docs/user-guide';
const COPY = 'src/content/handbook';

function markdown(dir: string): Map<string, Buffer> {
  const out = new Map<string, Buffer>();
  for (const name of readdirSync(dir)) {
    if (name.endsWith('.md')) out.set(name, readFileSync(`${dir}/${name}`));
  }
  return out;
}

describe('handbook vendoring (#899)', () => {
  it('carries a byte-identical copy of every docs/user-guide markdown file', () => {
    const source = markdown(SOURCE);
    const copy = markdown(COPY);

    expect(source.size).toBeGreaterThan(0);
    expect([...copy.keys()].sort()).toEqual([...source.keys()].sort());
    for (const [name, bytes] of source) {
      expect(copy.get(name)!.equals(bytes), `${name} drifted — run npm run sync:handbook`).toBe(
        true,
      );
    }
  });

  it('ships non-empty documents (an empty page is the failure this guards)', () => {
    for (const [name, bytes] of markdown(COPY)) {
      expect(bytes.length, `${name} must not be empty`).toBeGreaterThan(0);
    }
  });
});
