<script setup lang="ts">
/**
 * NextRoiView — /app/roi v2 admin page (U2 platform batch).
 * Behaviour parity with the legacy cache-ROI report: window selector, four
 * total cards, day table and CSV export (BOM for Excel).
 */
import { computed, onMounted, ref  } from 'vue';
import * as api from '@/api';
import { UiButton, UiDonut, UiTable } from '@/ui';
import { csvCell } from '@/utils/csv';
import type { RoiReportView } from '@/types/generated-api';

const report = ref<RoiReportView | null>(null);
const loading = ref(true);
const loadError = ref('');
const days = ref(30);

const WINDOWS = [
  { label: '近 7 天', value: 7 },
  { label: '近 30 天', value: 30 },
  { label: '近 93 天', value: 93 },
];

const columns = [
  { key: 'date', title: '日期', width: '140px' },
  { key: 'requests', title: '请求（上游 / 命中）', minWidth: '180px' },
  { key: 'hitRatePct', title: '命中率', width: '110px', align: 'right' as const },
  { key: 'paidCost', title: '上游实付', width: '140px', align: 'right' as const },
  { key: 'savedCost', title: '缓存节省', width: '140px', align: 'right' as const },
];

function money(value: number): string {
  return `¥${value.toFixed(4)}`;
}

function pct(value: number): string {
  return `${value.toFixed(2)}%`;
}

/** UiTable row slots are generic records; narrow to the report's day shape. */
/** Savings vs paid split for the selected window (donut centre = discount). */
const savingSegments = computed(() => {
  const saved = Number(report.value?.totals?.savedCost ?? 0);
  const paid = Number(report.value?.totals?.paidCost ?? 0);
  const rows = [];
  if (saved > 0) rows.push({ label: '缓存节省', value: saved, color: '#389e0d' });
  if (paid > 0) rows.push({ label: '上游实付', value: paid, color: '#0960bd' });
  return rows;
});

function asDay(row: unknown): NonNullable<RoiReportView['byDay']>[number] {
  return row as NonNullable<RoiReportView['byDay']>[number];
}

// #440: request-sequence guard — a slow window load must not land after the
// user switched windows (numbers must match the highlighted range).
let loadRequestSeq = 0;

async function load() {
  const seq = ++loadRequestSeq;
  loading.value = true;
  loadError.value = '';
  try {
    const to = new Date();
    const from = new Date(to.getTime() - days.value * 24 * 3600 * 1000);
    const result = await api.getRoiReport(from.toISOString(), to.toISOString());
    if (seq !== loadRequestSeq) {
      return; // a newer window won — this response is stale
    }
    report.value = result;
  } catch (err) {
    if (seq === loadRequestSeq) {
      loadError.value = err instanceof Error ? err.message : '加载失败';
    }
  } finally {
    if (seq === loadRequestSeq) {
      loading.value = false;
    }
  }
}

