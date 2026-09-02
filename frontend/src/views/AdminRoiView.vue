<script setup lang="ts">
import { onMounted, ref } from 'vue';
import type { PrimaryTableCol } from 'tdesign-vue-next';
import * as api from '@/api';
import PageHeader from '@/components/PageHeader.vue';
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

function money(value: number): string {
  return `¥${value.toFixed(4)}`;
}

function pct(value: number): string {
  return `${value.toFixed(2)}%`;
}

const columns: PrimaryTableCol[] = [
  { colKey: 'date', title: '日期', width: 130 },
  {
    colKey: 'requests',
    title: '请求（上游 / 缓存命中）',
    minWidth: 200,
    cell: (h, { row }: { row: RoiReportView['byDay'][number] }) =>
      h('span', `${row.upstreamRequests} / ${row.hitRequests}`),
  },
  {
    colKey: 'hitRatePct',
    title: '命中率',
    width: 100,
    align: 'right',
    cell: (h, { row }: { row: RoiReportView['byDay'][number] }) => h('span', pct(row.hitRatePct)),
  },
  {
    colKey: 'paidCost',
    title: '上游实付',
    width: 130,
    align: 'right',
    cell: (h, { row }: { row: RoiReportView['byDay'][number] }) => h('span', money(row.paidCost)),
  },
  {
    colKey: 'savedCost',
    title: '缓存节省',
    width: 130,
    align: 'right',
    cell: (h, { row }: { row: RoiReportView['byDay'][number] }) => h('span', money(row.savedCost)),
  },
];

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
  // BOM so Excel opens UTF-8 correctly (same convention as the cost report).
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
  <div class="mk-page">
    <PageHeader
      title="缓存 ROI"
      description="响应缓存的省量与实付对比——数据决定缓存策略（原始设计文档 P5.4）。"
    >
      <template #actions>
        <t-button variant="outline" data-testid="roi-export" :disabled="!report" @click="exportCsv">
          导出 CSV
        </t-button>
      </template>
    </PageHeader>

    <div class="mk-filter-bar">
      <t-radio-group v-model="days" variant="default-filled" @change="load">
        <t-radio-button v-for="w in WINDOWS" :key="w.value" :value="w.value">{{
          w.label
        }}</t-radio-button>
      </t-radio-group>
      <t-button variant="text" :loading="loading" @click="load">刷新</t-button>
    </div>

    <t-alert v-if="loadError" theme="error" class="block-alert" :message="loadError" />
    <t-loading :loading="loading">
      <div v-if="report" class="roi-cards" data-testid="roi-report">
        <div class="mk-stat-card">
          <div class="mk-stat-value mk-stat-value--accent">
            {{ money(report.totals.savedCost) }}
          </div>
          <div class="mk-stat-label">缓存节省（无缓存时需多付）</div>
        </div>
        <div class="mk-stat-card">
          <div class="mk-stat-value">{{ money(report.totals.paidCost) }}</div>
          <div class="mk-stat-label">上游实付</div>
        </div>
        <div class="mk-stat-card">
          <div class="mk-stat-value">{{ pct(report.totals.savedPct) }}</div>
          <div class="mk-stat-label">等效折扣（节省 / 实付+节省）</div>
        </div>
        <div class="mk-stat-card">
          <div class="mk-stat-value">{{ pct(report.totals.hitRatePct) }}</div>
          <div class="mk-stat-label">
            请求命中率（{{ report.totals.l1Hits + report.totals.l2Hits }} /
            {{ report.totals.upstreamRequests + report.totals.l1Hits + report.totals.l2Hits }}）
          </div>
        </div>
      </div>

      <t-table
        v-if="report"
        data-testid="roi-daily-table"
        :data="report.byDay"
        :columns="columns"
        row-key="date"
        :table-layout="'fixed'"
      >
        <template #empty>
          <div class="mk-empty-hint">该窗口内没有流量记录。</div>
        </template>
      </t-table>
    </t-loading>
  </div>
</template>

<style scoped>
.roi-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}
.mk-stat-card {
  background: var(--td-bg-color-container);
  border: 1px solid var(--td-component-stroke);
  border-radius: var(--td-radius-default);
  padding: 16px;
}
.mk-stat-value {
  font-size: 22px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.mk-stat-value--accent {
  color: var(--td-brand-color);
}
.mk-stat-label {
  margin-top: 4px;
  color: var(--td-text-color-secondary);
  font-size: 12px;
}
</style>
