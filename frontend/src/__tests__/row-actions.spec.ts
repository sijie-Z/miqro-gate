/// <reference types="node" />
import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import { readFileSync, readdirSync } from 'node:fs';
import { UiButton } from '@/ui';

/**
 * Row-action convention guard (#651): every list page renders its row
 * operations as visible text-link actions. A chrome-less ghost button inside
 * an #actions slot reads as body copy, and a bare "⋯" icon trigger is easy to
 * miss entirely — both were the exact complaints this guards against.
 */
describe('row action convention (#651)', () => {
  const dir = 'src/views/next';
  const files = readdirSync(dir).filter((name) => name.endsWith('.vue'));

  function actionBlocks(source: string): string[] {
    const lines = source.split('\n');
    const blocks: string[] = [];
    let i = 0;
    while (i < lines.length) {
      const line = lines[i] ?? '';
      if (line.includes('<template #actions')) {
        const start = i;
        let depth = 0;
        do {
          const row = lines[i] ?? '';
          depth += (row.match(/<template/g) ?? []).length;
          depth -= (row.match(/<\/template>/g) ?? []).length;
          i += 1;
        } while (depth > 0 && i < lines.length);
        blocks.push(lines.slice(start, i).join('\n'));
      } else {
        i += 1;
      }
    }
    return blocks;
  }

  it('no ghost buttons inside #actions slots', () => {
    const offenders: string[] = [];
    for (const name of files) {
      const source = readFileSync(`${dir}/${name}`, 'utf-8');
      for (const block of actionBlocks(source)) {
        if (block.includes('variant="ghost"')) {
          offenders.push(name);
        }
      }
    }
    expect(offenders).toEqual([]);
  });

  it('dropdown row menus use the labeled 更多 link trigger', () => {
    const offenders: string[] = [];
    for (const name of files) {
      const source = readFileSync(`${dir}/${name}`, 'utf-8');
      for (const block of actionBlocks(source)) {
        if (block.includes('DropdownMenuTrigger') && !block.includes('ui-link-action')) {
          offenders.push(name);
        }
      }
    }
    expect(offenders).toEqual([]);
  });

  it('link variants carry the shared link-action classes', () => {
    const link = mount(UiButton, {
      props: { variant: 'link' },
      slots: { default: '模型目录' },
    });
    expect(link.classes()).toContain('ui-link-action');
    expect(link.classes()).not.toContain('ui-link-action--danger');

    const danger = mount(UiButton, {
      props: { variant: 'link-danger' },
      slots: { default: '删除' },
    });
    expect(danger.classes()).toContain('ui-link-action');
    expect(danger.classes()).toContain('ui-link-action--danger');
  });
});
