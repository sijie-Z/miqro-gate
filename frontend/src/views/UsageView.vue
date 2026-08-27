<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import * as api from '@/api';
import PageHeader from '@/components/PageHeader.vue';
import { ApiError } from '@/api/http';
import type { UsageGroupBy, UsageSummary, UsageRecordPage } from '@/types/api';

const groupBy = ref<UsageGroupBy>('project');
const summary = ref<UsageSummary | null>(null);
const summaryLoading = ref(true);
const summaryError = ref('');

const records = ref<UsageRecordPage | null>(null);
const recordsLoading = ref(true);
const recordsError = ref('');
const page = ref(1);
const pageSize = ref(20);

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

function changePage(value: number) {
  page.value = value;
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
    <PageHeader title="Usage" description="仅统计你名下 Virtual Key 产生的用量。" />

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
          @current-change="changePage"
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
</style>
