<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import type { PrimaryTableCol } from 'tdesign-vue-next';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import PageHeader from '@/components/PageHeader.vue';
import type { UsageGroup, UsageSummary } from '@/types/api';

const WINDOWS = [
  { label: '近 7 天', days: 7 },
  { label: '近 30 天', days: 30 },
  { label: '近 93 天', days: 93 },
] as const;

const windowDays = ref(30);
const mode = ref<'project' | 'day'>('project');
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');

const projectSummary = ref<UsageSummary | null>(null);
const daySummary = ref<UsageSummary | null>(null);

const projectGroups = computed(() => projectSummary.value?.groups ?? []);
const dayGroups = computed(() => daySummary.value?.groups ?? []);

const totalCost = computed(() => projectSummary.value?.totals.cost.projectAllocated ?? '0');
const upstreamCost = computed(() => projectSummary.value?.totals.cost.upstreamPaid ?? '0');
const totalRequests = computed(() => projectSummary.value?.totals.requests.upstream ?? 0);
const totalTokens = computed(
  () =>
    (projectSummary.value?.totals.tokens.input ?? 0) +
    (projectSummary.value?.totals.tokens.output ?? 0),
);

function fromIso(days: number): string {
  const d = new Date();
  d.setDate(d.getDate() - days);
  return d.toISOString();
}

const projectColumns: PrimaryTableCol[] = [
  { colKey: 'label', title: '项目', minWidth: 200 },
  { colKey: 'requests', title: '请求', width: 100, align: 'right' },
  { colKey: 'tokens', title: 'Tokens', width: 140, align: 'right' },
  { colKey: 'cost', title: '分摊成本', width: 140, align: 'right' },
  { colKey: 'share', title: '占比', width: 220 },
];

const dayColumns: PrimaryTableCol[] = [
  { colKey: 'label', title: '日期', minWidth: 140 },
  { colKey: 'requests', title: '请求', width: 100, align: 'right' },
  { colKey: 'tokens', title: 'Tokens', width: 140, align: 'right' },
  { colKey: 'cost', title: '分摊成本', width: 140, align: 'right' },
];

function costNumber(value: string | undefined): number {
  return Number(value ?? '0');
}

function costOf(group: UsageGroup): number {
  return costNumber(group.cost.projectAllocated);
}

function tokensOf(group: UsageGroup): number {
  return (group.tokens.input ?? 0) + (group.tokens.output ?? 0);
}

function shareOf(group: UsageGroup): number {
  const total = costNumber(totalCost.value);
  return total > 0 ? costOf(group) / total : 0;
}

function formatCost(value: string | number): string {
  return `¥${Number(value).toFixed(4)}`;
}

function formatCount(value: number): string {
  if (value >= 1_000_000) {
    return `${(value / 1_000_000).toFixed(1)}M`;
  }
  if (value >= 1_000) {
    return `${(value / 1_000).toFixed(1)}k`;
  }
  return String(value);
}

async function load() {
  loading.value = true;
  loadError.value = '';
  const from = fromIso(windowDays.value);
  try {
    const [projects, days] = await Promise.all([
      api.adminUsageSummary({ groupBy: 'project', from }),
      api.adminUsageSummary({ groupBy: 'day', from }),
    ]);
    projectSummary.value = projects;
    daySummary.value = days;
  } catch (error) {
    if (error instanceof ApiError) {
      loadError.value = error.message;
      loadRequestId.value = error.requestId ?? '';
    } else {
      loadError.value = '加载成本报表失败。';
    }
  } finally {
    loading.value = false;
  }
}

