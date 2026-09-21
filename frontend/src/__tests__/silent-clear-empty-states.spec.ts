/// <reference types="node" />
import { describe, expect, it } from 'vitest';
import { parse } from '@vue/compiler-sfc';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

/**
 * #1160 guard — the reference-list face of 「失败的加载不得渲染成数据」.
 *
 * The family has been closed three times already: tables (#1065 → `UiTable.error`),
 * stat cards (#1104), counters (#1120 → `list-counters.spec.ts`). This face is the
 * one the counter guard cannot see: a lazily loaded reference list (projects / teams
 * / users / feed / quota rules) whose read fails and which the view then renders as
 * 「暂无项目」/「还没有动态记录。」/… — an empty-state *claim about the data*, drawn
 * from a request that never got an answer.
 *
 * Three rules, one test each — the third added in review, once the ninth site
 * (`NextPlansView`) showed the first two could not see a read that was never
 * given a catch at all:
 *  1. Every 暂无 / 还没有 / 没有更多 string the template can render must sit under a
 *     gate that admits a failed or not-yet-loaded read (an error/loaded predicate on
 *     the line, in its v-if chain, on an ancestor, or an ancestor's `:error` binding
 *     — the UiTable contract), or carry a written exemption.
 *  2. Every `catch` handler that discards a list (`rows.value = []`) or answers with
 *     an empty array (`.catch(() => [])`) must leave a visible failure signal in the
 *     handler body (assignment to an Error-named or Failed-named ref, `toast.error`,
 *     a rethrow), or carry a written exemption.
 *  3. A `try` that awaits an `api.*` read must have a `catch` — a rejection may not
 *     escape unhandled while the table below quietly draws its empty state.
 *
 * Rule 2 exists because the silent half of this face renders no text at all: the
 * picker is simply empty (Teams/Projects/AlertRules/Cost in #1160), so rule 1 alone
 * would stay green while the drawer says nothing.
 */

const dir = join(process.cwd(), 'src', 'views', 'next');

/** Template text that asserts something about the data ("there is none"). */
const MARKS = /暂无|还没有|没有更多/;

/**
 * A predicate that admits a failed or not-yet-completed read: an identifier that
 * names an error/loaded/failed flag, `countWhenLoaded(...)`, or the words the UI
 * puts on a failed panel. Deliberately generous on the *naming* side (a line whose
 * gate mentions `loadError` is safe even if the name is spelled `err`) — a rule
 * that reds harmless files gets ignored (#807), while a rule with no gate at all
 * was this issue.
 */
const GATE = /\w*(?:Error|Loaded|Failed)\w*|countWhenLoaded|加载失败/;

/** A catch handler that throws the listing away. */
const CLEAR = /([\w$.]+)\.value\s*=\s*\[\s*\]/g;

/** A catch handler whose *entire* answer is `[]` (possibly `[] as Grant[]`). */
const INLINE_EMPTY = /^\s*(\(\s*\)|\w+)?\s*=>\s*\[\s*\](\s+as\s+[^()]+)?\s*$/;

