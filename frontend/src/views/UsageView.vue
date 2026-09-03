<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import * as api from '@/api';
import PageHeader from '@/components/PageHeader.vue';
import { ApiError } from '@/api/http';
import type {
  QuotaLevel,
  QuotaMetric,
  QuotaPeriod,
  QuotaRuleView,
  UsageGroupBy,
  UsageSummary,
  UsageRecordPage,
} from '@/types/api';

const groupBy = ref<UsageGroupBy>('project');
const summary = ref<UsageSummary | null>(null);
const summaryLoading = ref(true);
const summaryError = ref('');

const records = ref<UsageRecordPage | null>(null);
const recordsLoading = ref(true);
const recordsError = ref('');
const page = ref(1);
const pageSize = ref(20);

// ---- self-service quota visibility (F04) ----

const myQuotaRules = ref<QuotaRuleView[]>([]);
const quotaLoading = ref(true);

const quotaMetricText: Record<QuotaMetric, string> = { TOKENS: 'Token 用量', REQUESTS: '请求次数' };
const quotaPeriodText: Record<QuotaPeriod, string> = {
  DAILY: '每日',
  WEEKLY: '每周',
  MONTHLY: '每月',
};
const quotaLevelText: Record<QuotaLevel, string> = {
  NORMAL: '正常',
  WARNING: '预警',
  EXCEEDED: '超限',
};

function quotaLevelClass(level: QuotaLevel): string {
  if (level === 'EXCEEDED') return 'mk-status--danger';
  if (level === 'WARNING') return 'mk-status--warning';
  return 'mk-status--success';
}

async function loadQuota() {
  quotaLoading.value = true;
  try {
    myQuotaRules.value = await api.listMyQuotaRules();
  } catch {
    myQuotaRules.value = []; // panel degrades silently — usage views stay usable
  } finally {
    quotaLoading.value = false;
  }
}

const summaryColumns = [
  { colKey: 'group', title: '分组', minWidth: 160 },
  { colKey: 'requests', title: '请求', width: 100, align: 'right' },
  { colKey: 'inputTokens', title: '输入 tokens', width: 130, align: 'right' },
  { colKey: 'outputTokens', title: '输出 tokens', width: 130, align: 'right' },
  { colKey: 'cacheRead', title: 'Cache 读', width: 120, align: 'right' },
  { colKey: 'upstreamCost', title: '上游成本', width: 130, align: 'right' },
  { colKey: 'gatewayCost', title: '网关观测成本', width: 140, align: 'right' },
];

const recordsColumns = [
  { colKey: 'occurredAt', title: '时间', width: 170 },
  { colKey: 'modelId', title: '模型', minWidth: 180 },
  { colKey: 'cacheLevel', title: '级别', width: 110 },
  { colKey: 'input', title: '输入', width: 100, align: 'right' },
  { colKey: 'output', title: '输出', width: 100, align: 'right' },
  { colKey: 'latency', title: '延迟', width: 90, align: 'right' },
  { colKey: 'upstreamStatus', title: '上游状态', width: 100, align: 'right' },
  { colKey: 'providerRequestId', title: '供应商请求 ID', minWidth: 200 },
];

const usageBars = computed(() => {
  const ranked = (summary.value?.groups ?? [])
    .map((g) => ({
      label: g.label,
      value: (g.tokens?.input ?? 0) + (g.tokens?.output ?? 0),
    }))
    .sort((a, b) => b.value - a.value)
    .slice(0, 8);
  const max = Math.max(...ranked.map((r) => r.value), 1);
  return ranked.map((r) => ({ ...r, width: `${Math.max(4, (r.value / max) * 100)}%` }));
});

const cacheLevelLabel: Record<string, string> = {
  UPSTREAM: 'upstream',
  COALESCED: 'coalesced',
  L1_HIT: 'L1 hit',
  L2_HIT: 'L2 hit',
};

onMounted(() => {
  void loadSummary();
  void loadRecords();
  void loadQuota();
});

async function loadSummary() {
  summaryLoading.value = true;
  summaryError.value = '';
  try {
    summary.value = await api.usageSummary(groupBy.value);
  } catch (error) {
    if (error instanceof ApiError) {
      summaryError.value = `${error.message}（requestId: ${error.requestId ?? '-'}）`;
    } else {
      summaryError.value = '加载用量汇总失败。';
    }
  } finally {
    summaryLoading.value = false;
  }
}

