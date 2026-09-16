import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import UiTable from '@/ui/Table.vue';

const columns = [
  { key: 'name', title: '名称', sortable: true },
  { key: 'tokens', title: 'Tokens', align: 'right' as const, sortable: true },
];

const rows = [
  { id: 'a', name: 'claude-code-main', tokens: 40 },
  { id: 'b', name: 'codex-tools', tokens: 10 },
  { id: 'c', name: 'glm-agent', tokens: 30 },
];

/** UiTable is data-driven presentation; spec pins sorting/empty/loading/
 *  slot behaviour so page tests can trust the primitive. */
describe('UiTable', () => {
  it('renders rows via keyed cell slots with row/value bindings', () => {
    const wrapper = mount(UiTable, {
      props: { columns, data: rows },
      slots: {
        tokens: `<template #tokens="{ row }">T{{ row.tokens }}</template>`,
      },
    });
    expect(wrapper.text()).toContain('T40');
    expect(wrapper.text()).toContain('codex-tools');
  });

  it('sorts ascending then descending on click', async () => {
    const wrapper = mount(UiTable, { props: { columns, data: rows } });
    const cells = () => wrapper.findAll('tbody tr').map((row) => row.text());

    const nameHeader = wrapper.findAll('th button').find((b) => b.text().includes('名称'))!;
    await nameHeader.trigger('click');
    expect(cells()[0]).toContain('claude-code-main');

    await nameHeader.trigger('click');
    expect(cells()[0]).toContain('glm-agent');
  });

  it('right-aligned numeric column gets the tabular cell class', () => {
    const wrapper = mount(UiTable, { props: { columns, data: rows } });
    const numericCell = wrapper.findAll('td').find((td) => td.text() === '40')!;
    expect(numericCell.classes()).toContain('ui-table__cell--num');
  });

  it('renders the empty state title', () => {
    const wrapper = mount(UiTable, {
      props: { columns, data: [], emptyTitle: '还没有 Virtual Key' },
    });
    expect(wrapper.text()).toContain('还没有 Virtual Key');
  });

  it('renders skeleton rows while loading', () => {
    const wrapper = mount(UiTable, {
      props: { columns, data: rows, loading: true, skeletonRows: 3 },
    });
    expect(wrapper.findAll('.ui-skeleton').length).toBeGreaterThanOrEqual(3);
  });

  it('formats nullish cell values as an em dash', () => {
    const wrapper = mount(UiTable, {
      props: { columns: [{ key: 'name', title: '名称' }], data: [{ id: 'x', name: null }] },
    });
    expect(wrapper.text()).toContain('—');
  });
});