/** Failure signals that a catch body may leave: visible error state, a toast, a rethrow. */
const SIGNAL = /toast\.error\(|\w*(?:Error|Err|Failed|Failure)\w*\.value\s*=|setError\(|\bthrow\b/;

/**
 * Rule 1 exemptions. Each entry is [file, snippet, reason]; the snippet must
 * occur in the ungated string. Every entry here falls under §0.3's two closed
 * classes — "既定决定" (#657 / A9) or "失败信号在屏幕别处可见" — and only the
 * entry is added; no behaviour is changed by listing it.
 */
const TEXT_EXEMPT: Array<[string, string, string]> = [
  // 失败信号在同弹窗/同面板别处可见（A9 边界，待 owner 拍板）。
  ['NextAdminMcpServicesView.vue', '暂无 API 消费者', '同弹窗顶部有 accessError 红条（:2350）'],
  ['NextAdminMcpServicesView.vue', '该服务还没有工具。', '同面板顶部有 toolsError 红条'],
  ['NextAdminMcpServicesView.vue', '该服务暂无工具。', '同弹窗顶部有 accessError 红条'],
  ['NextAdminMcpServicesView.vue', '该工具还没有修订记录。', '同面板顶部有 revisionsError 红条'],
  ['NextAdminMcpServicesView.vue', '该服务还没有自定义路由。', '同面板顶部有 rulesError 红条'],
  ['NextOverviewView.vue', '还没有虚拟密钥。', '主加载失败时页面顶部有 overview-load-error 红条'],
  [
    'NextOverviewView.vue',
    '还没有用量记录',
    '仅主加载失败时出现：页面顶部 overview-load-error 红条可见',
  ],
  [
    'NextOverviewView.vue',
    '暂无成本记录。',
    '仅主加载失败时出现：页面顶部 overview-load-error 红条可见',
  ],
  [
    'NextOverviewView.vue',
    '还没有订阅。',
    '仅主加载失败时出现：页面顶部 overview-load-error 红条可见',
  ],
  ['NextUsersView.vue', '该用户还没有加入任何项目', '同抽屉顶部有 membershipError 红条'],
  [
    'NextCostView.vue',
    '窗口内暂无调用',
    '统计卡 hint 回退；主加载失败时页面有 cost-load-error 红条',
  ],
  // #1065 表族：子表本身没有 `:error`，但失败信号在紧邻处可见（红条或 toast）。
  ['NextAdminSkillsView.vue', '暂无版本记录', '同对话框有 revisionsError 红条'],
  ['NextAdminWebhooksView.vue', '暂无投递记录', '同抽屉有 deliveriesError 红条'],
  // `还没有成员` 的两条豁免（团队/项目成员表）已随 #1356 删除：两处改接
  // `:error="memberError"` + 重试，不再靠 7 秒就消失的 toast 撑可见性。
  // #657 既定决定：目录/依赖元数据降级为空（决策台账 A9）。
  ['NextProvidersView.vue', '暂无目录模型', '#657 目录元数据降级 + 同面板有 modelError 红条'],
];

/**
 * Rule 2 exemptions, keyed `file::ref` for `ref.value = []` clears and
 * `file::owner` for `.catch(() => [])` answers. Each reason names the decision
 * the exemption rests on; the rot test below fails if an entry stops matching.
 */
const CLEAR_EXEMPT: Array<[string, string]> = [
  [
    'NextLoginView.vue::oauthProviders',
    'fail-open（§0.3）：登录页优先可用，注册开关是后端 403 执法点',
  ],
  [
    'NextProvidersView.vue::api.listSubscriptions()',
    '#657：依赖元数据降级为空，不阻塞列表（决策台账 A9 待 owner 拍板）',
  ],
  [
    'NextProvidersView.vue::api.listCredentials()',
    '#657：依赖元数据降级为空，不阻塞列表（决策台账 A9 待 owner 拍板）',
  ],
  [
    'NextProvidersView.vue::api.listGrants()',
    '#657：授权计数为辅助聚合，失败降级为 0 不阻塞列表（A9 待拍板）',
  ],
  [
    'NextProvidersView.vue::api.adminListModels()',
    '#657：模型目录降级为空不阻塞列表（决策台账 A9 待 owner 拍板）',
  ],
  [
    'NextCredentialsView.vue::api.listGrants()',
    '#657：授权计数为辅助聚合，失败降级为 0 不阻塞列表（A9 待拍板）',
  ],
  [
    'NextProjectsView.vue::api.listGrants()',
    '#657：授权计数为辅助聚合，失败降级为 0 不阻塞列表（A9 待拍板）',
  ],
];

// ---------------------------------------------------------------------------
// Template AST access. The AST is read through a minimal structural view of the
// compiler's nodes (the named node types live in @vue/compiler-core, which this
// package does not depend on directly — same reasoning as
// transition-chain-single-root.spec.ts).
// ---------------------------------------------------------------------------

interface AnyProp {
  type: number;
  name: string;
  value?: { content?: unknown } | null;
  exp?: { content?: string } | null;
  arg?: { content?: string } | null;
}

interface AnyNode {
  type: number;
  tag?: string;
  props?: AnyProp[];
  children?: AnyNode[];
  /** TEXT nodes carry a string; INTERPOLATION nodes carry `{ content }`. */
  content?: string | { content?: string };
  loc?: { start?: { line?: number } };
}

function propsOf(node: AnyNode): AnyProp[] {
  return Array.isArray(node.props) ? node.props : [];
}

function propText(p: AnyProp): string {
  const bits = [p.name ?? ''];
  if (p.value && typeof p.value.content === 'string') bits.push(p.value.content);
  if (p.exp && typeof p.exp.content === 'string') bits.push(p.exp.content);
  if (p.arg && typeof p.arg.content === 'string') bits.push(p.arg.content);
  return bits.join(' ');
}

function selfText(node: AnyNode): string {
  return propsOf(node).map(propText).join(' ');
}

function childrenOf(node: AnyNode): AnyNode[] {
  return Array.isArray(node.children) ? node.children : [];
}

const hasIf = (n: AnyNode) => propsOf(n).some((p) => p.name === 'if');
const isElseBranch = (n: AnyNode) =>
  propsOf(n).some((p) => p.name === 'else' || p.name === 'else-if');

/**
 * Is any branch of the v-if/v-else-if/v-else chain containing `node` gated?
 * The branches are mutually exclusive, so the error branch gates the empty one.
 */
function chainGated(node: AnyNode, parent: AnyNode | null): boolean {
  if (!parent) return false;
  const sibs = childrenOf(parent);
  const idx = sibs.indexOf(node);
  if (idx < 0) return false;
  let start = -1;
  if (hasIf(sibs[idx]!)) start = idx;
  else if (isElseBranch(sibs[idx]!)) {
    for (let i = idx - 1; i >= 0; i--) {
      if (hasIf(sibs[i]!)) {
        start = i;
        break;
      }
      if (!isElseBranch(sibs[i]!)) break;
    }
  }
  if (start < 0) return false;
  for (let i = start; i < sibs.length; i++) {
    if (i > start && !isElseBranch(sibs[i]!)) break;
    if (GATE.test(selfText(sibs[i]!))) return true;
  }
  return false;
}

/**
 * The full gate check: the string's own element or interpolation expression,
 * its v-if chain, any ancestor's props or chain, and any ancestor's `:error`
 * binding (UiTable's #1065 contract renders that error *instead of* the empty
 * state, so an empty-title or #empty slot under `:error="loadError"` is safe).
 */
function gated(node: AnyNode, parent: AnyNode | null, ancestors: AnyNode[]): boolean {
  if (GATE.test(selfText(node))) return true;
  if (chainGated(node, parent)) return true;
  for (let i = 0; i < ancestors.length; i++) {
    const a = ancestors[i]!;
    if (GATE.test(selfText(a))) return true;
    for (const p of propsOf(a)) {
      if ((p.name === 'bind' && p.arg?.content === 'error') || p.name === 'error') {
        if (GATE.test(propText(p))) return true;
      }
    }
    const up = ancestors[i + 1];
    if (up && chainGated(a, up)) return true;
  }
  return false;
}

interface MarkSite {
  file: string;
  line: number;
  snippet: string;
  gated: boolean;
}

function collectMarkSites(file: string, source: string): MarkSite[] | null {
  const { descriptor, errors } = parse(source, { filename: file });
  if (errors.length || !descriptor.template?.ast) return null;
  const sites: MarkSite[] = [];

  function walk(node: AnyNode, parent: AnyNode | null, ancestors: AnyNode[]): void {
    const line = node.loc?.start?.line ?? 0;
    if (node.type === 1) {
      for (const p of propsOf(node)) {
        const text = propText(p);
        if (MARKS.test(text)) {
          sites.push({ file, line, snippet: text, gated: gated(node, parent, ancestors) });
        }
      }
    } else if (node.type === 2 || node.type === 5) {
      // TEXT (2) and INTERPOLATION (5) — comments (3) are not rendered and the
      // comments *about* this guard must not be scanned as if they were.
      const text = typeof node.content === 'string' ? node.content : (node.content?.content ?? '');
      if (MARKS.test(text)) {
        sites.push({ file, line, snippet: text, gated: gated(node, parent, ancestors) });
      }
    }
    for (const c of childrenOf(node)) walk(c, node, [node, ...ancestors]);
  }

  const root = descriptor.template.ast as unknown as AnyNode;
  // `root` rides along as the outermost ancestor so a chain sitting directly
  // under the template root is still visible to `gated()` (its parent is the
  // root node, which the ancestor loop cannot reach otherwise).
  for (const c of childrenOf(root)) walk(c, root, [root]);
  return sites;
}

// ---------------------------------------------------------------------------
// Rule 2: silent clears.
// ---------------------------------------------------------------------------

function matchDelim(src: string, openIdx: number, open: string, close: string): number {
  let depth = 0;
  for (let i = openIdx; i < src.length; i++) {
    const c = src[i];
    if (c === open) depth++;
    else if (c === close) {
      depth--;
      if (depth === 0) return i;
    }
  }
  return -1;
}

/** The call expression a `.catch(` was chained onto, e.g. `api.listGrants()`. */
function ownerOf(src: string, start: number): string {
  let p = start - 1;
  while (p >= 0) {
    const c = src[p]!;
    if (c === ')') {
      // Skip back over this balanced call's parentheses.
      let depth = 0;
      while (p >= 0) {
        const d = src[p]!;
        if (d === ')') depth++;
        else if (d === '(') {
          depth--;
          if (depth === 0) {
            p--;
            break;
          }
        }
        p--;
      }
      continue;
    }
    if (/[\w$.]/.test(c)) {
      p--;
      continue;
    }
    break;
  }
  return src.slice(p + 1, start).trim();
}

interface CatchHandler {
  key: string;
  line: number;
  /** true when the handler is a candidate violation (clears, no signal). */
  candidate: boolean;
}

function collectCatchHandlers(file: string, src: string): CatchHandler[] {
  const out: CatchHandler[] = [];
  const lineOf = (idx: number) => src.slice(0, idx).split('\n').length;

  const push = (start: number, body: string) => {
    const cleared = [...body.matchAll(CLEAR)].map((m) => m[1]!);
    const inlineEmpty = INLINE_EMPTY.test(body.trim());
    if (!cleared.length && !inlineEmpty) return;
    const hasSignal = SIGNAL.test(body);
    let key: string;
    if (cleared.length) {
      key = `${file}::${cleared[0]!}`;
    } else {
      // Inline `() => []`: name the call the catch was attached to (`api.listGrants()`).
      key = `${file}::${ownerOf(src, start)}`;
    }
    out.push({ key, line: lineOf(start), candidate: !hasSignal });
  };

  for (const m of src.matchAll(/\.catch\s*\(/g)) {
    const open = m.index + m[0].length - 1;
    const close = matchDelim(src, open, '(', ')');
    if (close < 0) continue;
    const inner = src.slice(open + 1, close);
    const arrowBlock = /=>\s*\{/.exec(inner);
    if (arrowBlock) {
      const bstart = open + 1 + arrowBlock.index + arrowBlock[0].length - 1;
      const bend = matchDelim(src, bstart, '{', '}');
      if (bend < 0) continue;
      push(m.index, src.slice(bstart + 1, bend));
    } else {
      push(m.index, inner);
    }
  }
  for (const m of src.matchAll(/(?<![.\w])catch\s*(\([^)]*\))?\s*\{/g)) {
    const bstart = m.index + m[0].length - 1;
    const bend = matchDelim(src, bstart, '{', '}');
    if (bend < 0) continue;
    const cleared = [...src.slice(bstart + 1, bend).matchAll(CLEAR)].map((x) => x[1]!);
    if (!cleared.length) continue;
    const body = src.slice(bstart + 1, bend);
    out.push({
      key: `${file}::${cleared[0]!}`,
      line: lineOf(m.index),
      candidate: !SIGNAL.test(body),
    });
  }
  return out;
}

/**
 * The `<script>` block of a `.vue` source. This guard parses its own views — it
 * is not an HTML sanitizer — but the regex still follows HTML semantics on
 * purpose, because tag names are case-insensitive there and an end tag may
 * carry whitespace/attributes (`</script >`, `</script foo="bar">`).
 *
 * The narrower form `/<script[^>]*>([\s\S]*?)<\/script>/` tripped CodeQL's
 * `js/bad-tag-filter` heuristic twice (#1160 CI, lines 404/471). Adding only
 * the `i` flag is NOT enough: the query reports the shortest applicable
 * message, so it would then fall through to "does not match script end tags
 * like </script >" for the same regexp. Both widenings are required.
 */
function scriptOf(src: string): string {
  return /<script[^>]*>([\s\S]*?)<\/script[^>]*>/i.exec(src)?.[1] ?? src;
}

/**
 * Rule 3 (#1160 supervisor review, the ninth site): a `try` block that awaits an
 * `api.*` read and has NO `catch` lets the rejection escape unhandled — the
 * caller draws its empty state and nothing on screen says the read failed.
 * `NextPlansView.refreshSeats` was exactly this shape (`try { … } finally { … }`),
 * which rules 1 and 2 both miss: rule 2 needs a catch to inspect, and rule 1
 * only sees the claim, not the missing error path.
 *
 * Scope note: the target is the console's `api.*` reads — the family's evidence
 * surface. Store actions own their error semantics (`auth.fetchMe` normalizes
 * every failure into `serviceUnavailable` and never rejects, which is why
 * `NextUnavailableView`'s try/finally is not a violation).
 *
 * This rule has NO exemption table on purpose: an instance is either fixed
 * (wrap in try/catch and record a visible error) or the read belongs to a
 * different, self-handling layer. If an exemption is ever genuinely needed, add
 * it the way tables 1 and 2 do — with a reason and a rot test.
 */
function collectUncaughtApiReads(src: string): number[] {
  const script = scriptOf(src);
  const lines: number[] = [];
  for (const m of script.matchAll(/(?<![.\w])try\s*\{/g)) {
    const bstart = m.index + m[0].length - 1;
    const bend = matchDelim(script, bstart, '{', '}');
    if (bend < 0) continue;
    const body = script.slice(bstart + 1, bend);
    if (!/\bawait\b/.test(body) || !body.includes('api.')) continue;
    let p = bend + 1;
    while (p < script.length && /\s/.test(script[p]!)) p++;
    if (script.slice(p, p + 5) === 'catch') continue;
    lines.push(script.slice(0, m.index).split('\n').length);
  }
  return lines;
}

// ---------------------------------------------------------------------------

describe('#1160 empty states and silent clears', () => {
  const files = readdirSync(dir).filter((f) => f.endsWith('.vue'));

  it('every 暂无 / 还没有 / 没有更多 string is gated on the load state', () => {
    // Anti-vacuous: a scan that finds nothing (wrong cwd, renamed dir) would be
    // silently green forever. The directory currently holds 37 view files with
    // 52 marked strings.
    expect(files.length, 'views/next 扫描不到文件——守卫会永久静默通过').toBeGreaterThanOrEqual(30);

    const sites: MarkSite[] = [];
    for (const file of files) {
      const collected = collectMarkSites(file, readFileSync(join(dir, file), 'utf8'));
      expect(collected, `${file} 模板无法解析`).not.toBeNull();
      sites.push(...collected!);
    }
    expect(sites.length, '扫到的空态文案太少——守卫可能已失效').toBeGreaterThanOrEqual(40);

    const offenders: string[] = [];
    for (const s of sites) {
      if (s.gated) continue;
      const exempt = TEXT_EXEMPT.some(([f, snip]) => f === s.file && s.snippet.includes(snip));
      if (!exempt) offenders.push(`${s.file}:${s.line} ${s.snippet.trim().slice(0, 80)}`);
    }
    expect(offenders).toEqual([]);
  });

  it('a catch that empties a list leaves a visible failure signal (or is exempted)', () => {
    const handlers: CatchHandler[] = [];
    for (const file of files) {
      handlers.push(...collectCatchHandlers(file, readFileSync(join(dir, file), 'utf8')));
    }
    // Anti-vacuous: the tree has ~205 catch handlers, 19 of which clear a list
    // (7 of them exempted).
    expect(handlers.length, '扫不到任何清空列表的 catch——守卫可能已失效').toBeGreaterThanOrEqual(8);

    const offenders: string[] = [];
    for (const h of handlers) {
      if (!h.candidate) continue;
      const exempt = CLEAR_EXEMPT.some(([key]) => key === h.key);
      if (!exempt) offenders.push(`${h.key} (line ${h.line})`);
    }
    expect(offenders).toEqual([]);
  });

  it('an awaited api read is never left without a catch (rule 3, no exemptions)', () => {
    let tryBlocks = 0;
    const offenders: string[] = [];
    for (const file of files) {
      const src = readFileSync(join(dir, file), 'utf8');
      const script = scriptOf(src);
      tryBlocks += [...script.matchAll(/(?<![.\w])try\s*\{/g)].length;
      for (const line of collectUncaughtApiReads(src)) {
        offenders.push(`${file}:${line}`);
      }
    }
    // Anti-vacuous: the tree currently holds ~194 try blocks, ~150 of them
    // awaiting an api call. A scan that finds nothing would be silently green.
    expect(tryBlocks, '扫不到任何 try 块——守卫可能已失效').toBeGreaterThanOrEqual(120);
    expect(offenders).toEqual([]);
  });

  it('exemptions carry a reason and are still required (no rot)', () => {
    for (const [file, snippet, reason] of TEXT_EXEMPT) {
      expect(reason.trim().length, `${file}::${snippet} 的豁免必须写明理由`).toBeGreaterThan(10);
      const collected = collectMarkSites(file, readFileSync(join(dir, file), 'utf8'));
      expect(collected, `${file} 模板无法解析`).not.toBeNull();
      const matching = (collected ?? []).filter((s) => s.snippet.includes(snippet));
      expect(
        matching.length,
        `${file}::${snippet} 豁免条目匹配不到任何文案——请删除（白名单腐烂）`,
      ).toBeGreaterThan(0);
      // Still needed means: at least one match would be a violation without the entry.
      expect(
        matching.some((s) => !s.gated),
        `${file}::${snippet} 已有门控，豁免不再需要——请删除该条目（白名单腐烂）`,
      ).toBe(true);
    }
    for (const [key, reason] of CLEAR_EXEMPT) {
      expect(reason.trim().length, `${key} 的豁免必须写明理由`).toBeGreaterThan(10);
      const [file, ref] = key.split('::');
      const handlers = collectCatchHandlers(file!, readFileSync(join(dir, file!), 'utf8'));
      const matching = handlers.filter((h) => h.key === `${file}::${ref}`);
      expect(
        matching.length,
        `${key} 豁免条目匹配不到任何清空——请删除（白名单腐烂）`,
      ).toBeGreaterThan(0);
      expect(
        matching.some((h) => h.candidate),
        `${key} 已带失败信号，豁免不再需要——请删除该条目（白名单腐烂）`,
      ).toBe(true);
    }
  });
});