async function loadRecords() {
  recordsLoading.value = true;
  recordsError.value = '';
  try {
    records.value = await api.usageRecords({ page: page.value, size: pageSize.value });
  } catch (error) {
    if (error instanceof ApiError) {
      recordsError.value = `${error.message}（requestId: ${error.requestId ?? '-'}）`;
    } else {
      recordsError.value = '加载用量明细失败。';
    }
  } finally {
    recordsLoading.value = false;
  }
}

function changeGroupBy(value: UsageGroupBy) {
  groupBy.value = value;
  void loadSummary();
}

/** Exports every record of the current filter (all pages) as CSV. */
async function exportRecords() {
  const size = 200; // records API upper bound
  const all: UsageRecordPage['items'] = [];
  let pageNo = 1;
  try {
    for (;;) {
      const batch = await api.usageRecords({ page: pageNo, size });
      all.push(...batch.items);
      if (pageNo * size >= batch.total) {
        break;
      }
      pageNo += 1;
    }
  } catch (error) {
    MessagePlugin.error(error instanceof ApiError ? error.message : '导出失败，请稍后重试。');
    return;
  }
  if (!all.length) {
    MessagePlugin.warning('当前筛选下没有可导出的记录');
    return;
  }
  const header = [
    '时间',
    '模型',
    '级别',
    '输入 tokens',
    '输出 tokens',
    '延迟(ms)',
    '上游状态',
    '供应商请求 ID',
  ];
  const rows = all.map((r) => [
    r.occurredAt,
    r.modelId ?? '',
    cacheLevelLabel[r.cacheLevel] ?? r.cacheLevel,
    String(r.inputTokens ?? ''),
    String(r.outputTokens ?? ''),
    String(r.latencyMs ?? ''),
    String(r.upstreamStatusCode ?? ''),
    r.providerRequestId ?? '',
  ]);
  const csv = [header, ...rows]
    .map((row) => row.map((cell) => `"${String(cell).replace(/"/g, '""')}"`).join(','))
    .join('\n');
  const blob = new Blob(['﻿' + csv], { type: 'text/csv;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `usage-records-${new Date().toISOString().slice(0, 10)}.csv`;
  a.click();
  URL.revokeObjectURL(url);
}

function changePage(pageInfo: { current: number; pageSize: number }): void {
  page.value = pageInfo.current;
  pageSize.value = pageInfo.pageSize;
  void loadRecords();
}

function formatCost(value?: string): string {
  if (value === undefined || value === null) {
    return '—';
  }
  const num = Number(value);
  if (Number.isNaN(num)) {
    return value;
  }
  return `$${num.toFixed(4)}`;
}

function formatNumber(value?: number): string {
  return value === undefined || value === null ? '—' : value.toLocaleString();
}

function formatTime(iso: string): string {
  return new Date(iso).toLocaleString();
}
</script>

<template>
  <div class="usage-page">
    <PageHeader title="用量" description="仅统计你名下 Virtual Key 产生的用量。">
      <template #actions>
        <t-button variant="outline" data-testid="usage-export" @click="exportRecords">
          导出 CSV
        </t-button>
      </template>
    </PageHeader>

    <!-- Self-service quota visibility (F04) -->
    <section class="quota-panel" data-testid="my-quota-panel">
      <div class="panel-head">
        <span class="panel-title">我的配额</span>
        <span class="panel-sub"
          >管理员为你设置的用户级限额；当前窗口用量实时计算，超限仅提示不阻断。</span
        >
      </div>
      <t-loading :loading="quotaLoading" size="small" show-overlay>
        <div v-if="!quotaLoading && myQuotaRules.length === 0" class="quota-empty">
          暂无配额规则——管理员未为你设置用量限额。
        </div>
        <div
          v-for="rule in myQuotaRules"
          :key="rule.id"
          class="quota-row"
          data-testid="my-quota-row"
        >
          <div class="quota-row-head">
            <span class="quota-dim"
              >{{ quotaMetricText[rule.metric] }} · {{ quotaPeriodText[rule.period] }}</span
            >
            <span v-if="rule.status === 'DISABLED'" class="mk-status mk-status--neutral">停用</span>
            <span v-else class="mk-status" :class="quotaLevelClass(rule.level)">{{
              quotaLevelText[rule.level]
            }}</span>
          </div>
          <div class="quota-row-body">
            <span class="quota-nums"
              >限额 {{ rule.limitValue.toLocaleString() }} · 本期用量
              {{ rule.used.toLocaleString() }}（{{ rule.usedPct }}%）</span
            >
            <div class="quota-bar">
              <div
                class="quota-bar-fill"
                :style="{
                  width: `${Math.min(100, rule.usedPct)}%`,
                  background:
                    rule.level === 'EXCEEDED'
                      ? 'var(--td-error-color)'
                      : rule.level === 'WARNING'
                        ? 'var(--td-warning-color)'
                        : 'var(--td-brand-color)',
                }"
              ></div>
            </div>
          </div>
        </div>
      </t-loading>
    </section>

    <!-- Summary -->
    <section class="summary-panel">
      <div class="summary-toolbar">
        <span class="toolbar-label">按维度分组</span>
        <t-select
          :model-value="groupBy"
          class="group-select"
          data-testid="summary-groupby"
          @change="(value: unknown) => changeGroupBy(value as UsageGroupBy)"
        >
          <t-option value="project" label="项目" />
          <t-option value="virtual_key" label="Virtual Key" />
          <t-option value="cache_level" label="缓存级别" />
          <t-option value="day" label="日期" />
        </t-select>
      </div>

      <t-alert v-if="summaryError" theme="error" :close-btn="false" class="block-alert" />

      <t-loading :loading="summaryLoading" size="small" show-overlay>
        <t-table
          row-key="id"
          size="small"
          :columns="summaryColumns"
          :data="summary?.groups ?? []"
          class="summary-table"
          data-testid="summary-table"
        >
          <template #group="{ row }">{{ row.label || row.groupKey }}</template>
          <template #requests="{ row }">{{
            row.requests.upstream + row.requests.coalesced + row.requests.l1Hit + row.requests.l2Hit
          }}</template>
          <template #inputTokens="{ row }">
            <span class="mk-num">{{ formatNumber(row.tokens.input) }}</span>
          </template>
          <template #outputTokens="{ row }">
            <span class="mk-num">{{ formatNumber(row.tokens.output) }}</span>
          </template>
          <template #cacheRead="{ row }">
            <span class="mk-num">{{ formatNumber(row.tokens.cacheRead) }}</span>
          </template>
          <template #upstreamCost="{ row }">
            <span class="mk-num">{{ formatCost(row.cost.upstreamPaid) }}</span>
          </template>
          <template #gatewayCost="{ row }">
            <span class="mk-num">{{ formatCost(row.cost.gatewayObserved) }}</span>
          </template>
          <template #empty>
            <div class="table-empty">当前时间范围内没有用量记录。</div>
          </template>
        </t-table>
      </t-loading>

      <div v-if="summary" class="totals-row" data-testid="summary-totals">
        <span class="totals-label">合计</span>
        <span class="mk-num"
          >{{
            formatNumber(summary.totals.requests.upstream + summary.totals.requests.coalesced)
          }}
          请求</span
        >
        <span class="mk-num"
          >{{ formatNumber(summary.totals.tokens.input) }} 输入 /
          {{ formatNumber(summary.totals.tokens.output) }} 输出 tokens</span
        >
        <span class="mk-num">{{ formatCost(summary.totals.cost.upstreamPaid) }} 上游成本</span>
      </div>
    </section>

    <!-- Records -->
    <section class="records-panel">
      <section v-if="usageBars.length" class="mk-card chart-card" data-testid="usage-chart">
        <div class="mk-card-header">
          <h3 class="mk-card-title">用量分布</h3>
          <span class="mk-stat-hint">按 Tokens（输入 + 输出）Top 8</span>
        </div>
        <div class="mk-card-body">
          <div class="mk-bar-chart">
            <div v-for="bar in usageBars" :key="bar.label" class="mk-bar-row">
              <span class="mk-bar-label" :title="bar.label">{{ bar.label }}</span>
              <div class="mk-bar-track">
                <div class="mk-bar-fill" :style="{ width: bar.width }" />
              </div>
              <span class="mk-bar-value mk-num">{{ bar.value }}</span>
            </div>
          </div>
        </div>
      </section>

      <h3 class="panel-title">最近记录</h3>
      <t-alert v-if="recordsError" theme="error" :close-btn="false" class="block-alert" />

      <t-loading :loading="recordsLoading" size="small" show-overlay>
        <t-table
          row-key="id"
          size="small"
          :columns="recordsColumns"
          :data="records?.items ?? []"
          class="records-table"
          data-testid="records-table"
        >
          <template #occurredAt="{ row }">{{ formatTime(row.occurredAt) }}</template>
          <template #modelId="{ row }">
            <span class="mk-mono">{{ row.modelId }}</span>
          </template>
          <template #cacheLevel="{ row }">
            <t-tag size="small" variant="light">{{
              cacheLevelLabel[row.cacheLevel] ?? row.cacheLevel
            }}</t-tag>
          </template>
          <template #input="{ row }">
            <span class="mk-num">{{ formatNumber(row.inputTokens) }}</span>
          </template>
          <template #output="{ row }">
            <span class="mk-num">{{ formatNumber(row.outputTokens) }}</span>
          </template>
          <template #latency="{ row }">
            <span class="mk-num">{{
              row.latencyMs === null || row.latencyMs === undefined ? '—' : `${row.latencyMs}ms`
            }}</span>
          </template>
          <template #upstreamStatus="{ row }">{{ row.upstreamStatusCode ?? '—' }}</template>
          <template #providerRequestId="{ row }">
            <span class="mk-mono">{{ row.providerRequestId || '—' }}</span>
          </template>
          <template #empty>
            <div class="table-empty">没有用量记录。</div>
          </template>
        </t-table>
      </t-loading>

      <div v-if="records && records.total > 0" class="pagination-row">
        <span class="mk-num total-text">共 {{ records.total }} 条</span>
        <t-pagination
          v-model:current="page"
          v-model:page-size="pageSize"
          :total="records.total"
          @change="changePage"
        />
      </div>
    </section>
  </div>
