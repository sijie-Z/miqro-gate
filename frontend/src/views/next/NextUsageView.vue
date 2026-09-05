<script setup lang="ts">
/**
 * NextUsageView — /app-new/usage pilot page (UI U0, PostHog language).
 * Behaviour parity with legacy UsageView: self-service quota panel (F04),
 * dimension-grouped summary + totals, top-8 token distribution bars and the
 * paged records table with CSV export. Rendering only — APIs untouched.
 */
import { computed, onMounted, ref } from 'vue';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import { UiButton, UiSelect, UiStatusBadge, UiTable, toast } from '@/ui';
import type { UiSelectOption } from '@/ui';
import type {
  QuotaLevel,
  QuotaMetric,
  QuotaPeriod,
  QuotaRuleView,
  UsageGroup,
  UsageGroupBy,
  UsageRecordPage,
  UsageSummary,
} from '@/types/api';
import type { UsageRecord } from '@/types/generated-api';

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

function quotaLevelTone(
  level: QuotaLevel,
  status: QuotaRuleView['status'],
): 'success' | 'warning' | 'danger' | 'neutral' {
  if (status === 'DISABLED') return 'neutral';
  if (level === 'EXCEEDED') return 'danger';
  if (level === 'WARNING') return 'warning';
  return 'success';
}

const summaryColumns = [
  { key: 'group', title: '分组', minWidth: '160px' },
  { key: 'requests', title: '请求', width: '100px', align: 'right' as const },
  { key: 'inputTokens', title: '输入 Token', width: '130px', align: 'right' as const },
  { key: 'outputTokens', title: '输出 Token', width: '130px', align: 'right' as const },
  { key: 'cacheRead', title: '缓存读取', width: '120px', align: 'right' as const },
  { key: 'upstreamCost', title: '上游成本', width: '130px', align: 'right' as const },
  { key: 'gatewayCost', title: '网关观测成本', width: '150px', align: 'right' as const },
];

const recordsColumns = [
  { key: 'occurredAt', title: '时间', width: '180px' },
  { key: 'modelId', title: '模型', minWidth: '180px' },
  { key: 'cacheLevel', title: '级别', width: '110px' },
  { key: 'input', title: '输入', width: '100px', align: 'right' as const },
  { key: 'output', title: '输出', width: '100px', align: 'right' as const },
  { key: 'latency', title: '延迟', width: '90px', align: 'right' as const },
  { key: 'upstreamStatus', title: '上游状态', width: '100px', align: 'right' as const },
  { key: 'providerRequestId', title: '供应商请求 ID', minWidth: '220px' },
];

const groupByOptions: UiSelectOption[] = [
  { value: 'project', label: '项目' },
  { value: 'virtual_key', label: 'Virtual Key' },
  { value: 'cache_level', label: '缓存级别' },
  { value: 'day', label: '日期' },
];

const cacheLevelLabel: Record<string, string> = {
  UPSTREAM: 'upstream',
  COALESCED: 'coalesced',
  L1_HIT: 'L1 hit',
  L2_HIT: 'L2 hit',
};

const usageBars = computed(() => {
  const ranked = (summary.value?.groups ?? [])
    .map((g) => ({
      label: g.label,
      value: (g.tokens?.input ?? 0) + (g.tokens?.output ?? 0),
      cost: g.cost?.upstreamPaid,
    }))
    .sort((a, b) => b.value - a.value)
    .slice(0, 8);
  const max = Math.max(...ranked.map((r) => r.value), 1);
  return ranked.map((r, index) => ({
    ...r,
    width: `${Math.max(4, (r.value / max) * 100)}%`,
    alpha: Math.max(0.28, 0.85 - index * 0.11),
  }));
});

const totalPages = computed(() => {
  if (!records.value || records.value.total === 0) return 1;
  return Math.ceil(records.value.total / pageSize.value);
});

onMounted(() => {
  void loadSummary();
  void loadRecords();
  void loadQuota();
});

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

