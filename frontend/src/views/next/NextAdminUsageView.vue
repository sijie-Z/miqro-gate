<script setup lang="ts">
/**
 * NextAdminUsageView — /app/admin-usage 管理端「用量与成本总览」(#681)。
 *
 * cc-switch 式总览 + 腾讯成本分析式分解：
 * - KPI 卡带：请求 / 输入 / 输出 / 缓存读（命中率）/ 缓存节省 / 总成本；
 * - 服务端时间趋势：Token 与成本双序列（按日/按月，不再用记录页拼数据）；
 * - 维度分解表：团队 / 个人 / 项目 / 模型 / 虚拟密钥，含成本占比，行点击下钻；
 * - 每小时 Token 表（#634）与明细记录沿用。
 */
import { computed, onMounted, ref } from 'vue';
import * as api from '@/api';
import { ChartBarIcon, DownloadIcon, MoneyIcon, UploadIcon } from 'tdesign-icons-vue-next';
import { ApiError } from '@/api/http';
import UsageCaliberTip from '@/components/UsageCaliberTip.vue';
import { UiButton, UiInput, UiSelect, UiStatusBadge, UiTable, UiTrendChart } from '@/ui';
import type { UiSelectOption, UiTrendSeries } from '@/ui';
import type { UsageGroupBy } from '@/types/api';
import type {
  AdminUser,
  Project,
  Team,
  UsageGroup,
  UsageRecord,
  UsageRecordPage,
  UsageSummary,
  HourlyUsageReport,
  HourlyUsageRow,
} from '@/types/generated-api';

const groupBy = ref<UsageGroupBy>('project');
const modelId = ref('');
const projectId = ref('');
const teamId = ref('');
const userId = ref('');
// #605: abuse forensics — filter the whole report down to one calling address.
const clientIp = ref('');
const summary = ref<UsageSummary | null>(null);
const summaryLoading = ref(true);
const summaryError = ref('');
const summaryRequestId = ref('');

const records = ref<UsageRecordPage | null>(null);
const recordsLoading = ref(true);

// ---- pickers: teams / users / projects (loaded once, non-blocking) ----
const teams = ref<Team[]>([]);
const users = ref<AdminUser[]>([]);
const projects = ref<Project[]>([]);

const teamOptions = computed<UiSelectOption[]>(() => [
  { value: '', label: '全部团队' },
  ...teams.value.map((t) => ({ value: t.id ?? '', label: t.name ?? t.id ?? '' })),
]);
const userOptions = computed<UiSelectOption[]>(() => [
  { value: '', label: '全部用户' },
  ...users.value.map((u) => ({ value: u.id ?? '', label: u.username ?? u.id ?? '' })),
]);
const projectOptions = computed<UiSelectOption[]>(() => [
  { value: '', label: '全部项目' },
  ...projects.value.map((p) => ({ value: p.id ?? '', label: p.name ?? p.code ?? p.id ?? '' })),
]);

async function loadPickers() {
  try {
    const [teamList, userList, projectList] = await Promise.all([
      api.listTeams(),
      api.listUsers(),
      api.listProjects(),
    ]);
    teams.value = teamList;
    users.value = userList;
    projects.value = projectList;
  } catch {
    // pickers degrade to "全部" — the report itself still works
  }
}

// ---- server time series for the trend chart (Token + cost, day|month) ----
const seriesDim = ref<'day' | 'month'>('day');
const series = ref<UsageSummary | null>(null);

const seriesOptions: Array<{ value: 'day' | 'month'; label: string }> = [
  { value: 'day', label: '按日' },
  { value: 'month', label: '按月' },
];

function tokenTotal(g: UsageGroup | undefined): number {
  const t = g?.tokens;
  return (t?.input ?? 0) + (t?.output ?? 0) + (t?.cacheRead ?? 0) + (t?.cacheCreation ?? 0);
}

const trendSeries = computed<UiTrendSeries[]>(() => {
  const groups = [...(series.value?.groups ?? [])].sort((a, b) =>
    String(a.groupKey ?? '').localeCompare(String(b.groupKey ?? '')),
  );
  const label = (key?: string) =>
    seriesDim.value === 'month' ? String(key ?? '') : String(key ?? '').slice(5);
  return [
    {
      name: 'Token',
      color: 'var(--ui-primary)',
      kind: 'area',
      points: groups.map((g) => ({ label: label(g.groupKey), value: tokenTotal(g) })),
    },
    {
      name: '成本 ¥',
      color: '#fa8c16',
      kind: 'line',
      points: groups.map((g) => ({
        label: label(g.groupKey),
        value: Number(g.cost?.upstreamPaid ?? 0),
      })),
    },
  ];
});