</template>

<style scoped>
.page-header {
  margin-bottom: 16px;
}

.page-title {
  margin: 0;
  font-size: var(--miqrokey-font-size-title);
  font-weight: 600;
  color: var(--miqrokey-text-primary);
}

.page-desc {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--miqrokey-text-secondary);
}

.block-alert {
  margin-bottom: 16px;
}

.summary-panel,
.records-panel {
  background: var(--miqrokey-bg-surface);
  border: 1px solid var(--miqrokey-border-default);
  border-radius: var(--miqrokey-radius-panel);
  padding: 16px 20px 20px;
  margin-bottom: 20px;
}

.summary-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
}

.toolbar-label {
  font-size: 13px;
  color: var(--miqrokey-text-secondary);
}

.group-select {
  width: 180px;
}

.panel-title {
  margin: 0 0 12px;
  font-size: 15px;
  font-weight: 600;
}

.totals-row {
  display: flex;
  gap: 24px;
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px solid var(--miqrokey-border-muted);
  font-size: 13px;
  color: var(--miqrokey-text-secondary);
}

.totals-label {
  font-weight: 600;
  color: var(--miqrokey-text-primary);
}

.pagination-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 12px;
}

.total-text {
  font-size: 13px;
  color: var(--miqrokey-text-secondary);
}

.table-empty {
  padding: 16px 0;
  color: var(--miqrokey-text-secondary);
}

.quota-panel {
  margin-bottom: 16px;
  padding: 16px;
  border: 1px solid var(--td-component-stroke);
  border-radius: var(--td-radius-default);
}
.panel-head {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 8px;
}
.panel-title {
  font-weight: 600;
}
.panel-sub {
  color: var(--td-text-color-secondary);
  font-size: 12px;
}
.quota-empty {
  padding: 8px 0;
  color: var(--td-text-color-secondary);
  font-size: 13px;
}
.quota-row {
  padding: 10px 0;
  border-top: 1px solid var(--td-component-stroke);
}
.quota-row-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.quota-dim {
  font-weight: 500;
}
.quota-row-body {
  margin-top: 6px;
  display: flex;
  align-items: center;
  gap: 16px;
}
.quota-nums {
  color: var(--td-text-color-secondary);
  font-size: 12px;
  flex-shrink: 0;
}
.quota-bar {
  flex: 1;
  height: 6px;
  border-radius: 3px;
  background: var(--td-bg-color-component);
  overflow: hidden;
}
.quota-bar-fill {
  height: 100%;
  border-radius: 3px;
}
</style>
