/// <reference types="node" />
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { translateText } from '@/i18n';

/** #657 copy coverage. The i18n layer swaps text **node by node**, so every
 *  string these views render — including each half of the filtered count line —
 *  needs its own dictionary or pattern entry（docs/frontend-design.md：新增文案
 *  全部进 EN 词典）。 */
describe('EN dictionary covers the #657 copy', () => {
  const copies: Array<[string, string]> = [
    ['查看全部', 'View all'],
    ['查看全部授权', 'View all grants'],
    ['该凭证还没有被任何授权引用', 'No grant references this credential yet'],
    ['仅看凭证「deepseek-main」', 'Credential "deepseek-main" only'],
    [
      '必填，同一租户内唯一，最长 64 个字符。',
      'Required, unique within the tenant, up to 64 characters.',
    ],
    ['必填，最长 200 个字符。', 'Required, up to 200 characters.'],
  ];

  it.each(copies)('%s → %s', (zh, en) => {
    expect(translateText(zh)).toBe(en);
  });

  it('translates both halves of the filtered grants count line', () => {
    // 共 N 条授权 and （全部 M 条） are two adjacent text nodes: the template
    // v-if splits them, so each is looked up separately. The count rule predates
    // this batch and the i18n layer has no plural forms, so "1 grants" is pinned
    // here as what that existing rule yields — not as preferred English.
    expect(translateText('共 1 条授权')).toBe('1 grants');
    expect(translateText('（全部 2 条）')).toBe(' (2 total)');
  });
});

/** #735 adapter-status persistent warning copy. Same node-by-node rule: the
 *  banner's three lines and the row/dialog markers are separate text nodes. */
describe('EN dictionary covers the #735 adapter-status warning copy', () => {
  const copies: Array<[string, string]> = [
    ['⚠ 非已验证', '⚠ Not validated'],
    // The DISABLED label has no other dictionary entry; the persistent-warning
    // dialog now renders it next to an English sentence.
    ['已停用', 'Disabled'],
    [
      '目录中存在未处于「已验证」状态的产品',
      'Some catalogue products are not in the "VERIFIED" state',
    ],
    ['该产品未处于「已验证」状态', 'This product is not in the "VERIFIED" state'],
    [
      '未处于「已验证」状态的产品仍按当前配置可用（已停用的除外），但不应承载生产流量；本提示不改变产品的启用与可用行为。',
      'Products that are not in the "VERIFIED" state stay usable under their current configuration — except disabled ones — but must not carry production traffic. This notice does not change which products are enabled or usable.',
    ],
  ];

  it.each(copies)('%s → %s', (zh, en) => {
    expect(translateText(zh)).toBe(en);
  });

  it('translates the unverified count line', () => {
    expect(translateText('共 3 个产品实例当前不是 VERIFIED。')).toBe(
      '3 product instances are not currently VERIFIED.',
    );
  });
});

/** #714: a credential referenced by an ACTIVE agent answers 409 on rotate/disable.
 *  Views render those errors as `${message}（requestId: …）` in a single text
 *  node, so the dictionary has to cover the bare message and the toast wrapper. */
describe('EN dictionary covers the #714 binding-immutability copy', () => {
  const copies: Array<[string, string]> = [
    [
      '凭证已被 Agent「客服助手」引用，不能轮换；请先停用该 Agent。',
      'The credential is referenced by agent "客服助手" and cannot be rotated — disable that agent first.',
    ],
    [
      '凭证已被 Agent「客服助手」引用，不能停用；请先停用该 Agent。',
      'The credential is referenced by agent "客服助手" and cannot be disabled — disable that agent first.',
    ],
  ];

  it.each(copies)('%s → %s', (zh, en) => {
    expect(translateText(zh)).toBe(en);
  });

  it('keeps the interpolated requestId when the toast is one text node', () => {
    // What NextCredentialsView.vue:336 actually renders on a failed disable.
    expect(
      translateText(
        '凭证已被 Agent「客服助手」引用，不能停用；请先停用该 Agent。（requestId: 5d1c0a）',
      ),
    ).toBe(
      'The credential is referenced by agent "客服助手" and cannot be disabled — disable that agent first.（requestId: 5d1c0a）',
    );
    // requestId absent → the view falls back to "-".
    expect(
      translateText('凭证已被 Agent「客服助手」引用，不能轮换；请先停用该 Agent。（requestId: -）'),
    ).toBe(
      'The credential is referenced by agent "客服助手" and cannot be rotated — disable that agent first.（requestId: -）',
    );
  });

  it('leaves an uncovered message with a requestId suffix untranslated', () => {
    // Half-translated error copy would be worse than falling back to Chinese.
    expect(translateText('凭证已被别的什么引用（requestId: 5d1c0a）')).toBeNull();
  });
});

/** #935 copy drift, not missing copy: the views were renamed in Chinese and the
 *  dictionary was not. Both strings below ARE covered by a PATTERNS entry — the
 *  entry's regex still spells the old "吊销 Virtual Key" source, so the lookup
 *  misses and English mode falls back to Chinese. */