function exportCsv() {
  const groups = mode.value === 'project' ? projectGroups.value : dayGroups.value;
  if (!groups.length) {
    MessagePlugin.warning('当前筛选下没有数据可导出');
    return;
  }
  const header = ['分组', '请求', 'Tokens', '分摊成本(CNY)'];
  const rows = groups.map((g) => [
    g.label,
    String(g.requests.upstream),
    String(tokensOf(g)),
    costOf(g).toFixed(4),
  ]);
  const csv = [header, ...rows]
    .map((row) => row.map((cell) => `"${String(cell).replace(/"/g, '""')}"`).join(','))
    .join('\n');
  const blob = new Blob(['﻿' + csv], { type: 'text/csv;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `cost-report-${mode.value}-${fromIso(windowDays.value).slice(0, 10)}.csv`;
  a.click();
  URL.revokeObjectURL(url);
}

function switchWindow(days: number) {
  windowDays.value = days;
  void load();
}

onMounted(load);
</script>

<template>
  <div class="cost-page">
    <PageHeader
      title="成本报表"
      description="按项目与时间维度的成本分摊视图；单价来自「定价」目录，分摊结果由成本计算任务生成。"
    >
      <template #actions>
        <t-button variant="outline" data-testid="cost-export" @click="exportCsv">导出 CSV</t-button>
      </template>
    </PageHeader>

    <t-alert v-if="loadError" theme="error" class="block-alert" data-testid="cost-load-error">
      {{ loadError
      }}<span v-if="loadRequestId" class="mk-mono">requestId: {{ loadRequestId }}</span>
    </t-alert>

    <div class="mk-filter-bar" data-testid="cost-filter-bar">
      <t-radio-group v-model="mode" variant="default-filled" data-testid="cost-mode" @change="load">
        <t-radio-button value="project">按项目</t-radio-button>
        <t-radio-button value="day">按天</t-radio-button>
      </t-radio-group>
      <t-radio-group
        :value="windowDays"
        variant="default-filled"
        data-testid="cost-window"
        @change="switchWindow"
      >
        <t-radio-button v-for="w in WINDOWS" :key="w.days" :value="w.days">
          {{ w.label }}
        </t-radio-button>
      </t-radio-group>
    </div>

    <div class="mk-stat-grid" data-testid="cost-stats">
      <div class="mk-stat-card">
        <span class="mk-stat-label">分摊总成本</span>
        <span class="mk-stat-value mk-num">{{ formatCost(totalCost) }}</span>
        <span class="mk-stat-hint">按项目分摊口径</span>
      </div>
      <div class="mk-stat-card">
        <span class="mk-stat-label">上游已付成本</span>
        <span class="mk-stat-value mk-num">{{ formatCost(upstreamCost) }}</span>
        <span class="mk-stat-hint">按最新单价估算</span>
      </div>
      <div class="mk-stat-card">
        <span class="mk-stat-label">请求</span>
        <span class="mk-stat-value mk-num">{{ formatCount(totalRequests) }}</span>
        <span class="mk-stat-hint">到达上游的请求数</span>
      </div>
      <div class="mk-stat-card">
        <span class="mk-stat-label">Tokens</span>
        <span class="mk-stat-value mk-num">{{ formatCount(totalTokens) }}</span>
        <span class="mk-stat-hint">输入 + 输出</span>
      </div>
    </div>

    <t-loading :loading="loading" size="small" show-overlay>
      <t-table
        v-if="mode === 'project'"
        row-key="groupKey"
        size="small"
        :columns="projectColumns"
        :data="projectGroups"
        class="cost-table"
        data-testid="cost-project-table"
      >
        <template #label="{ row }">
          <span class="cost-label">{{ row.label }}</span>
        </template>
        <template #requests="{ row }">
          <span class="mk-num">{{ formatCount(row.requests.upstream) }}</span>
        </template>
        <template #tokens="{ row }">
          <span class="mk-num">{{ formatCount(tokensOf(row)) }}</span>
        </template>
        <template #cost="{ row }">
          <span class="mk-num">{{ formatCost(costOf(row)) }}</span>
        </template>
        <template #share="{ row }">
          <div class="share-cell">
            <div class="share-track">
              <div class="share-fill" :style="{ width: `${Math.round(shareOf(row) * 100)}%` }" />
            </div>
            <span class="mk-num share-text">{{ Math.round(shareOf(row) * 100) }}%</span>
          </div>
        </template>
        <template #empty>
          <div class="table-empty">
            <p>该时间窗口内没有成本数据。</p>
            <p class="hint">在「定价」页录入单价后，成本才会被计算。</p>
          </div>
        </template>
      </t-table>

      <t-table
        v-else
        row-key="groupKey"
        size="small"
        :columns="dayColumns"
        :data="dayGroups"
        class="cost-table"
        data-testid="cost-day-table"
      >
        <template #label="{ row }">
          <span class="cost-label">{{ row.label }}</span>
        </template>
        <template #requests="{ row }">
          <span class="mk-num">{{ formatCount(row.requests.upstream) }}</span>
        </template>
        <template #tokens="{ row }">
          <span class="mk-num">{{ formatCount(tokensOf(row)) }}</span>
        </template>
        <template #cost="{ row }">
          <span class="mk-num">{{ formatCost(costOf(row)) }}</span>
        </template>
        <template #empty>
          <div class="table-empty">该时间窗口内没有成本数据。</div>
        </template>
      </t-table>
    </t-loading>
  </div>
</template>

<style scoped>
.block-alert {
  margin-bottom: 16px;
}

.cost-table {
  width: 100%;
  background: var(--miqrokey-bg-surface);
  border: 1px solid var(--miqrokey-border-default);
  border-radius: var(--miqrokey-radius-panel);
}

.cost-label {
  font-weight: 500;
}

.share-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}

.share-track {
  flex: 1;
  height: 6px;
  border-radius: 3px;
  background: var(--miqrokey-bg-subtle);
  overflow: hidden;
}

.share-fill {
  height: 100%;
  border-radius: 3px;
  background: var(--miqrokey-accent);
}

.share-text {
  min-width: 40px;
  text-align: right;
  font-size: 12px;
}

.table-empty {
  padding: 24px 0;
  color: var(--miqrokey-text-secondary);
}

.table-empty .hint {
  font-size: 13px;
  color: var(--miqrokey-text-disabled);
  margin: 4px 0 0;
}
</style>
