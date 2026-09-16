<script setup lang="ts">
/**
 * NextUsageView — /app-new/usage pilot page (UI U0, PostHog language).
 * Behaviour parity with legacy UsageView: self-service quota panel (F04),
 * dimension-grouped summary + totals, top-8 token distribution bars and the
 * paged records table with CSV export. Rendering only — APIs untouched.
 */
import { computed, onMounted, ref } from 'vue';
import * as api from '@/api';
import { ChartBarIcon, LayersIcon, MoneyIcon } from 'tdesign-icons-vue-next';
import { ApiError } from '@/api/http';
import { csvCell } from '@/utils/csv';
import { UiButton, UiDonut, UiSelect, UiStatusBadge, UiTable, UiTrendChart, toast } from '@/ui';
import UsageCaliberTip from '@/components/UsageCaliberTip.vue';
import type { UiSelectOption } from '@/ui';
import type {
  QuotaMetric,
  QuotaPeriod,
  UsageGroupBy,
} from '@/types/api';
import type {
  QuotaRuleView,
  UsageGroup,
  UsageRecord,
  UsageRecordPage,
  UsageSummary,
  VirtualKeyView,
} from '@/types/generated-api';

const groupBy = ref<UsageGroupBy>('project');
const summary = ref<UsageSummary | null>(null);
const summaryLoading = ref(true);
const summaryError = ref('');

const records = ref<UsageRecordPage | null>(null);
const recordsLoading = ref(true);

// ---- daily trend (aggregated from the loaded records page) ----
type TrendMetric = 'tokens' | 'requests' | 'latency';

const TREND_TABS: Array<{ value: TrendMetric; label: string }> = [
  { value: 'tokens', label: 'Token' },
  { value: 'requests', label: '请求' },
  { value: 'latency', label: '平均延迟' },
];

const trendMetric = ref<TrendMetric>('tokens');


/** Vben analysis overview cards: value + right icon + label footer. */
const summaryStats = computed(() => {
  const t = summary.value?.totals;
  const tokens = (t?.tokens?.input ?? 0) + (t?.tokens?.output ?? 0);
  const requests = t?.requests?.upstream ?? 0;
  const cost = Number(t?.cost?.upstreamPaid ?? 0);
  return [
    { label: 'Token 总量', value: formatNumber(tokens), icon: LayersIcon, tone: 'cyan' },
    { label: '请求数', value: formatNumber(requests), icon: ChartBarIcon, tone: 'green' },
    { label: '上游成本', value: `¥${cost.toFixed(2)}`, icon: MoneyIcon, tone: 'gold' },
  ];
});

const trendPoints = computed(() => {
  const items = records.value?.items ?? [];
  const byDay = new Map<string, { sum: number; count: number }>();
  for (const r of items) {
    const day = String(r.occurredAt ?? '').slice(0, 10);
    if (!day) continue;
    const entry = byDay.get(day) ?? { sum: 0, count: 0 };
    if (trendMetric.value === 'tokens') {
      entry.sum += r.totalTokens ?? (r.inputTokens ?? 0) + (r.outputTokens ?? 0);
    } else if (trendMetric.value === 'requests') {
      entry.sum += 1;
    } else {
      entry.sum += r.latencyMs ?? 0;
    }
    entry.count += 1;
    byDay.set(day, entry);
  }
  return [...byDay.entries()]
    .sort((a, b) => a[0].localeCompare(b[0]))
    .slice(-14)
    .map(([day, { sum, count }]) => ({
      label: day.slice(5),
      value: trendMetric.value === 'latency' && count > 0 ? Math.round(sum / count) : sum,
    }));
});

const recordsError = ref('');
const page = ref(1);
const pageSize = ref(20);

// ---- time range presets (server default when 0 — behaviour unchanged) ----
const rangeDays = ref<number>(0);
const windowOptions = [
  { value: 0, label: '全部时间' },
  { value: 7, label: '近 7 天' },
  { value: 30, label: '近 30 天' },
  { value: 93, label: '近 93 天' },
];

// #643: custom window (datetime-local inputs, inclusive range ≤ server's 93d cap).
const customOpen = ref(false);
const customFrom = ref('');
const customTo = ref('');
const customActive = ref(false);

