import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import { computed, defineComponent, h } from 'vue';
import UiTable from '@/ui/Table.vue';

/** RouterLink stub that publishes its target so tests can assert jump targets. */
const RouterLinkStub = defineComponent({
  name: 'RouterLink',
  inheritAttrs: false,
  props: { to: { type: [String, Object], default: '' } },
  setup(props, { slots, attrs }) {
    const target = computed(() =>
      typeof props.to === 'string' ? props.to : JSON.stringify(props.to),
    );
    return () => h('a', { ...attrs, 'data-router-to': target.value }, slots.default?.());
  },
});

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

  it('renders the empty-state CTA only when a label and a target are given (#657)', () => {
    const plain = mount(UiTable, {
      props: { columns, data: [], emptyTitle: '还没有授权' },
    });
    expect(plain.find('[data-testid="table-empty-action"]').exists()).toBe(false);

    const wrapper = mount(UiTable, {
      props: {
        columns,
        data: [],
        emptyTitle: '该凭证还没有被任何授权引用',
        emptyActionLabel: '查看全部授权',
        emptyActionTo: { name: 'grants' },
      },
      global: { stubs: { RouterLink: RouterLinkStub } },
    });
    const cta = wrapper.find('[data-testid="table-empty-action"]');
    expect(cta.text()).toBe('查看全部授权');
    expect(cta.classes()).toContain('ui-link-action');
    expect(JSON.parse(cta.attributes('data-router-to') as string)).toEqual({ name: 'grants' });
  });

  it('keeps the #empty slot authoritative for pages with their own empty state', () => {
    const wrapper = mount(UiTable, {
      props: {
        columns,
        data: [],
        emptyActionLabel: '查看全部授权',
        emptyActionTo: { name: 'grants' },
      },
      slots: { empty: '<p class="own-empty">自己写空态</p>' },
      global: { stubs: { RouterLink: RouterLinkStub } },
    });
    expect(wrapper.find('.own-empty').exists()).toBe(true);
    expect(wrapper.find('[data-testid="table-empty-action"]').exists()).toBe(false);
  });

  it('formats nullish cell values as an em dash', () => {
    const wrapper = mount(UiTable, {
      props: { columns: [{ key: 'name', title: '名称' }], data: [{ id: 'x', name: null }] },
    });
    expect(wrapper.text()).toContain('—');
  });
});