function changeGroupBy(value: string) {
  groupBy.value = value as UsageGroupBy;
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
      if (pageNo * size >= batch.total) break;
      pageNo += 1;
    }
  } catch (error) {
    toast.error(error instanceof ApiError ? error.message : '导出失败，请稍后重试。');
    return;
  }
  if (!all.length) {
    toast.info('当前筛选下没有可导出的记录');
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

function gotoPage(next: number) {
  if (next < 1 || next > totalPages.value) return;
  page.value = next;
  void loadRecords();
}

function quotaBarFill(rule: QuotaRuleView): string {
  if (rule.status === 'DISABLED') return 'var(--ui-border-strong)';
  if (rule.level === 'EXCEEDED') return 'var(--ui-danger-fg)';
  if (rule.level === 'WARNING') return 'var(--ui-warning-fg)';
  return 'var(--ui-primary)';
}

/** Typed row accessors keep template expressions free of TS casts (prettier
 *  cannot parse `<` type syntax inside SFC interpolation). */
function asGroup(row: unknown): UsageGroup {
  return row as UsageGroup;
}

function asRecord(row: unknown): UsageRecord {
  return row as UsageRecord;
}

function formatCost(value?: string): string {
  if (value === undefined || value === null) return '—';
  const num = Number(value);
  if (Number.isNaN(num)) return value;
  return `$${num.toFixed(4)}`;
}

function formatNumber(value?: number): string {
  return value === undefined || value === null ? '—' : value.toLocaleString();
}

function formatTime(iso: string): string {
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}
</script>

<template>
  <div class="ui-page next-usage">
    <header class="ui-page-header">
      <div>
        <h1 class="ui-page-title">用量</h1>
        <p class="ui-page-desc">仅统计你名下 Virtual Key 产生的用量。</p>
      </div>
      <div class="ui-page-actions">
        <UiButton variant="primary" data-testid="usage-export" @click="exportRecords">
          导出 CSV
        </UiButton>
      </div>
    </header>

    <!-- Self-service quota visibility (F04) -->
    <section class="ui-panel next-usage__panel" data-testid="my-quota-panel">
      <div class="ui-panel-head">
        <div>
          <h2 class="ui-panel-title">我的配额</h2>
          <span class="ui-panel-sub"
            >管理员为你设置的用户级限额；当前窗口用量实时计算，超限仅提示不阻断。</span
          >
        </div>
      </div>
      <div class="ui-panel-body">
        <div v-if="!quotaLoading && myQuotaRules.length === 0" class="next-usage__quota-empty">
          暂无配额规则——管理员未为你设置用量限额。
        </div>
        <div
          v-for="rule in myQuotaRules"
          :key="rule.id"
          class="next-usage__quota-row"
          data-testid="my-quota-row"
        >
          <div class="next-usage__quota-head">
            <span class="next-usage__quota-dim"
              >{{ quotaMetricText[rule.metric] }} · {{ quotaPeriodText[rule.period] }}</span
            >
            <UiStatusBadge
              variant="pill"
              :tone="quotaLevelTone(rule.level, rule.status)"
              :label="rule.status === 'DISABLED' ? '停用' : quotaLevelText[rule.level]"
            />
          </div>
          <div class="next-usage__quota-body">
            <span class="next-usage__quota-nums ui-num"
              >限额 {{ rule.limitValue.toLocaleString() }} · 本期用量
              {{ rule.used.toLocaleString() }}（{{ rule.usedPct }}%）</span
            >
            <div
              class="next-usage__quota-bar"
              role="progressbar"
              :aria-valuenow="Math.min(100, rule.usedPct)"
              aria-valuemin="0"
              aria-valuemax="100"
            >
              <div
                class="next-usage__quota-fill"
                :style="{
                  width: `${Math.min(100, rule.usedPct)}%`,
                  background: quotaBarFill(rule),
                }"
              />
            </div>
          </div>
        </div>
        <div v-if="quotaLoading" class="next-usage__quota-empty">正在加载配额规则…</div>
      </div>
    </section>

    <!-- Summary -->
    <section class="ui-panel next-usage__panel">
      <div class="ui-panel-head">
        <div class="next-usage__head-inline">
          <h2 class="ui-panel-title">用量汇总</h2>
        </div>
        <div class="next-usage__groupby">
          <span class="next-usage__groupby-label">分组维度</span>
          <UiSelect
            :model-value="groupBy"
            :options="groupByOptions"
            width="180px"
            data-testid="summary-groupby"
            @change="changeGroupBy"
          />
        </div>
      </div>
      <UiTable
        :columns="summaryColumns"
        :data="summary?.groups ?? []"
        :loading="summaryLoading"
        row-key="groupKey"
        empty-title="当前时间范围内没有用量记录"
        data-testid="summary-table"
      >
        <template #group="{ row }">{{ asGroup(row).label || asGroup(row).groupKey }}</template>
        <template #requests="{ row }">{{
          asGroup(row).requests.upstream +
          asGroup(row).requests.coalesced +
          asGroup(row).requests.l1Hit +
          asGroup(row).requests.l2Hit
        }}</template>
        <template #inputTokens="{ row }">{{ formatNumber(asGroup(row).tokens.input) }}</template>
        <template #outputTokens="{ row }">{{ formatNumber(asGroup(row).tokens.output) }}</template>
        <template #cacheRead="{ row }">{{ formatNumber(asGroup(row).tokens.cacheRead) }}</template>
        <template #upstreamCost="{ row }">{{
          formatCost(asGroup(row).cost.upstreamPaid)
        }}</template>
        <template #gatewayCost="{ row }">{{
          formatCost(asGroup(row).cost.gatewayObserved)
        }}</template>
      </UiTable>
      <div v-if="summary" class="next-usage__totals" data-testid="summary-totals">
        <span class="next-usage__totals-label">合计</span>
        <span class="ui-num next-usage__totals-col next-usage__totals-col--wide">{{
          formatNumber(
            summary.totals.requests.upstream +
              summary.totals.requests.coalesced +
              summary.totals.requests.l1Hit +
              summary.totals.requests.l2Hit,
          )
        }}</span>
        <span class="ui-num next-usage__totals-col">{{
          formatNumber(summary.totals.tokens.input)
        }}</span>
        <span class="ui-num next-usage__totals-col">{{
          formatNumber(summary.totals.tokens.output)
        }}</span>
        <span class="ui-num next-usage__totals-col">{{
          formatNumber(summary.totals.tokens.cacheRead)
        }}</span>
        <span class="ui-num next-usage__totals-col">{{
          formatCost(summary.totals.cost.upstreamPaid)
        }}</span>
        <span class="ui-num next-usage__totals-col">{{
          formatCost(summary.totals.cost.gatewayObserved)
        }}</span>
      </div>
    </section>

    <div class="next-usage__columns">
      <!-- Records -->
      <section class="ui-panel next-usage__records">
        <div class="ui-panel-head">
          <h2 class="ui-panel-title">最近记录</h2>
        </div>
        <UiTable
          :columns="recordsColumns"
          :data="records?.items ?? []"
          :loading="recordsLoading"
          row-key="gatewayRequestId"
          empty-title="没有用量记录"
          data-testid="records-table"
        >
          <template #occurredAt="{ row }">{{ formatTime(asRecord(row).occurredAt) }}</template>
          <template #modelId="{ row }">
            <span class="ui-mono">{{ asRecord(row).modelId }}</span>
          </template>
          <template #cacheLevel="{ row }">
            <UiStatusBadge
              :label="cacheLevelLabel[asRecord(row).cacheLevel] ?? asRecord(row).cacheLevel"
            />
          </template>
          <template #input="{ row }">{{ formatNumber(asRecord(row).inputTokens) }}</template>
          <template #output="{ row }">{{ formatNumber(asRecord(row).outputTokens) }}</template>
          <template #latency="{ row }">
            {{
              asRecord(row).latencyMs === null || asRecord(row).latencyMs === undefined
                ? '—'
                : `${asRecord(row).latencyMs}ms`
            }}
          </template>
          <template #upstreamStatus="{ row }">{{
            asRecord(row).upstreamStatusCode ?? '—'
          }}</template>
          <template #providerRequestId="{ row }">
            <span class="ui-mono">{{ asRecord(row).providerRequestId || '—' }}</span>
          </template>
        </UiTable>
        <div v-if="records && records.total > 0" class="next-usage__pager">
          <span class="next-usage__pager-total ui-num"
            >共 {{ records.total }} 条 · 第 {{ page }} / {{ totalPages }} 页</span
          >
          <div class="next-usage__pager-actions">
            <UiButton
              variant="secondary"
              :disabled="page <= 1"
              data-testid="records-prev"
              @click="gotoPage(page - 1)"
            >
              上一页
            </UiButton>
            <UiButton
              variant="secondary"
              :disabled="page >= totalPages"
              data-testid="records-next"
              @click="gotoPage(page + 1)"
            >
              下一页
            </UiButton>
          </div>
        </div>
      </section>

      <!-- Distribution -->
      <aside v-if="usageBars.length" class="ui-panel next-usage__aside" data-testid="usage-chart">
        <div class="ui-panel-head">
          <div>
            <h2 class="ui-panel-title">用量分布</h2>
            <span class="ui-panel-sub">Tokens 输入 + 输出 · Top 8</span>
          </div>
        </div>
        <div class="ui-panel-body next-usage__bars">
          <div v-for="bar in usageBars" :key="bar.label" class="next-usage__bar-row">
            <span class="next-usage__bar-label" :title="bar.label">{{ bar.label }}</span>
            <div class="next-usage__bar-track">
              <div class="next-usage__bar-fill" :style="{ width: bar.width, opacity: bar.alpha }" />
            </div>
            <span class="next-usage__bar-value ui-num">{{ bar.value }}</span>
          </div>
        </div>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.next-usage__panel {
  margin-bottom: var(--ui-space-5);
}