function windowFromTo(): { from?: string; to?: string } {
  if (customActive.value && customFrom.value && customTo.value) {
    return {
      from: new Date(customFrom.value).toISOString(),
      to: new Date(customTo.value).toISOString(),
    };
  }
  if (!rangeDays.value) return {};
  const to = new Date();
  return {
    from: new Date(to.getTime() - rangeDays.value * 24 * 3600 * 1000).toISOString(),
    to: to.toISOString(),
  };
}

function applyRange(value: number) {
  rangeDays.value = value;
  customActive.value = false; // a preset wins; the custom window is parked
  page.value = 1;
  void loadSummary();
  void loadRecords();
}

function applyCustomRange() {
  if (!customFrom.value || !customTo.value) {
    toast.info('请选择开始与结束时间。');
    return;
  }
  const from = new Date(customFrom.value);
  const to = new Date(customTo.value);
  if (!(from.getTime() < to.getTime())) {
    toast.info('开始时间必须早于结束时间。');
    return;
  }
  customActive.value = true;
  rangeDays.value = -1; // nothing in the preset strip stays highlighted
  page.value = 1;
  void loadSummary();
  void loadRecords();
}

// ---- self-service quota visibility (F04) ----

const myQuotaRules = ref<QuotaRuleView[]>([]);
const quotaLoading = ref(true);

const quotaMetricText: Record<QuotaMetric, string> = { TOKENS: 'Token 用量', REQUESTS: '请求次数' };
const quotaPeriodText: Record<QuotaPeriod, string> = {
  DAILY: '每日',
  WEEKLY: '每周',
  MONTHLY: '每月',
};
// hub schema types level as a plain string, so keep the label map string-keyed
const quotaLevelText: Record<string, string> = {
  NORMAL: '正常',
  WARNING: '预警',
  EXCEEDED: '超限',
};

function quotaLevelTone(
  level: QuotaRuleView['level'],
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
  { key: 'modelId', title: '模型', minWidth: '170px' },
  { key: 'virtualKey', title: '密钥', minWidth: '150px' },
  { key: 'cacheLevel', title: '级别', width: '100px' },
  { key: 'input', title: '输入', width: '90px', align: 'right' as const },
  { key: 'output', title: '输出', width: '90px', align: 'right' as const },
  { key: 'cacheRead', title: '缓存读', width: '110px', align: 'right' as const },
  { key: 'latency', title: '延迟', width: '85px', align: 'right' as const },
  { key: 'upstreamStatus', title: '上游状态', width: '95px', align: 'right' as const },
  { key: 'clientIp', title: '来源 IP', width: '140px' },
  { key: 'providerRequestId', title: '供应商请求 ID', minWidth: '210px' },
];

// #643: record rows resolve their virtual key by name for at-a-glance auditing.
const myKeys = ref<VirtualKeyView[]>([]);
const keyName = computed(() => {
  const byId = new Map(myKeys.value.map((k) => [k.id ?? '', k.name ?? k.id ?? '']));
  return (id?: string) => (id ? (byId.get(id) ?? `${id.slice(0, 8)}…`) : '—');
});

async function loadKeys() {
  try {
    myKeys.value = await api.listVirtualKeys();
  } catch {
    // the column degrades to short ids — never blocks the page
  }
}

// #643: page-jump input beside prev/next.
const pageInput = ref('');

function jumpToPage() {
  const wanted = Number.parseInt(pageInput.value, 10);
  if (Number.isNaN(wanted)) {
    pageInput.value = '';
    return;
  }
  pageInput.value = '';
  gotoPage(Math.min(Math.max(wanted, 1), totalPages.value));
}

const groupByOptions: UiSelectOption[] = [
  { value: 'project', label: '项目' },
  { value: 'virtual_key', label: '虚拟密钥' },
  { value: 'cache_level', label: '缓存级别' },
  { value: 'day', label: '日期' },
];

const cacheLevelLabel: Record<string, string> = {
  UPSTREAM: '上游',
  COALESCED: '合并',
  L1_HIT: 'L1 命中',
  L2_HIT: 'L2 命中',
};

