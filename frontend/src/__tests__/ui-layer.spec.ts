import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { globSync } from 'node:fs';

/**
 * U3 layer guard: the console runs on the self-drawn ui/ design system only.
 * tdesign-vue-next was removed from the bundle in U3; this spec fails the
 * moment a runtime import sneaks back in (the SVG icon set
 * tdesign-icons-vue-next stays — it is an asset library, not a UI layer).
 */
describe('UI layer isolation (U3)', () => {
  const sourceFiles = globSync('src/**/*.{vue,ts}');
  const sources = sourceFiles.map((f) => readFileSync(f, 'utf-8')).join('\n');

  it('has no tdesign-vue-next runtime imports in src', () => {
    expect(sources).not.toMatch(/from ['"]tdesign-vue-next['"]/);
  });
});
