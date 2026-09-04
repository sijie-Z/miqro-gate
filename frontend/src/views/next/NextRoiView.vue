<script setup lang="ts">
/**
 * NextRoiView — /app/roi v2 admin page (U2 platform batch).
 * Behaviour parity with the legacy cache-ROI report: window selector, four
 * total cards, day table and CSV export (BOM for Excel).
 */
import { onMounted, ref } from 'vue';
import * as api from '@/api';
import { UiButton, UiTable } from '@/ui';
import type { RoiReportView } from '@/types/api';

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

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    const to = new Date();
    const from = new Date(to.getTime() - days.value * 24 * 3600 * 1000);
    report.value = await api.getRoiReport(from.toISOString(), to.toISOString());
  } catch (err) {
    loadError.value = err instanceof Error ? err.message : '加载失败';
  } finally {
    loading.value = false;
  }
}

function exportCsv() {
  if (!report.value) return;
  const rows = report.value.byDay.map((d) =>
    [
      d.date,
      d.upstreamRequests,
      d.hitRequests,
      d.hitRatePct.toFixed(2),
      d.paidCost.toFixed(4),
      d.savedCost.toFixed(4),
    ].join(','),
  );
  const header = 'date,upstreamRequests,hitRequests,hitRatePct,paidCost,savedCost';
  const blob = new Blob([`﻿${header}\n${rows.join('\n')}`], { type: 'text/csv;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `cache-roi-${report.value.from.slice(0, 10)}_${report.value.to.slice(0, 10)}.csv`;
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
          money(report.totals.savedCost)
        }}</span>
        <span class="next-roi__label">缓存节省（无缓存时需多付）</span>
      </div>
      <div class="ui-panel next-roi__card">
        <span class="next-roi__value ui-num">{{ money(report.totals.paidCost) }}</span>
        <span class="next-roi__label">上游实付</span>
      </div>
      <div class="ui-panel next-roi__card">
        <span class="next-roi__value ui-num">{{ pct(report.totals.savedPct) }}</span>
        <span class="next-roi__label">等效折扣（节省 / 实付+节省）</span>
      </div>
      <div class="ui-panel next-roi__card">
        <span class="next-roi__value ui-num">{{ pct(report.totals.hitRatePct) }}</span>
        <span class="next-roi__label">请求命中率（L1+L2）</span>
      </div>
    </div>

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
          {{ (row as RoiReportView['byDay'][number]).upstreamRequests }} /
          {{ (row as RoiReportView['byDay'][number]).hitRequests }}
        </template>
        <template #hitRatePct="{ row }">{{
          pct((row as RoiReportView['byDay'][number]).hitRatePct)
        }}</template>
        <template #paidCost="{ row }">{{
          money((row as RoiReportView['byDay'][number]).paidCost)
        }}</template>
        <template #savedCost="{ row }">{{
          money((row as RoiReportView['byDay'][number]).savedCost)
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
  color: var(--ui-primary);
  font-weight: var(--ui-weight-semibold);
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