const PALETTE = ['#0960bd', '#69c0ff', '#13c2c2', '#fa8c16', '#8c8c8c', '#d9d9d9'];

const compositionSegments = computed(() => {
  const ranked = (summary.value?.groups ?? [])
    .map((g) => ({
      label: g.label,
      value: (g.tokens?.input ?? 0) + (g.tokens?.output ?? 0),
    }))
    .filter((g) => g.value > 0)
    .sort((a, b) => b.value - a.value);
  const top = ranked.slice(0, 5);
  const restValue = ranked.slice(5).reduce((sum, g) => sum + g.value, 0);
  const rows: { label: string; value: number; color: string; pct: number }[] = top.map((g, i) => ({
    label: g.label ?? '—',
    value: g.value,
    color: PALETTE[i]!,
    pct: 0,
  }));
  if (restValue > 0) {
    rows.push({ label: '其他', value: restValue, color: PALETTE[5]!, pct: 0 });
  }
  const total = rows.reduce((sum, r) => sum + r.value, 0) || 1;
  return { rows: rows.map((r) => ({ ...r, pct: (r.value / total) * 100 })), total };
});

const usageTotalTokens = computed(() => compositionSegments.value.total);

const totalPages = computed(() => {
  if (!records.value || records.value.total === 0) return 1;
  return Math.ceil((records.value.total ?? 0) / pageSize.value);
});