function exportCsv() {
  if (!report.value) return;
  const rows = (report.value.byDay ?? []).map((d) =>
    [
      d.date,
      d.upstreamRequests,
      d.hitRequests,
      (d.hitRatePct ?? 0).toFixed(2),
      (d.paidCost ?? 0).toFixed(4),
      (d.savedCost ?? 0).toFixed(4),
    ]
      .map(csvCell)
      .join(','),
  );
  const header = 'date,upstreamRequests,hitRequests,hitRatePct,paidCost,savedCost';
  const blob = new Blob([`﻿${header}\n${rows.join('\n')}`], { type: 'text/csv;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `cache-roi-${(report.value.from ?? '').slice(0, 10)}_${(report.value.to ?? '').slice(0, 10)}.csv`;
  a.click();
  URL.revokeObjectURL(url);
}

onMounted(load);
</script>

<template>
  <div class="ui-page next-roi">
    <header class="ui-page-header">
      <div>
        <h1 class="ui-page-title">缓存收益</h1>
        <p class="ui-page-desc">响应缓存的省量与实付对比——数据决定缓存策略。</p>
      </div>
      <div class="ui-page-actions">
        <UiButton
          variant="secondary"
          data-testid="roi-export"
          :disabled="!report"
          @click="exportCsv"
        >
          导出 CSV
        </UiButton>
      </div>
    </header>

    <div class="next-roi__toolbar">
      <div class="next-roi__segmented" role="tablist" aria-label="窗口">
        <button
          v-for="w in WINDOWS"
          :key="w.value"
          type="button"
          class="next-roi__seg"
          :class="{ 'next-roi__seg--on': days === w.value }"
          :data-testid="`roi-window-${w.value}`"
          @click="
            days = w.value;
            load();
          "
        >
          {{ w.label }}
        </button>
      </div>
      <UiButton variant="ghost" size="sm" :loading="loading" @click="load">刷新</UiButton>
    </div>

    <div v-if="loadError" class="ui-alert ui-alert--error">{{ loadError }}</div>

    <div v-if="report" class="next-roi__cards" data-testid="roi-report">
      <div class="ui-panel next-roi__card">
        <span class="next-roi__value next-roi__value--accent ui-num">{{
          money(report.totals?.savedCost ?? 0)
        }}</span>
        <span class="next-roi__label">缓存节省（无缓存时需多付）</span>
      </div>
      <div class="ui-panel next-roi__card">
        <span class="next-roi__value ui-num">{{ money(report.totals?.paidCost ?? 0) }}</span>
        <span class="next-roi__label">上游实付</span>
      </div>
      <div class="ui-panel next-roi__card">
        <span class="next-roi__value ui-num">{{ pct(report.totals?.savedPct ?? 0) }}</span>
        <span class="next-roi__label">等效折扣（节省 / 实付+节省）</span>
      </div>
      <div class="ui-panel next-roi__card">
        <span class="next-roi__value ui-num">{{ pct(report.totals?.hitRatePct ?? 0) }}</span>
        <span class="next-roi__label">请求命中率（L1+L2）</span>
      </div>
    </div>

    <section
      v-if="savingSegments.length"
      class="ui-panel next-roi__summary"
      data-testid="roi-saving-dist"
    >
      <div class="ui-panel-head">
        <div>
          <h2 class="ui-panel-title">缓存收益构成</h2>
          <span class="ui-panel-sub">缓存节省 vs 上游实付 · 当前窗口</span>
        </div>
      </div>
      <div class="ui-panel-body next-roi__summary-body">
        <UiDonut
          :segments="savingSegments"
          :center-text="pct(report?.totals?.savedPct ?? 0)"
          data-testid="roi-saving-donut"
        />
        <div class="ui-legend">
          <div v-for="seg in savingSegments" :key="seg.label" class="ui-legend-row">
            <span class="ui-legend-dot" :style="{ background: seg.color }" />
            <span class="ui-legend-label">{{ seg.label }}</span>
            <span class="ui-legend-pct ui-num">{{ Math.round((seg.value / Math.max(0.0001, savingSegments.reduce((x, y) => x + y.value, 0))) * 100) }}%</span>
            <span class="ui-legend-value ui-num">{{ money(seg.value) }}</span>
          </div>
        </div>
      </div>
    </section>

    <section class="ui-panel">
      <div class="ui-panel-toolbar">
        <span class="ui-panel-sub">按日明细</span>
      </div>
      <UiTable
        :columns="columns"
        :data="report?.byDay ?? []"
        :loading="loading"
        row-key="date"
        empty-title="该窗口没有缓存命中数据"
        data-testid="roi-table"
      >
        <template #requests="{ row }">
          {{ asDay(row).upstreamRequests }} /
          {{ asDay(row).hitRequests }}
        </template>
        <template #hitRatePct="{ row }">{{
          pct(asDay(row).hitRatePct ?? 0)
        }}</template>
        <template #paidCost="{ row }">{{
          money(asDay(row).paidCost ?? 0)
        }}</template>
        <template #savedCost="{ row }">{{
          money(asDay(row).savedCost ?? 0)
        }}</template>
      </UiTable>
    </section>
  </div>
</template>

<style scoped>
.ui-alert {
  padding: var(--ui-space-3) var(--ui-space-4);
  margin-bottom: var(--ui-space-4);
  border-radius: var(--ui-radius-control);
  font-size: var(--ui-font-size-sm);
}

.ui-alert--error {
  background: var(--ui-danger-bg);
  color: var(--ui-danger-fg);
}

.next-roi__toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--ui-space-5);
}

.next-roi__segmented {
  display: inline-flex;
  gap: var(--ui-space-1);
  padding: var(--ui-space-1);
  background: var(--ui-muted);
  border: 1px solid var(--ui-border-muted);
  border-radius: var(--ui-radius-control);
}

.next-roi__seg {
  height: 30px;
  padding: 0 var(--ui-space-3);
  border: 0;
  border-radius: calc(var(--ui-radius-control) - 2px);
  background: transparent;
  color: var(--ui-foreground-secondary);
  font-size: var(--ui-font-size-sm);
  font-weight: var(--ui-weight-medium);
  cursor: pointer;
}

.next-roi__seg:hover {
  color: var(--ui-foreground);
}

.next-roi__seg--on {
  background: var(--ui-card);
  border: 1px solid var(--ui-border);
  color: var(--ui-primary-text);
  font-weight: var(--ui-weight-semibold);
}

.next-roi__summary {
  margin-bottom: var(--ui-space-5);
}

.next-roi__summary-body {
  display: flex;
  align-items: center;
  gap: var(--ui-space-6);
  flex-wrap: wrap;
}

.next-roi__cards {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--ui-space-4);
  margin-bottom: var(--ui-space-5);
}

@media (max-width: 1100px) {
  .next-roi__cards {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

.next-roi__card {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-2);
  padding: var(--ui-space-5);
}

.next-roi__value {
  font-size: 24px;
  font-weight: var(--ui-weight-semibold);
  letter-spacing: -0.01em;
}

.next-roi__value--accent {
  color: var(--ui-success-fg);
}

.next-roi__label {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
}
</style>