describe('EN dictionary covers the #935 rotate/revoke dialog titles', () => {
  const copies: Array<[string, string]> = [
    ['轮换虚拟密钥「prod-key」', 'Rotate Virtual Key "prod-key"'],
    ['吊销虚拟密钥「prod-key」', 'Revoke Virtual Key "prod-key"'],
  ];

  it.each(copies)('%s → %s', (zh, en) => {
    expect(translateText(zh)).toBe(en);
  });

  it('matches the disable title that shares the same 「name」 template', () => {
    // Regression anchor for the two cases above: 停用 goes through the identical
    // DialogTitle text node and already translates, so EN mode currently shows an
    // English body and button under a Chinese title on the same destructive dialog.
    expect(translateText('停用虚拟密钥「prod-key」')).toBe('Disable Virtual Key "prod-key"');
  });
});

/** #936 same rename damage, different mechanism: these DICT keys are matched by
 *  exact equality after whitespace collapsing, and each carries a stray space
 *  where the Latin word "Secret" used to sit. The views dropped the space in the
 *  same commit, so the lookup misses and these render in Chinese under EN. The
 *  inputs below are verbatim what the views set — see the render sites in the
 *  comments. */
describe('EN dictionary covers the #936 stray-space copy', () => {
  const copies: Array<[string, string]> = [
    // NextCredentialsView.vue:151 (form error) / :391 (page lede)
    ['名称、订阅与密钥必填。', 'Name, subscription and secret are required.'],
    [
      '真实供应商 API 密钥的加密托管与版本管理；密钥明文仅录入时可见一次。',
      'Encrypted custody and version management for real provider API keys; the plaintext secret is visible only once at entry.',
    ],
    // NextAdminWebhooksView.vue:138 (form error)
    ['名称、URL 与签名密钥必填。', 'Name, URL and signing secret are required.'],
    // NextAdminAgentsView.vue:125 (destructive confirm body) / :268 (empty state)
    [
      '禁用后该代理不再计为可用，其凭证不受影响。',
      'Once disabled the agent no longer counts as available; its credential is unaffected.',
    ],
    [
      '创建代理并绑定出口凭证后，可按代理维度观测用量。',
      'Create an agent bound to an egress credential to observe usage per agent.',
    ],
    // NextAdminConsumersView.vue:283 (destructive confirm body) / :705 (dialog lede)
    [
      '吊销后该 API 密钥立即失效，外部系统将无法再调用计费查询接口。',
      'Once revoked the API key stops working immediately and external systems can no longer call the billing query endpoint.',
    ],
    [
      '控制这把消费者密钥能访问哪些通道；能力不足的调用会被拒绝（403 CONSUMER_SCOPE_DENIED / consumer_scope_denied）。',
      'Controls which channels this consumer key may access; calls beyond its capability are rejected (403 CONSUMER_SCOPE_DENIED / consumer_scope_denied).',
    ],
    // NextAdminMcpServicesView.vue:321 (form error) / :2930 (dialog lede)
    [
      'API 密钥模式必须填写密钥（每次保存都需要重新填写）。',
      'API Key mode requires the secret (re-enter it on every save).',
    ],
    [
      '控制网关调用该 MCP 服务时向上游携带的凭据：访客模式不携带；API 密钥模式由网关注入 Authorization: Bearer <密钥>（密钥只写不读）。',
      'Controls the credential the gateway presents upstream when calling this MCP service: guest mode presents none; API Key mode injects Authorization: Bearer <secret> (write-only).',
    ],
  ];

  it.each(copies)('%s → %s', (zh, en) => {
    expect(translateText(zh)).toBe(en);
  });

  it('leaves no CJK inside a PATTERNS replacement value', () => {
    // Root-cause guard for #935. translateText substitutes $1 into these values
    // verbatim, so a replacement containing Chinese makes English mode emit a
    // half-translated string even when its regex matches. 3fbdb45b renamed the view
    // copy to the Chinese 虚拟密钥 wording and rewrote only the replacement, leaving
    // the sources spelling the old Latin term — so these two entries were both
    // unreachable and half-Chinese. Sources are now Chinese, outputs English.
    const dict = readFileSync('src/i18n/dict.ts', 'utf8').split(/\r?\n/);
    const start = dict.findIndex((l) => l.startsWith('export const PATTERNS'));
    expect(start).toBeGreaterThan(-1);

    const offending: string[] = [];
    for (let i = start; i < dict.length; i++) {
      const m = /^\s{2}\[(\/.*?\/[a-z]*),\s*('(?:[^'\\]|\\.)*')/.exec(dict[i]!);
      if (!m) continue;
      const replacement = m[2]!.slice(1, -1).replace(/\\'/g, "'").replace(/\\\\/g, '\\');
      if (/[一-鿿]/.test(replacement)) offending.push(`dict.ts:${i + 1} ${replacement}`);
    }
    expect(offending).toEqual([]);
  });
});

/** #946 the same #532 damage as #935, one dialog over: 3fbdb45b renamed this title
 *  禁用 Agent「name」 → 禁用代理「name」, and the pattern source stayed Latin, so the
 *  entry stopped matching. This is the destructive disable-agent dialog whose BODY
 *  #936 fixes, so leaving it would put an English body and button under a Chinese
 *  title — the exact symptom #935 is about. */
describe('EN dictionary covers the #946 disable-agent dialog title', () => {
  it('translates the composed confirm title', () => {
    expect(translateText('禁用代理「prod-key」')).toBe('Disable agent "prod-key"');
  });
});