.next-usage__head-inline {
  display: flex;
  align-items: baseline;
  gap: var(--ui-space-3);
}

.next-usage__groupby {
  display: flex;
  align-items: center;
  gap: var(--ui-space-3);
}

.next-usage__groupby-label {
  font-size: var(--ui-font-size-sm);
  color: var(--ui-foreground-secondary);
}

.next-usage__quota-empty {
  font-size: var(--ui-font-size-sm);
  color: var(--ui-foreground-secondary);
  line-height: var(--ui-line-height-base);
}

.next-usage__quota-row {
  padding: var(--ui-space-3) 0;
  border-bottom: 1px solid var(--ui-border-muted);
}

.next-usage__quota-row:first-child {
  padding-top: 0;
}

.next-usage__quota-row:last-child {
  border-bottom: none;
}

.next-usage__quota-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ui-space-3);
}

.next-usage__quota-dim {
  font-size: var(--ui-font-size-sm);
  font-weight: var(--ui-weight-semibold);
}

.next-usage__quota-body {
  display: flex;
  align-items: center;
  gap: var(--ui-space-4);
  margin-top: var(--ui-space-2);
}

.next-usage__quota-nums {
  flex-shrink: 0;
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
  min-width: 320px;
}

.next-usage__quota-bar {
  flex: 1;
  height: 6px;
  border-radius: var(--ui-radius-pill);
  background: var(--ui-muted);
  overflow: hidden;
}

