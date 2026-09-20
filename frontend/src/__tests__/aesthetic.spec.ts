/// <reference types="node" />
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { globSync } from 'node:fs';

/**
 * Remove comments while keeping the line count, so a reported line number still
 * points at the file. String literals are respected on purpose: `'…/handbook/*.md'`
 * is a glob, not the start of a block comment — the naive `/* … *\/` strip swallowed
 * every line up to the next `*\/` and made forty lines of `NextHelpView.vue`
 * invisible to this audit (caught in review of #1112).
 */
function stripComments(source: string): string {
  let out = '';
  let quote: string | null = null;
  let inBlock = false;
  let inLine = false;
  for (let i = 0; i < source.length; i += 1) {
    const ch = source[i];
    const next = source[i + 1];
    if (inLine) {
      if (ch === '\n') {
        inLine = false;
        out += ch;
      }
      continue;
    }
    if (inBlock) {
      if (ch === '*' && next === '/') {
        inBlock = false;
        i += 1;
      } else if (ch === '\n') out += ch;
      continue;
    }
    if (quote) {
      out += ch;
      if (ch === quote && source[i - 1] !== '\\') quote = null;
      continue;
    }
    if (ch === '"' || ch === "'" || ch === '`') {
      quote = ch;
      out += ch;
      continue;
    }
    if (ch === '/' && next === '*') {
      inBlock = true;
      i += 1;
      continue;
    }
    if (ch === '/' && next === '/') {
      inLine = true;
      i += 1;
      continue;
    }
    out += ch;
  }
  return out;
}

/** CSS colour names whose hue sits in the violet band. */
const PURPLE_NAMES: Record<string, string> = {
  purple: '#800080',
  rebeccapurple: '#663399',
  blueviolet: '#8a2be2',
  darkviolet: '#9400d3',
  mediumpurple: '#9370db',
  mediumorchid: '#ba55d3',
  darkorchid: '#9932cc',
  violet: '#ee82ee',
  orchid: '#da70d6',
  plum: '#dda0dd',
  slateblue: '#6a5acd',
  indigo: '#4b0082',
  fuchsia: '#ff00ff',
  magenta: '#ff00ff',
};

const to255 = (part: string) =>
  part.endsWith('%') ? (Number.parseFloat(part) / 100) * 255 : Number.parseFloat(part);

/** HSV hue (degrees) and saturation of an RGB triple in 0–255. */
function hsv(r: number, g: number, b: number): { hue: number; saturation: number } {
  const [rn, gn, bn] = [r / 255, g / 255, b / 255];
  const max = Math.max(rn, gn, bn);
  const delta = max - Math.min(rn, gn, bn);
  if (delta === 0 || max === 0) return { hue: 0, saturation: 0 };
  let hue = 0;
  if (max === rn) hue = 60 * (((gn - bn) / delta) % 6);
  else if (max === gn) hue = 60 * ((bn - rn) / delta + 2);
  else hue = 60 * ((rn - gn) / delta + 4);
  return { hue: hue < 0 ? hue + 360 : hue, saturation: delta / max };
}

function hslToRgb(h: number, s: number, l: number): [number, number, number] {
  const c = (1 - Math.abs(2 * l - 1)) * s;
  const x = c * (1 - Math.abs(((h / 60) % 2) - 1));
  const m = l - c / 2;
  const [r, g, b] =
    h < 60
      ? [c, x, 0]
      : h < 120
        ? [x, c, 0]
        : h < 180
          ? [0, c, x]
          : h < 240
            ? [0, x, c]
            : h < 300
              ? [x, 0, c]
              : [c, 0, x];
  return [(r! + m) * 255, (g! + m) * 255, (b! + m) * 255];
}

/**
 * Every colour literal on one line, with the hue/saturation it carries. Covers the
 * ordinary spellings — hex (3/4/6/8 digits), `rgb()/rgba()`, `hsl()/hsla()`,
 * `oklch()`, and the violet-family colour names — because "judged by hue" has to
 * mean the ways a colour is actually written, not just `#rrggbb`.
 */
function colourLiterals(line: string): Array<{ text: string; hue: number; saturation: number }> {
  const found: Array<{ text: string; hue: number; saturation: number }> = [];
  const add = (text: string, r: number, g: number, b: number) => {
    const { hue, saturation } = hsv(r, g, b);
    found.push({ text, hue, saturation });
  };

  for (const match of line.matchAll(/#([0-9a-fA-F]{3,8})\b/g)) {
    let hex = match[1]!;
    // `#758` in a comment-free line is an issue reference, not a colour: three- and
    // four-digit forms are only read as hex when they carry a hex letter. (`#fff`,
    // `#abc` are colours; `#316`, `#617` are tickets.)
    if (hex.length <= 4 && !/[a-fA-F]/.test(hex)) continue;
    if (hex.length === 3 || hex.length === 4) hex = hex.slice(0, 3).replace(/./g, (c) => c + c);
    if (hex.length !== 6) hex = hex.slice(0, 6); // 8 digits: #rrggbbaa, hue is in the first six
    add(
      match[0],
      ...([0, 2, 4].map((o) => parseInt(hex.slice(o, o + 2), 16)) as [number, number, number]),
    );
  }
  for (const match of line.matchAll(/rgba?\(\s*([\d.]+%?)[,\s]+([\d.]+%?)[,\s]+([\d.]+%?)/g)) {
    add(match[0], to255(match[1]!), to255(match[2]!), to255(match[3]!));
  }
  for (const match of line.matchAll(/hsla?\(\s*([\d.]+)(?:deg)?[,\s]+([\d.]+)%[,\s]+([\d.]+)%/g)) {
    add(match[0], ...hslToRgb(Number(match[1]), Number(match[2]) / 100, Number(match[3]) / 100));
  }
  for (const match of line.matchAll(/oklch\(\s*[\d.]+%?\s+[\d.]+\s+([\d.]+)/g)) {
    add(match[0], ...hslToRgb(Number(match[1]), 1, 0.5));
  }
  for (const [name, hex] of Object.entries(PURPLE_NAMES)) {
    if (new RegExp(`\\b${name}\\b`, 'i').test(line)) {
      add(
        name,
        ...([0, 2, 4].map((o) => parseInt(hex.slice(o + 1, o + 3), 16)) as [
          number,
          number,
          number,
        ]),
      );
    }
  }
  return found;
}

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
    //
    // What is covered: `#rgb` / `#rrggbb` / `#rrggbbaa` (and `#rgba`), `rgb()/rgba()`,
    // `hsl()/hsla()`, `oklch()`, and the CSS colour names for violet-family hues.
    // What is not: colours composed at runtime (a string built from parts), `oklab()`
    // (its a/b axes are not a hue), and `color-mix()`. Those would have to be caught
    // by reading the code, not by a scanner — this check exists to keep the ordinary
    // mistake from shipping, not to be a proof.
    const sources = globSync('src/**/*.{css,vue,ts}').filter(
      (file) => !file.includes('__tests__') && !file.includes('types/generated'),
    );
    expect(sources.length).toBeGreaterThan(0);

    const purple: string[] = [];
    for (const file of sources) {
      stripComments(readFileSync(file, 'utf-8'))
        .split('\n')
        .forEach((line, index) => {
          // Supplier brand colours are the sanctioned exception (frontend-design §4.1).
          if (line.includes('--miqrokey-chip-')) return;
          for (const { text, hue, saturation } of colourLiterals(line)) {
            if (hue >= 255 && hue <= 320 && saturation >= 0.25) {
              purple.push(`${file}:${index + 1} ${text}`);
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
