<script setup lang="ts">
/**
 * UiTable — v2 design-system data table.
 * Column-driven with header sort, loading skeleton rows and an empty state.
 * Cell rendering: slot named after the column key receives `{ row, value }`;
 * without a slot the value is stringified (— for null/undefined). Numeric
 * cells get .ui-table__cell--num (tabular) via `align: 'right'`.
 * Sortable columns toggle asc → desc → none; sorting happens inside the
 * table on a copy of the data (client-side, fine for console scale lists).
 *
 * #657: the default empty state can carry a link CTA (`emptyActionLabel` +
 * `emptyActionTo`) so a list says where to go next instead of leaving the
 * user at a dead end. Pages whose call-to-action opens an in-page form keep
 * overriding the `#empty` slot (see NextKeysView).
 */
import { computed, ref, useAttrs } from 'vue';
// Imported rather than left to the global registration: a `<router-link>` in a
// UiTable would otherwise make every mount of this component (and of the ~30
// views embedding it) resolve a component it never needed, and the compiler
// hoists that resolution above the v-if — so every test that mounts a UiTable
// without a RouterLink stub logs "Failed to resolve component" even when no
// CTA is rendered.
import { RouterLink, type RouteLocationRaw } from 'vue-router';
import UiButton from './Button.vue';

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
    /** Empty-state link CTA; rendered only when both label and target are set. */
    emptyActionLabel?: string;
    emptyActionTo?: RouteLocationRaw;
    /**
     * #1065: a non-empty message means the last load failed. The table then
     * shows that message with a retry instead of the empty state — a failed
     * read must not be presented as "no rows", which is what the empty state
     * asserts. Pages pass their `loadError` here.
     */
    error?: string;
    skeletonRows?: number;
    /** Striped zebra for wide reference lists; hover stays on both. */
    striped?: boolean;
  }>(),
  {
    rowKey: 'id',
    loading: false,
    emptyTitle: '暂无数据',
    emptyDescription: '',
    emptyActionLabel: '',
    emptyActionTo: '',
    error: '',
    skeletonRows: 5,
    striped: false,
  },
);

const attrs = useAttrs();

const emit = defineEmits<{ (e: 'rowClick', row: unknown): void; (e: 'retry'): void }>();

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
              :class="{
                'ui-table__row--striped': striped,
                'ui-table__row--clickable': !!attrs.onRowClick,
              }"
              @click="emit('rowClick', row)"
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
              <div v-if="error" class="ui-table__empty-body" data-testid="table-load-failed">
                <span class="ui-table__empty-mark ui-table__empty-mark--error" aria-hidden="true">
                  <svg width="26" height="26" viewBox="0 0 24 24" fill="none">
                    <circle cx="12" cy="12" r="8.5" stroke="currentColor" stroke-width="1.6" />
                    <path
                      d="M12 7.8v5.4"
                      stroke="currentColor"
                      stroke-width="1.6"
                      stroke-linecap="round"
                    />
                    <circle cx="12" cy="16.4" r="1" fill="currentColor" />
                  </svg>
                </span>
                <p class="ui-table__empty-title">加载失败</p>
                <p class="ui-table__empty-desc">{{ error }}</p>
                <UiButton variant="secondary" data-testid="table-load-retry" @click="emit('retry')">
                  重试
                </UiButton>
              </div>
              <slot v-else name="empty" :title="emptyTitle" :description="emptyDescription">
                <div class="ui-table__empty-body">
                  <span class="ui-table__empty-mark" aria-hidden="true">
                    <svg width="26" height="26" viewBox="0 0 24 24" fill="none">
                      <rect
                        x="4"
                        y="5"
                        width="16"
                        height="14"
                        rx="2.5"
                        stroke="currentColor"
                        stroke-width="1.6"
                      />
                      <path
                        d="M4 10.5h16M9 14.5h6"
                        stroke="currentColor"
                        stroke-width="1.6"
                        stroke-linecap="round"
                      />
                    </svg>
                  </span>
                  <p class="ui-table__empty-title">{{ emptyTitle }}</p>
                  <p v-if="emptyDescription" class="ui-table__empty-desc">{{ emptyDescription }}</p>
                  <router-link
                    v-if="emptyActionLabel && emptyActionTo"
                    class="ui-link-action ui-table__empty-action"
                    :to="emptyActionTo"
                    data-testid="table-empty-action"
                  >
                    {{ emptyActionLabel }}
                  </router-link>
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
  font-size: var(--ui-font-size-base);
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
  font-size: var(--ui-font-size-base);
  font-weight: var(--ui-weight-semibold);
  color: var(--ui-foreground);
  background: var(--ui-muted);
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
  /* Expanded hit area (>=32px) without shifting the header layout. */
  padding: 6px 2px;
  margin: -6px -2px;
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
  color: var(--ui-primary-text);
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
  background: var(--ui-row-hover);
}

.ui-table__row--striped:nth-child(even) {
  background: var(--ui-background);
}

.ui-table__row--striped:hover {
  background: var(--ui-row-hover);
}

.ui-table__row--clickable {
  cursor: pointer;
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

.ui-table__empty-body {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--ui-space-2);
  padding: var(--ui-space-4);
}

.ui-table__empty-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 52px;
  height: 52px;
  margin-bottom: var(--ui-space-1);
  border-radius: var(--ui-radius-panel);
  background: var(--ui-muted);
  color: var(--ui-foreground-faint);
}

/* #1065: the failed-load mark reads as a problem, not as "nothing here". */
.ui-table__empty-mark--error {
  background: var(--ui-danger-bg);
  color: var(--ui-danger-fg);
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

.ui-table__empty-action {
  margin-top: var(--ui-space-2);
}
</style>
