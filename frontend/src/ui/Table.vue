<script setup lang="ts">
/**
 * UiTable — v2 design-system data table.
 * Column-driven with header sort, loading skeleton rows and an empty state.
 * Cell rendering: slot named after the column key receives `{ row, value }`;
 * without a slot the value is stringified (— for null/undefined). Numeric
 * cells get .ui-table__cell--num (tabular) via `align: 'right'`.
 * Sortable columns toggle asc → desc → none; sorting happens inside the
 * table on a copy of the data (client-side, fine for console scale lists).
 */
import { computed, ref, useAttrs } from 'vue';

export interface UiTableColumn {
  key: string;
  title: string;
  width?: string;
  minWidth?: string;
  align?: 'left' | 'right' | 'center';
  sortable?: boolean;
  /** Optional value used for sorting when row[key] is not directly comparable. */
  sortValue?: (row: Record<string, unknown>) => number | string;
  /** Optional formatter; takes precedence over stringify, loses to slots. */
  format?: (value: unknown, row: Record<string, unknown>) => string;
}

type SortOrder = 'asc' | 'desc';

const props = withDefaults(
  defineProps<{
    columns: UiTableColumn[];
    /** Any row-shaped array; rows are read via column keys internally. */
    data: unknown[];
    rowKey?: string;
    loading?: boolean;
    emptyTitle?: string;
    emptyDescription?: string;
    skeletonRows?: number;
    /** Striped zebra for wide reference lists; hover stays on both. */
    striped?: boolean;
  }>(),
  {
    rowKey: 'id',
    loading: false,
    emptyTitle: '暂无数据',
    emptyDescription: '',
    skeletonRows: 5,
    striped: false,
  },
);

const attrs = useAttrs();

const sortKey = ref<string | null>(null);
const sortOrder = ref<SortOrder>('asc');

function toRecord(row: unknown): Record<string, unknown> {
  return (row ?? {}) as Record<string, unknown>;
}

function toggleSort(column: UiTableColumn) {
  if (!column.sortable) return;
  if (sortKey.value === column.key) {
    sortOrder.value = sortOrder.value === 'asc' ? 'desc' : 'asc';
  } else {
    sortKey.value = column.key;
    sortOrder.value = 'asc';
  }
}

function sortIndicator(column: UiTableColumn): 'asc' | 'desc' | '' {
  if (!column.sortable || sortKey.value !== column.key) return '';
  return sortOrder.value;
}

const sortedData = computed<Record<string, unknown>[]>(() => {
  const rows = props.data.map(toRecord);
  const column = props.columns.find((c) => c.key === sortKey.value);
  if (!column) return rows;
  const asc = sortOrder.value === 'asc';
  return [...rows].sort((a, b) => {
    let va: unknown = a[column.key];
    let vb: unknown = b[column.key];
    if (column.sortValue) {
      va = column.sortValue(a);
      vb = column.sortValue(b);
    }
    if (va === vb) return 0;
    const left = va ?? '';
    const right = vb ?? '';
    const cmp =
      typeof left === 'number' && typeof right === 'number'
        ? left - right
        : String(left).localeCompare(String(right), 'zh-Hans-CN');
    return asc ? cmp : -cmp;
  });
});

function cellValue(column: UiTableColumn, row: Record<string, unknown>): unknown {
  const raw = row[column.key];
  if (column.format) return column.format(raw, row);
  return raw;
}
</script>