// ---- KPI cards from summary.totals ----
const totals = computed(() => summary.value?.totals);
const upstreamRequests = computed(() => Number(totals.value?.requests?.upstream ?? 0));
const coalescedRequests = computed(() => Number(totals.value?.requests?.coalesced ?? 0));
const hitRequests = computed(
  () => Number(totals.value?.requests?.l1Hit ?? 0) + Number(totals.value?.requests?.l2Hit ?? 0),
);
const hitRatePct = computed(() => {
  const denom = upstreamRequests.value + coalescedRequests.value + hitRequests.value;
  return denom > 0 ? ((hitRequests.value / denom) * 100).toFixed(1) + '%' : '—';
});

// ---- dimension breakdown (summary.groups) ----
const GROUP_LABELS: Record<string, string> = {
  project: '项目',
  user: '用户',
  team: '团队',
  model: '模型',
  virtual_key: '虚拟密钥',
  cache_level: '缓存层级',
  day: '日',
  month: '月',
};

interface BreakdownRow {
  key: string;
  label: string;
  requests: number;
  tokens: number;
  cost: number;
  share: number;
}

const breakdownRows = computed<BreakdownRow[]>(() => {
  const groups = summary.value?.groups ?? [];
  const totalCost = Number(summary.value?.totals?.cost?.upstreamPaid ?? 0);
  const totalTokens = tokenTotal(summary.value?.totals);
  const useCost = totalCost > 0;
  const shareBase = useCost ? totalCost : totalTokens;
  return groups
    .map((g) => {
      const cost = Number(g.cost?.upstreamPaid ?? 0);
      const tokens = tokenTotal(g);
      return {
        key: String(g.groupKey ?? g.label ?? ''),
        label: g.label ?? String(g.groupKey ?? '—'),
        requests: Number(g.requests?.upstream ?? 0),
        tokens,
        cost,
        share: shareBase > 0 ? ((useCost ? cost : tokens) / shareBase) * 100 : 0,
      };
    })
    .sort((a, b) => b.cost - a.cost || b.tokens - a.tokens);
});

const breakdownColumns = computed(() => [
  { key: 'label', title: GROUP_LABELS[groupBy.value] ?? '分组', minWidth: '180px' },
  { key: 'requests', title: '请求', width: '110px', align: 'right' as const, sortable: true },
  { key: 'tokens', title: 'Token', width: '130px', align: 'right' as const, sortable: true },
  { key: 'cost', title: '成本 ¥', width: '130px', align: 'right' as const, sortable: true },
  { key: 'share', title: '占比', width: '170px' },
]);

const drillable = computed(() => ['team', 'user', 'project', 'model'].includes(groupBy.value));

function onBreakdownRow(row: unknown) {
  const key = (row as unknown as BreakdownRow).key;
  if (!key) return;
  switch (groupBy.value) {
    case 'team':
      teamId.value = key;
      break;
    case 'user':
      userId.value = key;
      break;
    case 'project':
      projectId.value = key;
      break;
    case 'model':
      modelId.value = key;
      break;
    default:
      return;
  }
  page.value = 1;
  void load();
  void loadHourly();
}

// ---- active drill chips ----
const drillChips = computed(() => {
  const chips: Array<{ kind: string; label: string }> = [];
  if (teamId.value) {
    const t = teamOptions.value.find((o) => o.value === teamId.value);
    chips.push({ kind: '团队', label: t?.label ?? teamId.value });
  }
  if (userId.value) {
    const u = userOptions.value.find((o) => o.value === userId.value);
    chips.push({ kind: '用户', label: u?.label ?? userId.value });
  }
  if (projectId.value) {
    const p = projectOptions.value.find((o) => o.value === projectId.value);
    chips.push({ kind: '项目', label: p?.label ?? projectId.value });
  }
  if (modelId.value.trim()) {
    chips.push({ kind: '模型', label: modelId.value.trim() });
  }
  return chips;
});

function clearDrill(kind: string) {
  if (kind === '团队') teamId.value = '';
  else if (kind === '用户') userId.value = '';
  else if (kind === '项目') projectId.value = '';
  else if (kind === '模型') modelId.value = '';
  page.value = 1;
  void load();
  void loadHourly();
}

function clearAllDrill() {
  teamId.value = '';
  userId.value = '';
  projectId.value = '';
  modelId.value = '';
  page.value = 1;
  void load();
  void loadHourly();
}

