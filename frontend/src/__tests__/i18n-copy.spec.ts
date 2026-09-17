import { describe, expect, it } from 'vitest';
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