.next-usage__quota-fill {
  height: 100%;
  border-radius: var(--ui-radius-pill);
  transition: width 300ms ease;
}

.next-usage__totals {
  display: flex;
  align-items: center;
  gap: var(--ui-space-2);
  margin: 0 var(--ui-space-5);
  padding: var(--ui-space-3) var(--ui-space-4);
  margin-top: var(--ui-space-2);
  border-radius: var(--ui-radius-control);
  background: color-mix(in srgb, var(--ui-primary) 7%, white);
  font-size: var(--ui-font-size-sm);
  color: var(--ui-foreground-secondary);
}

.next-usage__totals-label {
  font-weight: var(--ui-weight-semibold);
  color: var(--ui-primary);
  width: 148px;
  flex-shrink: 0;
}

.next-usage__totals-col {
  width: 110px;
  text-align: right;
  flex-shrink: 0;
}

.next-usage__totals-col--wide {
  width: 100px;
}

.next-usage__columns {
  display: flex;
  gap: var(--ui-space-5);
  align-items: flex-start;
}

.next-usage__records {
  flex: 1;
  min-width: 0;
}

.next-usage__aside {
  width: 320px;
  flex-shrink: 0;
}

.next-usage__bars {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-3);
}

.next-usage__bar-row {
  display: grid;
  grid-template-columns: 110px 1fr 70px;
  align-items: center;
  gap: var(--ui-space-3);
  font-size: var(--ui-font-size-xs);
}

.next-usage__bar-label {
  color: var(--ui-foreground-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.next-usage__bar-track {
  height: 8px;
  border-radius: var(--ui-radius-pill);
  background: var(--ui-muted);
  overflow: hidden;
}

.next-usage__bar-fill {
  height: 100%;
  border-radius: var(--ui-radius-pill);
  background: var(--ui-primary);
}

.next-usage__bar-value {
  text-align: right;
  color: var(--ui-foreground);
}

.next-usage__pager {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--ui-space-3) var(--ui-space-5);
  border-top: 1px solid var(--ui-border-muted);
}

.next-usage__pager-total {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
}

.next-usage__pager-actions {
  display: flex;
  gap: var(--ui-space-2);
}
</style>