// ---- CSV export of the breakdown table ----
function exportBreakdownCsv() {
  const rows = breakdownRows.value;
  if (!rows.length) return;
  const header = '分组,请求,Token,成本(CNY),占比(%)';
  const body = rows.map((r) =>
    [r.label, r.requests, r.tokens, r.cost.toFixed(4), r.share.toFixed(2)].join(','),
  );
  const csv = '﻿' + [header, ...body].join('\r\n');
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = `usage-${groupBy.value}-${new Date().toISOString().slice(0, 10)}.csv`;
  link.click();
  URL.revokeObjectURL(url);
}

const page = ref(1);
const pageSize = ref(20);

// ---- hourly token table (#634): per-hour buckets crossed with project x user/team ----
const hourlyDate = ref<string>(todayLocalIso());
const hourlyDays = ref<number>(1);
const hourlyDimension = ref<string>('USER');
const hourly = ref<HourlyUsageReport | null>(null);
const hourlyLoading = ref(true);
const hourlyError = ref('');

const hourlyDayOptions = [
  { value: 1, label: '当天' },
  { value: 3, label: '近 3 天' },
  { value: 7, label: '近 7 天' },
];

const hourlyDimensionOptions: UiSelectOption[] = [
  { value: 'NONE', label: '不分组（仅项目）' },
  { value: 'USER', label: '按用户' },
  { value: 'TEAM', label: '按团队' },
];