onMounted(() => {
  void loadSummary();
  void loadRecords();
  void loadQuota();
  void loadKeys();
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

// #440: per-endpoint request-sequence guards — rapid range/group/page changes
// must not let an older response land after a newer one.
let summaryRequestSeq = 0;
let recordsRequestSeq = 0;

async function loadSummary() {
  const seq = ++summaryRequestSeq;
  summaryLoading.value = true;
  summaryError.value = '';
  try {
    const w = windowFromTo();
    const result =
      w.from && w.to
        ? await api.usageSummary(groupBy.value, w.from, w.to)
        : await api.usageSummary(groupBy.value);
    if (seq !== summaryRequestSeq) {
      return; // a newer request won — this response is stale
    }
    summary.value = result;
  } catch (error) {
    if (seq === summaryRequestSeq) {
      if (error instanceof ApiError) {
        summaryError.value = `${error.message}（requestId: ${error.requestId ?? '-'}）`;
      } else {
        summaryError.value = '加载用量汇总失败。';
      }
    }
  } finally {
    if (seq === summaryRequestSeq) {
      summaryLoading.value = false;
    }
  }
}

async function loadRecords() {
  const seq = ++recordsRequestSeq;
  recordsLoading.value = true;
  recordsError.value = '';
  try {
    const result = await api.usageRecords({
      page: page.value,
      size: pageSize.value,
      ...windowFromTo(),
    });
    if (seq !== recordsRequestSeq) {
      return; // a newer request won — this response is stale
    }
    records.value = result;
  } catch (error) {
    if (seq === recordsRequestSeq) {
      if (error instanceof ApiError) {
        recordsError.value = `${error.message}（requestId: ${error.requestId ?? '-'}）`;
      } else {
        recordsError.value = '加载用量明细失败。';
      }
    }
  } finally {
    if (seq === recordsRequestSeq) {
      recordsLoading.value = false;
    }
  }
}

function changeGroupBy(value: string) {
  groupBy.value = value as UsageGroupBy;
  void loadSummary();
}

/** Exports every record of the current filter (all pages) as CSV. */
async function exportRecords() {
  const size = 200; // records API upper bound
  const all: UsageRecord[] = [];
  let pageNo = 1;
  try {
    for (;;) {
      const batch = await api.usageRecords({ page: pageNo, size, ...windowFromTo() });
      all.push(...(batch.items ?? []));
      if (pageNo * size >= (batch.total ?? 0)) break;
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
    '密钥',
    '级别',
    '输入 Token',
    '输出 Token',
    '缓存读 Token',
    '延迟(ms)',
    '上游状态',
    '来源 IP',
    '供应商请求 ID',
  ];
  const rows = all.map((r) => [
    r.occurredAt,
    r.modelId ?? '',
    keyName.value(r.virtualKeyId),
    cacheLevelLabel[r.cacheLevel ?? ''] ?? r.cacheLevel,
    String(r.inputTokens ?? ''),
    String(r.outputTokens ?? ''),
    String(r.cacheReadInputTokens ?? ''),
    String(r.latencyMs ?? ''),
    String(r.upstreamStatusCode ?? ''),
    r.clientIp ?? '',
    r.providerRequestId ?? '',
  ]);
  const csv = [header, ...rows]
    .map((row) => row.map(csvCell).join(','))
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

function formatCost(value?: string | number): string {
  if (value === undefined || value === null) return '—';
  const num = Number(value);
  if (Number.isNaN(num)) return String(value);
  return `$${num.toFixed(4)}`;
}

function formatNumber(value?: number): string {
  return value === undefined || value === null ? '—' : value.toLocaleString();
}

function formatTime(iso?: string): string {
  if (!iso) return '—';
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

    <UsageCaliberTip />

    <!-- Self-service quota visibility (F04) -->
    <section class="next-usage__stats" data-testid="usage-stats">
      <div v-for="item in summaryStats" :key="item.label" class="ui-panel next-usage__stat">
        <div class="next-usage__stat-main">
          <span class="next-usage__stat-value ui-num">{{ item.value }}</span>
          <span
            class="next-usage__stat-icon"
            :class="`next-usage__tone--${item.tone}`"
            aria-hidden="true"
          >
            <component :is="item.icon" size="22px" />
          </span>
        </div>
        <span class="next-usage__stat-label">{{ item.label }}</span>
      </div>
    </section>

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
              >{{ quotaMetricText[rule.metric!] }} · {{ quotaPeriodText[rule.period!] }}</span
            >
            <UiStatusBadge
              variant="pill"
              :tone="quotaLevelTone(rule.level, rule.status)"
              :label="rule.status === 'DISABLED' ? '停用' : quotaLevelText[rule.level!]"
            />
          </div>
          <div class="next-usage__quota-body">
            <span class="next-usage__quota-nums ui-num"
              >限额 {{ formatNumber(rule.limitValue) }} · 本期用量
              {{ formatNumber(rule.used) }}（{{ rule.usedPct }}%）</span
            >
            <div
              class="next-usage__quota-bar"
              role="progressbar"
              :aria-valuenow="Math.min(100, rule.usedPct ?? 0)"
              aria-valuemin="0"
              aria-valuemax="100"
            >
              <div
                class="next-usage__quota-fill"
                :style="{
                  width: `${Math.min(100, rule.usedPct ?? 0)}%`,
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

    <section class="ui-panel next-usage__trend" data-testid="usage-trend">
      <div class="ui-panel-head">
        <h2 class="ui-panel-title">用量趋势</h2>
        <div class="next-usage__trend-tabs" role="tablist" aria-label="趋势指标">
          <button
            v-for="tab in TREND_TABS"
            :key="tab.value"
            type="button"
            role="tab"
            class="next-usage__trend-tab"
            :class="{ 'next-usage__trend-tab--on': trendMetric === tab.value }"
            :aria-selected="trendMetric === tab.value"
            :data-testid="`trend-tab-${tab.value}`"
            @click="trendMetric = tab.value"
          >
            {{ tab.label }}
          </button>
        </div>
      </div>
      <div class="ui-panel-body">
        <UiTrendChart
          :points="trendPoints"
          :value-formatter="formatNumber"
          data-testid="usage-trend-chart"
        />
      </div>
    </section>

    <section
      class="ui-panel next-usage__panel"
      :class="{ 'next-usage__busy': summaryLoading && summary }"
    >
      <div class="ui-panel-head">
        <div class="next-usage__head-inline">
          <h2 class="ui-panel-title">用量汇总</h2>
        </div>
        <div class="next-usage__controls">
          <div class="next-usage__range" aria-label="时间范围">
            <button
              v-for="w in windowOptions"
              :key="w.value"
              type="button"
              class="next-usage__seg"
              :class="{ 'next-usage__seg--on': rangeDays === w.value }"
              :data-testid="`usage-range-${w.value}`"
              @click="applyRange(w.value)"
            >
              {{ w.label }}
            </button>
            <button
              type="button"
              class="next-usage__seg"
              :class="{ 'next-usage__seg--on': customActive }"
              data-testid="usage-range-custom"
              @click="customOpen = !customOpen"
            >
              自定义
            </button>
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
      </div>
      <div v-if="customOpen" class="next-usage__custom-range" data-testid="usage-custom-range">
        <input
          v-model="customFrom"
          type="datetime-local"
          class="next-usage__datetime"
          data-testid="usage-custom-from"
        />
        <span class="next-usage__custom-sep">至</span>
        <input
          v-model="customTo"
          type="datetime-local"
          class="next-usage__datetime"
          data-testid="usage-custom-to"
        />
        <UiButton
          variant="primary"
          size="sm"
          data-testid="usage-custom-apply"
          @click="applyCustomRange"
        >
          应用
        </UiButton>
        <span class="next-usage__custom-hint">最长 93 天</span>
      </div>
      <UiTable
        :columns="summaryColumns"
        :data="summary?.groups ?? []"
        :loading="summaryLoading && !summary"
        row-key="groupKey"
        empty-title="当前时间范围内没有用量记录"
        data-testid="summary-table"
      >
        <template #group="{ row }">{{ asGroup(row).label || asGroup(row).groupKey }}</template>
        <template #requests="{ row }">{{
          (asGroup(row).requests?.upstream ?? 0) +
          (asGroup(row).requests?.coalesced ?? 0) +
          (asGroup(row).requests?.l1Hit ?? 0) +
          (asGroup(row).requests?.l2Hit ?? 0)
        }}</template>
        <template #inputTokens="{ row }">{{ formatNumber(asGroup(row).tokens?.input ?? 0) }}</template>
        <template #outputTokens="{ row }">{{ formatNumber(asGroup(row).tokens?.output ?? 0) }}</template>
        <template #cacheRead="{ row }">{{ formatNumber(asGroup(row).tokens?.cacheRead ?? 0) }}</template>
        <template #upstreamCost="{ row }">{{
          formatCost(asGroup(row).cost?.upstreamPaid)
        }}</template>
        <template #gatewayCost="{ row }">{{
          formatCost(asGroup(row).cost?.gatewayObserved)
        }}</template>
      </UiTable>
      <div
        v-if="summary && (summary.groups?.length ?? 0) > 0"
        class="next-usage__totals"
        data-testid="summary-totals"
      >
        <span class="next-usage__totals-label">合计</span>
        <span class="ui-num next-usage__totals-col next-usage__totals-col--wide">{{
          formatNumber(
            (summary.totals?.requests?.upstream ?? 0) +
              (summary.totals?.requests?.coalesced ?? 0) +
              (summary.totals?.requests?.l1Hit ?? 0) +
              (summary.totals?.requests?.l2Hit ?? 0),
          )
        }}</span>
        <span class="ui-num next-usage__totals-col">{{
          formatNumber(summary.totals?.tokens?.input)
        }}</span>
        <span class="ui-num next-usage__totals-col">{{
          formatNumber(summary.totals?.tokens?.output)
        }}</span>
        <span class="ui-num next-usage__totals-col">{{
          formatNumber(summary.totals?.tokens?.cacheRead)
        }}</span>
        <span class="ui-num next-usage__totals-col">{{
          formatCost(summary.totals?.cost?.upstreamPaid)
        }}</span>
        <span class="ui-num next-usage__totals-col">{{
          formatCost(summary.totals?.cost?.gatewayObserved)
        }}</span>
      </div>
    </section>

    <div class="next-usage__columns">
      <!-- Records -->
      <section
        class="ui-panel next-usage__records"
        :class="{ 'next-usage__busy': recordsLoading && records }"
      >
        <div class="ui-panel-head">
          <h2 class="ui-panel-title">最近记录</h2>
        </div>
        <UiTable
          :columns="recordsColumns"
          :data="records?.items ?? []"
          :loading="recordsLoading && !records"
          row-key="gatewayRequestId"
          empty-title="没有用量记录"
          data-testid="records-table"
        >
          <template #occurredAt="{ row }">{{ formatTime(asRecord(row).occurredAt) }}</template>
          <template #modelId="{ row }">
            <span class="ui-mono">{{ asRecord(row).modelId }}</span>
          </template>
          <template #virtualKey="{ row }">
            <span class="next-usage__keyname">{{ keyName(asRecord(row).virtualKeyId) }}</span>
          </template>
          <template #cacheLevel="{ row }">
            <UiStatusBadge
              :label="cacheLevelLabel[asRecord(row).cacheLevel!] ?? asRecord(row).cacheLevel"
            />
          </template>
          <template #input="{ row }">{{ formatNumber(asRecord(row).inputTokens) }}</template>
          <template #output="{ row }">{{ formatNumber(asRecord(row).outputTokens) }}</template>
          <template #cacheRead="{ row }">{{
            formatNumber(asRecord(row).cacheReadInputTokens)
          }}</template>
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
          <template #clientIp="{ row }">
            <span class="ui-mono">{{ asRecord(row).clientIp || '—' }}</span>
          </template>
          <template #providerRequestId="{ row }">
            <span class="ui-mono">{{ asRecord(row).providerRequestId || '—' }}</span>
          </template>
        </UiTable>
        <div v-if="records && (records.total ?? 0) > 0" class="next-usage__pager">
          <span class="next-usage__pager-total ui-num"
            >共 {{ records.total }} 条 · 第 {{ page }} / {{ totalPages }} 页<span
              v-if="recordsLoading"
              class="next-usage__busy-hint"
              data-testid="records-busy"
            >
              更新中…</span
            ></span
          >
          <div class="next-usage__pager-actions">
            <div class="next-usage__pager-jump">
              <span>跳至</span>
              <input
                v-model="pageInput"
                class="next-usage__page-input"
                type="text"
                inputmode="numeric"
                :placeholder="String(page)"
                data-testid="records-page-input"
                @keydown.enter="jumpToPage"
              />
              <span>页</span>
              <UiButton
                variant="secondary"
                size="sm"
                data-testid="records-page-go"
                @click="jumpToPage"
              >
                跳转
              </UiButton>
            </div>
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
      <aside v-if="compositionSegments.rows.length" class="ui-panel next-usage__aside" data-testid="usage-chart">
        <div class="ui-panel-head">
          <div>
            <h2 class="ui-panel-title">用量分布</h2>
            <span class="ui-panel-sub">Tokens 输入 + 输出 · Top 8</span>
          </div>
        </div>
        <div class="ui-panel-body next-usage__composition">
          <UiDonut
            :segments="
              compositionSegments.rows.map((r) => ({ label: r.label, value: r.value, color: r.color }))
            "
            :center-text="formatNumber(usageTotalTokens)"
            data-testid="usage-composition-donut"
          />
          <div class="ui-legend">
            <div
              v-for="seg in compositionSegments.rows"
              :key="seg.label"
              class="ui-legend-row"
            >
              <span class="ui-legend-dot" :style="{ background: seg.color }" />
              <span class="ui-legend-label" :title="seg.label">{{ seg.label }}</span>
              <span class="ui-legend-pct ui-num">{{ seg.pct.toFixed(0) }}%</span>
              <span class="ui-legend-value ui-num">{{ formatNumber(seg.value) }}</span>
            </div>
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

