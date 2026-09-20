/// <reference types="node" />
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { globSync } from 'node:fs';

/**
 * Aesthetic audit (frontend-design.md §9, 2026-08-27 Tencent-console
 * direction): gradients exist ONLY on brand identity chips (.mk-brand-chip)
 * and the cost donut (.mk-donut); purple exists ONLY in the chip palette;
 * regular containers stay <=8px radius; cards may cast the hairline shadow
 * (0 1px 2px) — anything heavier belongs to popper/dropdown/dialog. This
 * scans the source CSS; the built bundle inherits the same rules.
 */
describe('aesthetic audit', () => {
  const cssFiles = globSync('src/styles/*.css');
  const css = cssFiles.map((f) => readFileSync(f, 'utf-8')).join('\n');

  // Rule blocks outside the sanctioned brand-identity rules.
  const stripped = css
    .split('}')
    .filter(
      (block) => !/\.mk-brand-chip|\.mk-donut|--miqrokey-chip-|--miqrokey-shadow-card/.test(block),
    )
    .join('}');

  it('contains gradients only on brand chips and the cost donut', () => {
    expect(stripped).not.toMatch(/linear-gradient|radial-gradient|conic-gradient/);
  });

  it('contains no purple tokens outside the brand chip palette', () => {
    // Explicitly forbidden palette entries (any case).
    expect(stripped).not.toMatch(
      /#7c3aed|#8b5cf6|#a855f7|#6d28d9|#9333ea|#c026d3|#d946ef|#a21caf|purple/i,
    );
  });

  it('contains no purple literal anywhere in the sources, judged by hue (#1112)', () => {
    // The list above is a denylist, and it only ever saw `src/styles/*.css`. Both
    // halves of that failed on 2026-09-20: a new shared palette shipped `#722ed1`
    // (Ant's purple — the spelling this codebase's palette family would actually
    // reach for) in `src/lib/chart-palette.ts`, and nothing looked at component
    // sources at all. The rule is about the colour, so judge the colour: hue in the
    // violet band with enough saturation to read as intentional (greys and the light
    // tints carry no hue and are not the thing §4.1 forbids).
    const purple: string[] = [];
    for (const file of globSync('src/**/*.{css,vue,ts}')) {
      if (file.includes('__tests__') || file.includes('types/generated')) continue;
      // Comments and docstrings are stripped first: a comment that *mentions* a purple
      // (this very palette's docstring explains which one it replaced) ships nothing,
      // and flagging it would train the next reader to reword the comment instead of
      // keeping the check.
      readFileSync(file, 'utf-8')
        .replace(/\/\*[\s\S]*?\*\//g, '')
        .replace(/^\s*\/\/.*$/gm, '')
        .split('\n')
        .forEach((line, index) => {
          // Supplier brand colours are the sanctioned exception (frontend-design §4.1).
          if (line.includes('--miqrokey-chip-')) return;
          for (const match of line.matchAll(/#([0-9a-fA-F]{6})\b/g)) {
            const [r, g, b] = [0, 2, 4].map((o) => parseInt(match[1]!.slice(o, o + 2), 16) / 255);
            const max = Math.max(r!, g!, b!);
            const delta = max - Math.min(r!, g!, b!);
            if (delta === 0 || max === 0) continue; // a grey has no hue to judge
            let hue = 0;
            if (max === r) hue = 60 * (((g! - b!) / delta) % 6);
            else if (max === g) hue = 60 * ((b! - r!) / delta + 2);
            else hue = 60 * ((r! - g!) / delta + 4);
            if (hue < 0) hue += 360;
            if (hue >= 255 && hue <= 320 && delta / max >= 0.25) {
              purple.push(`${file}:${index + 1} ${match[0]}`);
            }
          }
        });
    }
    expect(purple).toEqual([]);
  });

  it('never exceeds 8px radius on regular controls (panels/dialogs/pills are the sanctioned exceptions)', () => {
    // Split into rule blocks so the sanctioned .mk-status pill radius (spec
    // §3: pill only for short status labels) is not treated as a container.
    const blocks = css.split('}');
    let checked = 0;
    for (const block of blocks) {
      const isStatusPill = /\.mk-status/.test(block);
      // Sanctioned exceptions: panel/card tokens (Vben console family —
      // radius-panel 12px), dialog/modal tokens (--*radius-modal, ui dialog
      // layer), and pill tokens (status pill, segmented fills).
      const isModal =
        /\.t-dialog/.test(block) ||
        /radius-(modal|dialog|panel)/.test(block) ||
        /radius-pill/.test(block);
      const skip = isStatusPill || isModal;
      for (const m of block.matchAll(/--?[a-z-]*radius[a-z-]*:\s*([0-9.]+)px/g)) {
        if (!skip) {
          expect(Number(m[1])).toBeLessThanOrEqual(8);
          checked++;
        }
      }
    }
    expect(checked).toBeGreaterThan(0);
  });

  it('does not use marketing gradients or giant pill statuses', () => {
    // No pill-style status with big padding.
    expect(css).not.toMatch(/\.mk-status[^{]*\{[^}]*padding:\s*(?:1[2-9]|2\d)px/);
  });

  it('keeps the shared box model and the teleported popper chrome in the global sheet', () => {
    // v2 controls size themselves as border-box (width 100% + 1px border +
    // 24px padding); without the reset every field rendered 26px wider than
    // its wrapper and bled into the next control in the row.
    expect(css).toMatch(/\*,\s*\*::before,\s*\*::after\s*\{\s*box-sizing:\s*border-box/);
    // radix popper roots drop the scoped data-v attribute, so the popover
    // containers must live in src/styles/*.css for their chrome to apply.
    expect(css).toMatch(/\.ui-select__content\s*\{/);
    expect(css).toMatch(/\.ui-menu\s*\{/);
  });

  it('keeps every stylesheet brace-balanced (unclosed blocks silently re-scope the NEXT sheet)', () => {
    // 2026-09-18 incident: design-tokens.css lost the `}` closing its `:root`
    // block. The build quietly treated design-base.css as NESTED inside it and
    // flattened every selector to `:root .x` — html-attribute rules
    // (`[data-menu-theme='light']`, `[data-anim='off']`, …) became `:root [..]`
    // and stopped matching, so the dark rail's ink fell back to body text
    // color ("MiQroGate" invisible on the navy rail). Nothing failed: typecheck,
    // tests and build were all green. Balance every sheet, comment- and
    // string-aware, so a missing brace can never ship silently again.
    const sheets = globSync('src/**/*.css');
    const unbalanced: string[] = [];
    for (const file of sheets) {
      const source = readFileSync(file, 'utf-8').replace(/\/\*[\s\S]*?\*\//g, '');
      let depth = 0;
      let quote: string | null = null;
      for (let i = 0; i < source.length; i += 1) {
        const ch = source[i];
        if (quote) {
          if (ch === quote && source[i - 1] !== '\\') quote = null;
          continue;
        }
        if (ch === '"' || ch === "'") quote = ch;
        else if (ch === '{') depth += 1;
        else if (ch === '}') depth -= 1;
      }
      if (depth !== 0) unbalanced.push(`${file} (brace depth ${depth} at EOF)`);
    }
    expect(unbalanced).toEqual([]);
  });

  it('keeps shadows limited to dropdown/popover/modal (cards may cast the hairline shadow)', () => {
    // TDesign (t-) and legacy (el-) names both sanctioned; the v2 teleported
    // popper surfaces (.ui-select__content / .ui-menu / .ui-tooltip) live in
    // the global sheet because radix's popper root drops the scoped data-v
    // attribute. Hairline card shadow (0 1px 2px, or the --ui-shadow-card
    // token) is the sanctioned card depth; the focus ring token
    // (--ui-shadow-focus = 0 0 0 2px ring) is sanctioned by name; anything
    // else must stay on popper/dropdown/dialog.
    const shadowBlocks = css.match(/[^{}]*\{[^}]*box-shadow:[^}]*\}/g) ?? [];
    for (const block of shadowBlocks) {
      const hairlineCard =
        /\.mk-card|\.mk-stat-card|\.ui-panel/.test(block) &&
        (/0 1px 2px/.test(block) ||
          /var\(--miqrokey-shadow-card\)/.test(block) ||
          /var\(--ui-shadow-card\)/.test(block));
      const popperSurface =
        /(?:el|t)-(?:popper|dropdown|dialog|popup)|\.ui-select__content|\.ui-menu|\.ui-tooltip/.test(
          block,
        );
      const focusRing =
        /box-shadow:\s*none/.test(block) ||
        /0 0 0 2px/.test(block) ||
        /var\(--ui-shadow-focus\)/.test(block);
      if (!hairlineCard && !popperSurface && !focusRing) {
        expect(block).toMatch(/(?:el|t)-(?:popper|dropdown|dialog|popup)/);
      }
    }
  });
});