function todayLocalIso(): string {
  const d = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

function formatHour(iso?: string): string {
  if (!iso) return '—';
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:00`;
}

const hourlyRows = computed(() =>
  (hourly.value?.rows ?? []).map((r, i) => ({
    ...r,
    key: `${r.hourStart ?? ''}-${r.projectId ?? ''}-${r.dimensionId ?? ''}-${i}`,
  })),
);

const hourlyColumns = computed(() => {
  const cols: Array<{
    key: string;
    title: string;
    width?: string;
    minWidth?: string;
    align?: 'right';
  }> = [{ key: 'hourStart', title: '小时', width: '130px' }];
  if (hourlyDimension.value !== 'NONE') {
    cols.push({
      key: 'dimensionLabel',
      title: hourlyDimension.value === 'TEAM' ? '团队' : '用户',
      minWidth: '150px',
    });
  }
  cols.push(
    { key: 'projectLabel', title: '项目', minWidth: '150px' },
    { key: 'requests', title: '请求', width: '90px', align: 'right' },
    { key: 'inputTokens', title: '输入', width: '110px', align: 'right' },
    { key: 'outputTokens', title: '输出', width: '110px', align: 'right' },
    { key: 'cacheReadTokens', title: '缓存读', width: '110px', align: 'right' },
    { key: 'cacheCreationTokens', title: '缓存写', width: '110px', align: 'right' },
    { key: 'totalTokens', title: '合计', width: '120px', align: 'right' },
  );
  return cols;
});

async function loadHourly() {
  hourlyLoading.value = true;
  hourlyError.value = '';
  try {
    hourly.value = await api.adminUsageHourly({
      date: hourlyDate.value || undefined,
      days: hourlyDays.value,
      dimension: hourlyDimension.value,
      projectId: projectId.value || undefined,
      userId: userId.value || undefined,
      teamId: teamId.value || undefined,
      tzOffsetMinutes: -new Date().getTimezoneOffset(),
    });
  } catch (error) {
    if (error instanceof ApiError) {
      hourlyError.value = error.message;
    }
  } finally {
    hourlyLoading.value = false;
  }
}

function applyHourlyDays(value: number) {
  hourlyDays.value = value;
  void loadHourly();
}

// ---- time range presets (server default when 0 — behaviour unchanged) ----
const rangeDays = ref<number>(0);
const windowOptions = [
  { value: 0, label: '默认' },
  { value: 7, label: '近 7 天' },
  { value: 30, label: '近 30 天' },
  { value: 93, label: '近 93 天' },
];

function rangeParams(): { from?: string; to?: string } {
  if (!rangeDays.value) return {};
  const to = new Date();
  return {
    from: new Date(to.getTime() - rangeDays.value * 24 * 3600 * 1000).toISOString(),
    to: to.toISOString(),
  };
}

function applyRange(value: number) {
  rangeDays.value = value;
  page.value = 1;
  void load();
}

const groupOptions: UiSelectOption[] = [
  { value: 'project', label: '项目' },
  { value: 'virtual_key', label: '虚拟密钥' },
  { value: 'user', label: '用户' },
  { value: 'team', label: '团队' },
  { value: 'model', label: '模型' },
  { value: 'cache_level', label: '缓存层级' },
  { value: 'day', label: '日' },
  { value: 'month', label: '月' },
];

const columns = [
  { key: 'occurredAt', title: '时间', width: '180px' },
  { key: 'modelId', title: '模型', minWidth: '170px' },
  { key: 'inputTokens', title: '输入', width: '110px', align: 'right' as const },
  { key: 'outputTokens', title: '输出', width: '110px', align: 'right' as const },
  { key: 'cacheLevel', title: '缓存层级', width: '120px' },
  { key: 'upstreamStatusCode', title: '状态码', width: '90px', align: 'right' as const },
  { key: 'usageMissing', title: '用量上报', width: '90px' },
  { key: 'clientIp', title: '来源 IP', width: '140px' },
  { key: 'gatewayRequestId', title: '请求 ID', minWidth: '230px' },
];

// #440: request-sequence guard — rapid filter/window/page changes must not
// let an older summary+records pair land after a newer one.
let loadRequestSeq = 0;

function summaryFilters() {
  return {
    userId: userId.value || undefined,
    projectId: projectId.value || undefined,
    teamId: teamId.value || undefined,
    modelId: modelId.value.trim() || undefined,
  };
}

async function load() {
  const seq = ++loadRequestSeq;
  summaryLoading.value = true;
  recordsLoading.value = true;
  summaryError.value = '';
  const summaryFiltersNow = summaryFilters();
  const range = rangeParams();
  try {
    const [summaryResult, seriesResult, recordsResult] = await Promise.all([
      api.adminUsageSummary({ groupBy: groupBy.value, ...summaryFiltersNow, ...range }),
      api.adminUsageSummary({ groupBy: seriesDim.value, ...summaryFiltersNow, ...range }),
      api.adminUsageRecords({
        ...summaryFiltersNow,
        clientIp: clientIp.value.trim() || undefined,
        page: page.value,
        size: pageSize.value,
        ...range,
      }),
    ]);
    if (seq !== loadRequestSeq) {
      return; // a newer request won — this response is stale
    }
    summary.value = summaryResult;
    series.value = seriesResult;
    records.value = recordsResult;
  } catch (error) {
    if (seq === loadRequestSeq && error instanceof ApiError) {
      summaryError.value = error.message;
      summaryRequestId.value = error.requestId ?? '';
    }
  } finally {
    if (seq === loadRequestSeq) {
      summaryLoading.value = false;
      recordsLoading.value = false;
    }
  }
}

async function loadSeries() {
  const seq = loadRequestSeq;
  try {
    const result = await api.adminUsageSummary({
      groupBy: seriesDim.value,
      ...summaryFilters(),
      ...rangeParams(),
    });
    if (seq === loadRequestSeq) {
      series.value = result;
    }
  } catch {
    // the trend chart keeps its previous data on failure
  }
}

function applySeriesDim(value: 'day' | 'month') {
  seriesDim.value = value;
  void loadSeries();
}

function runQuery() {
  page.value = 1;
  void load();
  void loadHourly();
}

function gotoPage(next: number) {
  if (next < 1) return;
  page.value = next;
  void load();
}

function fmtNum(value: number | undefined): string {
  return (value ?? 0).toLocaleString();
}

function fmtMoney(value: number | string | undefined): string {
  return Number(value ?? 0).toFixed(4);
}

function formatTime(iso?: string): string {
  if (!iso) return '—';
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

const cacheLabel: Record<string, string> = {
  UPSTREAM: '上游',
  COALESCED: '合并',
  L1_HIT: 'L1 命中',
  L2_HIT: 'L2 命中',
};

onMounted(() => {
  void load();
  void loadHourly();
  void loadPickers();
});
</script>

<template>
  <div class="ui-page next-admin-usage">
    <header class="ui-page-header">
      <div>
        <h1 class="ui-page-title">用量与成本</h1>
        <p class="ui-page-desc">
          全租户用量与费用总览：团队 / 用户 / 项目维度的 Token 与成本、趋势、分解与明细。
        </p>
      </div>
    </header>

    <UsageCaliberTip />

    <section class="ui-panel next-admin-usage__filters" data-testid="usage-filter-bar">
      <div class="ui-panel-toolbar">
        <div class="next-admin-usage__range" aria-label="时间范围">
          <button
            v-for="w in windowOptions"
            :key="w.value"
            type="button"
            class="next-admin-usage__seg"
            :class="{ 'next-admin-usage__seg--on': rangeDays === w.value }"
            :data-testid="`admin-usage-range-${w.value}`"
            @click="applyRange(w.value)"
          >
            {{ w.label }}
          </button>
        </div>
        <UiSelect
          v-model="teamId"
          :options="teamOptions"
          width="160px"
          data-testid="usage-team-filter"
          @change="runQuery"
        />
        <UiSelect
          v-model="userId"
          :options="userOptions"
          width="160px"
          data-testid="usage-user-filter"
          @change="runQuery"
        />
        <UiSelect
          v-model="projectId"
          :options="projectOptions"
          width="180px"
          data-testid="usage-project-id"
          @change="runQuery"
        />
        <UiSelect
          v-model="groupBy"
          :options="groupOptions"
          data-testid="usage-group-by"
          @change="load"
        />
        <UiInput
          v-model="modelId"
          placeholder="模型 ID（可选）"
          width="180px"
          data-testid="usage-model-id"
        />
        <UiInput
          v-model="clientIp"
          placeholder="来源 IP（可选）"
          width="160px"
          data-testid="usage-client-ip"
        />
        <UiButton variant="primary" data-testid="usage-query" @click="runQuery">查询</UiButton>
      </div>
      <div v-if="drillChips.length" class="next-admin-usage__drill" data-testid="usage-drill-chips">
        <span class="next-admin-usage__drill-label">下钻筛选：</span>
        <button
          v-for="chip in drillChips"
          :key="chip.kind"
          type="button"
          class="next-admin-usage__drill-chip"
          :data-testid="`usage-drill-${chip.kind}`"
          @click="clearDrill(chip.kind)"
        >
          {{ chip.kind }}：{{ chip.label }} ✕
        </button>
        <button
          v-if="drillChips.length > 1"
          type="button"
          class="next-admin-usage__drill-clear"
          data-testid="usage-drill-clear"
          @click="clearAllDrill"
        >
          全部清除
        </button>
      </div>
      <div
        v-if="summary && !summaryLoading"
        class="next-admin-usage__summary"
        data-testid="usage-summary"
      >
        <div class="next-admin-usage__stat">
          <span class="next-admin-usage__stat-icon next-admin-usage__stat-icon--blue">
            <ChartBarIcon />
          </span>
          <span class="next-admin-usage__stat-main">
            <span class="next-admin-usage__stat-label">请求</span>
            <span class="next-admin-usage__stat-value ui-num">{{ fmtNum(upstreamRequests) }}</span>
            <span class="next-admin-usage__stat-sub"
              >合并 {{ fmtNum(coalescedRequests) }} · 缓存命中 {{ fmtNum(hitRequests) }}</span
            >
          </span>
        </div>
        <div class="next-admin-usage__stat">
          <span class="next-admin-usage__stat-icon next-admin-usage__stat-icon--green">
            <DownloadIcon />
          </span>
          <span class="next-admin-usage__stat-main">
            <span class="next-admin-usage__stat-label">输入 Token</span>
            <span class="next-admin-usage__stat-value ui-num">{{
              fmtNum(totals?.tokens?.input)
            }}</span>
          </span>
        </div>
        <div class="next-admin-usage__stat">
          <span class="next-admin-usage__stat-icon next-admin-usage__stat-icon--cyan">
            <UploadIcon />
          </span>
          <span class="next-admin-usage__stat-main">
            <span class="next-admin-usage__stat-label">输出 Token</span>
            <span class="next-admin-usage__stat-value ui-num">{{
              fmtNum(totals?.tokens?.output)
            }}</span>
          </span>
        </div>
        <div class="next-admin-usage__stat">
          <span class="next-admin-usage__stat-icon next-admin-usage__stat-icon--violet">
            <DownloadIcon />
          </span>
          <span class="next-admin-usage__stat-main">
            <span class="next-admin-usage__stat-label">缓存读 Token</span>
            <span class="next-admin-usage__stat-value ui-num">{{
              fmtNum(totals?.tokens?.cacheRead)
            }}</span>
            <span class="next-admin-usage__stat-sub">命中率 {{ hitRatePct }}</span>
          </span>
        </div>
        <div class="next-admin-usage__stat">
          <span class="next-admin-usage__stat-icon next-admin-usage__stat-icon--gold">
            <MoneyIcon />
          </span>
          <span class="next-admin-usage__stat-main">
            <span class="next-admin-usage__stat-label">网关缓存节省</span>
            <span class="next-admin-usage__stat-value ui-num"
              >¥{{ fmtMoney(totals?.cost?.savedByGatewayCache) }}</span
            >
          </span>
        </div>
        <div class="next-admin-usage__stat">
          <span class="next-admin-usage__stat-icon next-admin-usage__stat-icon--gold">
            <MoneyIcon />
          </span>
          <span class="next-admin-usage__stat-main">
            <span class="next-admin-usage__stat-label">总成本</span>
            <span class="next-admin-usage__stat-value ui-num"
              >¥{{ fmtMoney(totals?.cost?.upstreamPaid) }}</span
            >
            <span class="next-admin-usage__stat-sub">按官方价目估算</span>
          </span>
        </div>
      </div>
    </section>

    <div v-if="summaryError" class="ui-alert ui-alert--error">
      {{ summaryError
      }}<span v-if="summaryRequestId" class="ui-request-id">
        requestId: {{ summaryRequestId }}</span
      >
    </div>

    <section class="ui-panel next-usage__trend" data-testid="usage-trend">
      <div class="ui-panel-head">
        <h2 class="ui-panel-title">用量与成本趋势</h2>
        <div class="next-usage__trend-tabs" role="tablist" aria-label="趋势粒度">
          <button
            v-for="opt in seriesOptions"
            :key="opt.value"
            type="button"
            role="tab"
            class="next-usage__trend-tab"
            :class="{ 'next-usage__trend-tab--on': seriesDim === opt.value }"
            :aria-selected="seriesDim === opt.value"
            :data-testid="`trend-dim-${opt.value}`"
            @click="applySeriesDim(opt.value)"
          >
            {{ opt.label }}
          </button>
        </div>
      </div>
      <div class="ui-panel-body">
        <UiTrendChart :series="trendSeries" data-testid="usage-trend-chart" />
      </div>
    </section>

    <section class="ui-panel next-admin-usage__breakdown" data-testid="usage-breakdown">
      <div class="ui-panel-head">
        <h2 class="ui-panel-title">维度分解 · {{ GROUP_LABELS[groupBy] ?? groupBy }}</h2>
        <div class="next-admin-usage__breakdown-actions">
          <span v-if="drillable" class="next-admin-usage__hint">点击行可下钻到明细</span>
          <UiButton
            variant="secondary"
            data-testid="usage-breakdown-export"
            @click="exportBreakdownCsv"
          >
            导出 CSV
          </UiButton>
        </div>
      </div>
      <UiTable
        :columns="breakdownColumns"
        :data="breakdownRows"
        row-key="key"
        :loading="summaryLoading"
        empty-title="该窗口没有用量"
        data-testid="usage-breakdown-table"
        @row-click="onBreakdownRow"
      >
        <template #label="{ row }">
          <span class="next-admin-usage__breakdown-label">{{
            (row as unknown as BreakdownRow).label
          }}</span>
        </template>
        <template #requests="{ row }">
          <span class="ui-num">{{ fmtNum((row as unknown as BreakdownRow).requests) }}</span>
        </template>
        <template #tokens="{ row }">
          <span class="ui-num">{{ fmtNum((row as unknown as BreakdownRow).tokens) }}</span>
        </template>
        <template #cost="{ row }">
          <span class="ui-num">¥{{ fmtMoney((row as unknown as BreakdownRow).cost) }}</span>
        </template>
        <template #share="{ row }">
          <span class="next-admin-usage__share">
            <span class="next-admin-usage__share-bar">
              <span
                class="next-admin-usage__share-fill"
                :style="{
                  width: `${Math.min(100, (row as unknown as BreakdownRow).share).toFixed(1)}%`,
                }"
              />
            </span>
            <span class="ui-num next-admin-usage__share-pct"
              >{{ (row as unknown as BreakdownRow).share.toFixed(1) }}%</span
            >
          </span>
        </template>
      </UiTable>
    </section>

    <section class="ui-panel next-admin-usage__hourly" data-testid="usage-hourly">
      <div class="ui-panel-head">
        <h2 class="ui-panel-title">每小时 Token</h2>
        <div class="next-admin-usage__hourly-controls">
          <UiInput
            v-model="hourlyDate"
            type="date"
            width="160px"
            data-testid="hourly-date"
            @change="loadHourly"
          />
          <div class="next-admin-usage__range" aria-label="天数">
            <button
              v-for="d in hourlyDayOptions"
              :key="d.value"
              type="button"
              class="next-admin-usage__seg"
              :class="{ 'next-admin-usage__seg--on': hourlyDays === d.value }"
              :data-testid="`hourly-days-${d.value}`"
              @click="applyHourlyDays(d.value)"
            >
              {{ d.label }}
            </button>
          </div>
          <UiSelect
            v-model="hourlyDimension"
            :options="hourlyDimensionOptions"
            data-testid="hourly-dimension"
            @change="loadHourly"
          />
        </div>
      </div>
      <div v-if="hourlyError" class="ui-alert ui-alert--error">{{ hourlyError }}</div>
      <UiTable
        :columns="hourlyColumns"
        :data="hourlyRows"
        row-key="key"
        :loading="hourlyLoading"
        empty-title="该窗口没有按小时数据"
        data-testid="usage-hourly-table"
      >
        <template #hourStart="{ row }">{{
          formatHour((row as HourlyUsageRow).hourStart)
        }}</template>
        <template #dimensionLabel="{ row }">
          <span class="ui-mono">{{ (row as HourlyUsageRow).dimensionLabel ?? '—' }}</span>
        </template>
        <template #projectLabel="{ row }">
          <span class="ui-mono">{{ (row as HourlyUsageRow).projectLabel ?? '—' }}</span>
        </template>
        <template #requests="{ row }">
          <span class="ui-num">{{ fmtNum((row as HourlyUsageRow).requests) }}</span>
        </template>
        <template #inputTokens="{ row }">
          <span class="ui-num">{{ fmtNum((row as HourlyUsageRow).inputTokens) }}</span>
        </template>
        <template #outputTokens="{ row }">
          <span class="ui-num">{{ fmtNum((row as HourlyUsageRow).outputTokens) }}</span>
        </template>
        <template #cacheReadTokens="{ row }">
          <span class="ui-num">{{ fmtNum((row as HourlyUsageRow).cacheReadTokens) }}</span>
        </template>
        <template #cacheCreationTokens="{ row }">
          <span class="ui-num">{{ fmtNum((row as HourlyUsageRow).cacheCreationTokens) }}</span>
        </template>
        <template #totalTokens="{ row }">
          <span class="ui-num">{{ fmtNum((row as HourlyUsageRow).totalTokens) }}</span>
        </template>
      </UiTable>
    </section>

    <section class="ui-panel">
      <UiTable
        :columns="columns"
        :data="records?.items ?? []"
        :loading="recordsLoading"
        row-key="gatewayRequestId"
        empty-title="没有用量记录"
        data-testid="usage-records-table"
      >
        <template #occurredAt="{ row }">{{ formatTime((row as UsageRecord).occurredAt) }}</template>
        <template #modelId="{ row }">
          <span class="ui-mono">{{ (row as UsageRecord).modelId }}</span>
        </template>
        <template #inputTokens="{ row }">
          <span class="ui-num">{{ (row as UsageRecord).inputTokens ?? 0 }}</span>
        </template>
        <template #outputTokens="{ row }">
          <span class="ui-num">{{ (row as UsageRecord).outputTokens ?? 0 }}</span>
        </template>
        <template #cacheLevel="{ row }">
          <UiStatusBadge
            :label="
              cacheLabel[(row as UsageRecord).cacheLevel ?? ''] ?? (row as UsageRecord).cacheLevel
            "
          />
        </template>
        <template #upstreamStatusCode="{ row }">
          <span class="ui-num">{{ (row as UsageRecord).upstreamStatusCode ?? '—' }}</span>
        </template>
        <template #usageMissing="{ row }">
          <UiStatusBadge
            :tone="(row as UsageRecord).usageMissing ? 'warning' : 'success'"
            :label="(row as UsageRecord).usageMissing ? '缺失' : '正常'"
          />
        </template>
        <template #clientIp="{ row }">
          <span class="ui-mono">{{ (row as UsageRecord).clientIp || '—' }}</span>
        </template>
        <template #gatewayRequestId="{ row }">
          <span class="ui-mono next-admin-usage__reqid">{{
            (row as UsageRecord).gatewayRequestId
          }}</span>
        </template>
      </UiTable>
    </section>

    <div class="next-admin-usage__pager">
      <span class="next-admin-usage__pager-text">共 {{ records?.total ?? 0 }} 条</span>
      <UiButton
        variant="secondary"
        :disabled="page <= 1"
        data-testid="usage-prev"
        @click="gotoPage(page - 1)"
      >
        上一页
      </UiButton>
      <span class="next-admin-usage__pager-text next-admin-usage__pager-current"
        >第 {{ page }} 页</span
      >
      <UiButton
        variant="secondary"
        :disabled="(records?.items ?? []).length < pageSize"
        data-testid="usage-next"
        @click="gotoPage(page + 1)"
      >
        下一页
      </UiButton>
    </div>
  </div>
</template>

<style scoped>
.next-admin-usage__filters {
  margin-bottom: var(--ui-space-4);
}

.next-admin-usage__range {
  display: inline-flex;
  gap: 2px;
  padding: 2px;
  background: var(--ui-muted);
  border: 1px solid var(--ui-border-muted);
  border-radius: var(--ui-radius-control);
}

.next-admin-usage__seg {
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

.next-admin-usage__seg:hover {
  color: var(--ui-foreground);
}

.next-admin-usage__seg--on {
  background: var(--ui-card);
  border: 1px solid var(--ui-border);
  color: var(--ui-primary-text);
  font-weight: var(--ui-weight-semibold);
}

/* keep the filter controls in one tight cluster (no space-between spread) */
.next-admin-usage__filters :deep(.ui-panel-toolbar) {
  flex-wrap: wrap;
  justify-content: flex-start;
}

.next-admin-usage__drill {
  display: flex;
  align-items: center;
  gap: var(--ui-space-2);
  flex-wrap: wrap;
  padding: var(--ui-space-2) var(--ui-space-5) 0;
}

.next-admin-usage__drill-label {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
}

.next-admin-usage__drill-chip {
  border: 1px solid var(--ui-border);
  background: var(--ui-muted);
  color: var(--ui-primary-text);
  border-radius: 999px;
  height: 24px;
  padding: 0 10px;
  font-size: var(--ui-font-size-xs);
  font-family: inherit;
  cursor: pointer;
}

.next-admin-usage__drill-chip:hover {
  border-color: var(--ui-primary);
}

.next-admin-usage__drill-clear {
  border: 0;
  background: transparent;
  color: var(--ui-foreground-secondary);
  font-size: var(--ui-font-size-xs);
  font-family: inherit;
  cursor: pointer;
  text-decoration: underline;
}

.next-admin-usage__summary {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
  gap: var(--ui-space-4);
  padding: var(--ui-space-4) var(--ui-space-5);
  border-top: 1px solid var(--ui-border-muted);
}

.next-admin-usage__stat {
  display: flex;
  align-items: center;
  gap: var(--ui-space-3);
}

.next-admin-usage__stat-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  border-radius: 10px;
  flex-shrink: 0;
}

.next-admin-usage__stat-icon svg {
  width: 18px;
  height: 18px;
}

.next-admin-usage__stat-icon--blue {
  background: var(--ui-info-bg);
  color: var(--ui-info-fg);
}

.next-admin-usage__stat-icon--green {
  background: var(--ui-success-bg);
  color: var(--ui-success-fg);
}

.next-admin-usage__stat-icon--cyan {
  background: #e0f4f6;
  color: #0e7490;
}

.next-admin-usage__stat-icon--violet {
  background: #ede9fe;
  color: #6d28d9;
}

.next-admin-usage__stat-icon--gold {
  background: #fdf3e0;
  color: #a16207;
}

.next-admin-usage__stat-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.next-admin-usage__stat-label {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
  white-space: nowrap;
}

.next-admin-usage__stat-value {
  font-size: 20px;
  font-weight: var(--ui-weight-semibold);
  color: var(--ui-foreground);
  letter-spacing: -0.01em;
  white-space: nowrap;
}

.next-admin-usage__stat-sub {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-faint);
  white-space: nowrap;
}

.next-admin-usage__breakdown {
  margin-bottom: var(--ui-space-5);
}

.next-admin-usage__breakdown-actions {
  display: inline-flex;
  align-items: center;
  gap: var(--ui-space-3);
}

.next-admin-usage__hint {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-faint);
}

.next-admin-usage__breakdown-label {
  font-weight: var(--ui-weight-medium);
}

.next-admin-usage__share {
  display: inline-flex;
  align-items: center;
  gap: var(--ui-space-2);
  width: 100%;
}

.next-admin-usage__share-bar {
  flex: 1;
  height: 6px;
  border-radius: 3px;
  background: var(--ui-muted);
  overflow: hidden;
}

.next-admin-usage__share-fill {
  display: block;
  height: 100%;
  border-radius: 3px;
  background: var(--ui-primary);
}

.next-admin-usage__share-pct {
  min-width: 44px;
  text-align: right;
  color: var(--ui-foreground-secondary);
  font-size: var(--ui-font-size-xs);
}

.next-admin-usage__reqid {
  display: inline-block;
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
}

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

.next-admin-usage__pager {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--ui-space-3);
  margin-top: var(--ui-space-4);
}

.next-admin-usage__pager-text {
  font-size: var(--ui-font-size-sm);
  color: var(--ui-foreground-secondary);
}

.next-admin-usage__pager-current {
  color: var(--ui-foreground);
  font-weight: var(--ui-weight-medium);
}

.next-usage__trend {
  margin-bottom: var(--ui-space-5);
}

.next-admin-usage__hourly {
  margin-bottom: var(--ui-space-5);
}

.next-admin-usage__hourly-controls {
  display: inline-flex;
  align-items: center;
  gap: var(--ui-space-3);
  flex-wrap: wrap;
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
</style>