<template>
  <div class="ui-table" v-bind="attrs">
    <div class="ui-table__scroll">
      <table class="ui-table__grid">
        <thead>
          <tr>
            <th
              v-for="column in columns"
              :key="column.key"
              :style="{ width: column.width, minWidth: column.minWidth }"
              :class="[
                'ui-table__head',
                {
                  'ui-table__head--right': column.align === 'right',
                  'ui-table__head--center': column.align === 'center',
                },
              ]"
            >
              <button
                v-if="column.sortable"
                type="button"
                class="ui-table__sort"
                :class="{ 'ui-table__sort--active': sortKey === column.key }"
                @click="toggleSort(column)"
              >
                <span>{{ column.title }}</span>
                <svg
                  class="ui-table__sort-arrow"
                  :class="{ 'ui-table__sort-arrow--desc': sortIndicator(column) === 'desc' }"
                  width="12"
                  height="12"
                  viewBox="0 0 16 16"
                  fill="none"
                  aria-hidden="true"
                >
                  <path
                    v-if="sortIndicator(column) !== 'asc'"
                    d="M8 3.5 3 8.5h10L8 3.5Z"
                    fill="currentColor"
                  />
                  <path v-else d="M8 12.5 3 7.5h10l-5 5Z" fill="currentColor" />
                </svg>
              </button>
              <span v-else>{{ column.title }}</span>
            </th>
          </tr>
        </thead>
        <tbody>
          <template v-if="loading">
            <tr v-for="n in skeletonRows" :key="`skeleton-${n}`" class="ui-table__row">
              <td v-for="column in columns" :key="column.key" class="ui-table__cell">
                <span class="ui-skeleton" :style="{ width: `${40 + ((n * 17) % 50)}%` }"
                  >&nbsp;</span
                >
              </td>
            </tr>
          </template>
          <template v-else-if="sortedData.length">
            <tr
              v-for="row in sortedData"
              :key="row[rowKey] as string"
              class="ui-table__row"
              :class="{ 'ui-table__row--striped': striped }"
            >
              <td
                v-for="column in columns"
                :key="column.key"
                :class="[
                  'ui-table__cell',
                  {
                    'ui-table__cell--num': column.align === 'right',
                    'ui-table__cell--center': column.align === 'center',
                  },
                ]"
              >
                <slot :name="column.key" :row="row" :value="cellValue(column, row)">
                  {{ cellValue(column, row) ?? '—' }}
                </slot>
              </td>
            </tr>
          </template>
          <tr v-else>
            <td :colspan="columns.length" class="ui-table__empty">
              <slot name="empty" :title="emptyTitle" :description="emptyDescription">
                <div class="ui-table__empty-body">
                  <p class="ui-table__empty-title">{{ emptyTitle }}</p>
                  <p v-if="emptyDescription" class="ui-table__empty-desc">{{ emptyDescription }}</p>
                </div>
              </slot>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.ui-table {
  width: 100%;
  font-size: var(--ui-font-size-sm);
  line-height: var(--ui-line-height-base);
  color: var(--ui-foreground);
}

.ui-table__scroll {
  overflow-x: auto;
}

.ui-table__grid {
  width: 100%;
  border-collapse: collapse;
  table-layout: auto;
}

.ui-table__head {
  text-align: left;
  padding: 0 var(--ui-space-3);
  height: var(--ui-row-height);
  border-bottom: 1px solid var(--ui-border);
  font-size: var(--ui-font-size-xs);
  font-weight: var(--ui-weight-semibold);
  letter-spacing: 0.01em;
  color: #45454d;
  background: #f3f1ec;
  white-space: nowrap;
}

.ui-table__head--right {
  text-align: right;
}

.ui-table__head--center {
  text-align: center;
}

.ui-table__sort {
  display: inline-flex;
  align-items: center;
  gap: var(--ui-space-1);
  border: none;
  background: none;
  padding: 0;
  font: inherit;
  font-size: inherit;
  font-weight: inherit;
  color: inherit;
  cursor: pointer;
}

.ui-table__sort:hover {
  color: var(--ui-foreground);
}

.ui-table__sort--active {
  color: var(--ui-primary);
}

.ui-table__sort-arrow {
  color: var(--ui-foreground-faint);
}

.ui-table__sort--active .ui-table__sort-arrow {
  color: currentColor;
}

.ui-table__row {
  border-bottom: 1px solid var(--ui-border);
  transition: background-color var(--ui-ease);
}

.ui-table__row:hover {
  background: var(--ui-fill-hover);
}

.ui-table__row--striped:nth-child(even) {
  background: var(--ui-background);
}

.ui-table__row--striped:hover {
  background: var(--ui-fill-hover);
}

.ui-table__cell {
  padding: 0 var(--ui-space-3);
  height: var(--ui-row-height);
  vertical-align: middle;
  white-space: nowrap;
}

.ui-table__cell--num {
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.ui-table__cell--center {
  text-align: center;
}

.ui-table__empty {
  height: 180px;
  text-align: center;
  vertical-align: middle;
  border-bottom: none;
}

.ui-table__empty-title {
  margin: 0;
  font-size: var(--ui-font-size-base);
  font-weight: var(--ui-weight-medium);
  color: var(--ui-foreground);
}

.ui-table__empty-desc {
  margin: var(--ui-space-1) 0 0;
  font-size: var(--ui-font-size-sm);
  color: var(--ui-foreground-secondary);
}
</style>