.next-usage__controls {
  display: flex;
  align-items: center;
  gap: var(--ui-space-5);
  flex-wrap: wrap;
}

.next-usage__range {
  display: inline-flex;
  gap: 2px;
  padding: 2px;
  background: var(--ui-muted);
  border: 1px solid var(--ui-border-muted);
  border-radius: var(--ui-radius-control);
}

.next-usage__seg {
  height: 28px;
  padding: 0 var(--ui-space-3);
  border: 0;
  border-radius: calc(var(--ui-radius-control) - 2px);
  background: transparent;
  color: var(--ui-foreground-secondary);
  font-size: var(--ui-font-size-xs);
  font-weight: var(--ui-weight-medium);
  cursor: pointer;
  transition:
    color var(--ui-ease),
    background-color var(--ui-ease);
}

.next-usage__seg:hover {
  color: var(--ui-foreground);
}

.next-usage__seg--on {
  background: var(--ui-card);
  border: 1px solid var(--ui-border);
  color: var(--ui-primary-text);
  font-weight: var(--ui-weight-semibold);
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
  color: var(--ui-primary-text);
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

.next-usage__composition {
  display: flex;
  align-items: center;
  gap: var(--ui-space-6);
  flex-wrap: wrap;
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
  align-items: center;
  gap: var(--ui-space-2);
}

/* #643: page/range changes keep the old data visible with a soft busy dim
   instead of swapping in skeleton rows (the height jump made the whole
   column flicker). */
.next-usage__busy {
  opacity: 0.65;
  transition: opacity var(--ui-ease);
}

.next-usage__busy-hint {
  margin-left: var(--ui-space-2);
  color: var(--ui-foreground-faint);
}

.next-usage__keyname {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
}

.next-usage__custom-range {
  display: flex;
  align-items: center;
  gap: var(--ui-space-2);
  padding: var(--ui-space-2) var(--ui-space-5);
  border-bottom: 1px solid var(--ui-border-muted);
}

.next-usage__custom-sep,
.next-usage__custom-hint {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
}

.next-usage__datetime {
  height: var(--ui-control-height);
  padding: 0 var(--ui-space-2);
  border: 1px solid var(--ui-input-border);
  border-radius: var(--ui-radius-control);
  background: var(--ui-card);
  color: var(--ui-foreground);
  font-family: inherit;
  font-size: var(--ui-font-size-sm);
}

.next-usage__datetime:focus {
  outline: none;
  border-color: var(--ui-primary);
  box-shadow: var(--ui-shadow-focus);
}

.next-usage__pager-jump {
  display: flex;
  align-items: center;
  gap: var(--ui-space-1);
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
}

.next-usage__page-input {
  width: 56px;
  height: 26px;
  padding: 0 var(--ui-space-1);
  border: 1px solid var(--ui-input-border);
  border-radius: var(--ui-radius-control);
  background: var(--ui-card);
  color: var(--ui-foreground);
  font-family: inherit;
  font-size: var(--ui-font-size-xs);
  text-align: center;
}

.next-usage__page-input:focus {
  outline: none;
  border-color: var(--ui-primary);
  box-shadow: var(--ui-shadow-focus);
}

/* usage micro-polish (issue #261): empty summaries skip the misleading
   totals band; records list keeps a touch of bottom air. */
.next-usage__records {
  padding-bottom: var(--ui-space-2);
}

.next-usage__trend {
  margin-bottom: var(--ui-space-5);
}

.next-usage__trend-tabs {
  display: inline-flex;
  gap: 2px;
  padding: 2px;
  border-radius: var(--ui-radius-control);
  background: var(--ui-muted);
}

.next-usage__trend-tab {
  border: 0;
  padding: 0 12px;
  height: 24px;
  border-radius: 4px;
  background: transparent;
  color: var(--ui-foreground-secondary);
  font-family: inherit;
  font-size: var(--ui-font-size-xs);
  cursor: pointer;
}

.next-usage__trend-tab--on {
  background: var(--ui-card);
  color: var(--ui-primary-text);
  box-shadow: var(--ui-shadow-card);
}

/* ---- analysis overview cards (Vben: value + icon, label under) ---- */
.next-usage__stats {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--ui-space-4);
  margin-bottom: var(--ui-space-5);
}

@media (max-width: 900px) {
  .next-usage__stats {
    grid-template-columns: 1fr;
  }
}

.next-usage__stat {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-2);
  padding: var(--ui-space-4) var(--ui-space-5);
}

.next-usage__stat-main {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ui-space-3);
}

.next-usage__stat-value {
  font-size: 24px;
  font-weight: var(--ui-weight-semibold);
  color: var(--ui-foreground);
  line-height: 30px;
}

.next-usage__stat-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border-radius: 10px;
  flex-shrink: 0;
}

.next-usage__tone--cyan {
  background: #e0f4f6;
  color: #0e7490;
}

.next-usage__tone--green {
  background: var(--ui-success-bg);
  color: var(--ui-success-fg);
}

.next-usage__tone--gold {
  background: #fdf3e0;
  color: #a16207;
}

.next-usage__stat-label {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
}
</style>
