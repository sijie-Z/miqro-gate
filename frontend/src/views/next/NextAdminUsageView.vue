<script setup lang="ts">
/**
 * NextAdminUsageView — /app/admin-usage 管理端「用量与成本总览」(#681/#758)。
 *
 * cc-switch 式总览 + 腾讯成本分析式分解：
 * - Hero 总览卡：真实消耗 Tokens 大数（≈中文单位）+ 总请求/总成本 + 子指标行
 *   （输入/输出/缓存创建/缓存读取 + 缓存命中率进度条 + 网关缓存节省）；
 * - 服务端时间趋势：输入/输出/缓存读取/缓存创建 4 条 Token 序列 + 成本线；
 * - 报表页签（#758）：请求日志 / 供应商统计 / 模型统计 / 维度分解——统计页签
 *   带成功率与平均延迟（生命周期口径）；请求日志富列：供应商/成本(未定价)/
 *   用时·首字/协议；
 * - 每小时 Token 表（#634）沿用。
 */
import { computed, onMounted, ref } from 'vue';
import AttributionChip from '@/components/AttributionChip.vue';
import { CHART_OTHER_COLOR, COST_SPLIT_COLORS } from '@/lib/chart-palette';
import * as api from '@/api';
import { ChartBarIcon } from 'tdesign-icons-vue-next';
import { ApiError } from '@/api/http';
import UsageCaliberTip from '@/components/UsageCaliberTip.vue';
import UsageAdjustChip from '@/components/UsageAdjustChip.vue';
import { netTokens } from '@/lib/usage-net';
import { csvCell } from '@/utils/csv';
import { localTzOffsetMinutes } from '@/utils/datetime';
import { costGapNote, savingsBoundNote } from '@/lib/usage-pricing';
import {
  UiButton,
  UiDrawer,
  UiInput,
  UiSelect,
  UiStatusBadge,
  UiTable,
  UiTooltip,
  UiTrendChart,
} from '@/ui';
import type { UiSelectOption, UiTrendSeries } from '@/ui';
import type { UsageGroupBy } from '@/types/api';
import type {
  AdminUser,
  ModelCallTimeline,
  ModelCallTimelinePhase,
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

/**
 * Trend colours, taken from the shared palette (#1112) rather than re-spelled here:
 * the four token dimensions keep the colours they have in the cost split and the
 * composition rings, so 「缓存写」 is the same colour in every chart instead of grey
 * here and green there. The cost line is not a token dimension — it is derived money
 * — so it rides the neutral, which is what the neutral is for.
 */
const TREND_COLORS = {
  input: COST_SPLIT_COLORS.input,
  output: COST_SPLIT_COLORS.output,
  cacheRead: COST_SPLIT_COLORS.cacheRead,
  cacheCreation: COST_SPLIT_COLORS.cacheCreation,
  cost: CHART_OTHER_COLOR,
};

// #758: the token series splits into the four billed dimensions (input /
// output / cache read / cache write) plus the cost line — the cc-switch
// anatomy, all four token series independently scaled by the chart.
const trendSeries = computed<UiTrendSeries[]>(() => {
  const groups = [...(series.value?.groups ?? [])].sort((a, b) =>
    String(a.groupKey ?? '').localeCompare(String(b.groupKey ?? '')),
  );
  const label = (key?: string) =>
    seriesDim.value === 'month' ? String(key ?? '') : String(key ?? '').slice(5);
  const pointsOf = (pick: (g: UsageGroup) => number) =>
    groups.map((g) => ({ label: label(g.groupKey), value: pick(g) }));
  return [
    {
      name: '输入',
      color: TREND_COLORS.input,
      kind: 'area',
      points: pointsOf((g) => Number(g.tokens?.input ?? 0)),
    },
    {
      name: '输出',
      color: TREND_COLORS.output,
      kind: 'line',
      points: pointsOf((g) => Number(g.tokens?.output ?? 0)),
    },
    {
      name: '缓存读取',
      color: TREND_COLORS.cacheRead,
      kind: 'line',
      points: pointsOf((g) => Number(g.tokens?.cacheRead ?? 0)),
    },
    {
      name: '缓存创建',
      color: TREND_COLORS.cacheCreation,
      kind: 'line',
      points: pointsOf((g) => Number(g.tokens?.cacheCreation ?? 0)),
    },
    {
      name: '成本 ¥',
      color: TREND_COLORS.cost,
      kind: 'line',
      points: pointsOf((g) => Number(g.cost?.upstreamPaid ?? 0)),
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

// ---- Hero overview card (#758, cc-switch anatomy) ----
const totalRequests = computed(
  () => upstreamRequests.value + coalescedRequests.value + hitRequests.value,
);
const totalTokensAll = computed(() => tokenTotal(totals.value));
/** Chinese-unit companion label for the big number (≈ 72.17 亿 / 5,807.2 万). */
const totalTokensCn = computed(() => {
  const v = totalTokensAll.value;
  if (v >= 100_000_000) return `${(v / 100_000_000).toFixed(2)} 亿`;
  if (v >= 10_000) return `${(v / 10_000).toFixed(1)} 万`;
  return String(v);
});
/**
 * 缓存命中率（Token 口径）= 缓存读取 ÷（缓存读取 + 新增输入）—— the prompt-cache
 * share the provider bills at the read rate; null when nothing was read or sent.
 */
const tokenHitRate = computed<number | null>(() => {
  const read = Number(totals.value?.tokens?.cacheRead ?? 0);
  const fresh = Number(totals.value?.tokens?.input ?? 0);
  const denom = read + fresh;
  return denom > 0 ? (read / denom) * 100 : null;
});
const tokenHitRatePct = computed(() =>
  tokenHitRate.value === null ? '—' : tokenHitRate.value.toFixed(1) + '%',
);
/**
 * #790: hits the gateway could not value, because no price was in force when they
 * happened. While any remain, the saving above is a lower bound — without this the
 * number reads as "the cache saved almost nothing" rather than "we cannot say".
 */
const savingsBound = computed(() => savingsBoundNote(totals.value));

/**
 * #801: the cost figure above is not a total while this is non-null. The API has
 * said so since #766 (`pricingStatus` + `unpriced`); the console just never showed
 * it, so an incomplete amount read as a complete one.
 */
const costCaveat = computed(() => costGapNote(totals.value));

const heroSub = computed(() => {
  const t = totals.value?.tokens;
  return [
    { key: 'input', label: '新增输入', value: Number(t?.input ?? 0) },
    { key: 'output', label: '输出', value: Number(t?.output ?? 0) },
    { key: 'creation', label: '缓存创建', value: Number(t?.cacheCreation ?? 0) },
    { key: 'read', label: '缓存读取', value: Number(t?.cacheRead ?? 0) },
  ];
});

// ---- report tabs (#758): 请求日志 / 供应商统计 / 模型统计 / 维度分解 ----
type ReportTab = 'records' | 'provider' | 'model' | 'breakdown';
const activeTab = ref<ReportTab>('records');
const tabOptions: Array<{ value: ReportTab; label: string; hint: string }> = [
  { value: 'records', label: '请求日志', hint: '逐条调用明细' },
  { value: 'provider', label: '供应商统计', hint: '按供应商产品聚合' },
  { value: 'model', label: '模型统计', hint: '按模型聚合' },
  { value: 'breakdown', label: '维度分解', hint: '自选维度与导出' },
];

/** The aggregation dimension behind the active tab. */
const breakdownGroupBy = computed(() =>
  activeTab.value === 'provider'
    ? 'product'
    : activeTab.value === 'model'
      ? 'model'
      : groupBy.value,
);

function toggleTab(tab: ReportTab) {
  activeTab.value = tab;
  if (tab !== 'records') {
    void loadBreakdown();
  }
}

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
  product: '供应商产品',
};

interface BreakdownRow {
  key: string;
  label: string;
  requests: number;
  tokens: number;
  cost: number;
  /**
   * #876: the group's cost is short of a total while this is non-empty. Empty is the
   * "nothing to say" value because `UiTooltip.text` is a plain string, and a template
   * narrows `v-if` on a ref but not on a function's result.
   */
  costCaveat: string;
  /** Percent (0–100) over decided calls; null when nothing was decided yet. */
  successRate: number | null;
  avgLatencyMs: number | null;
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
      const succeeded = Number(g.outcomes?.succeeded ?? 0);
      const failed = Number(g.outcomes?.failed ?? 0);
      const decided = succeeded + failed;
      return {
        key: String(g.groupKey ?? g.label ?? ''),
        label: g.label ?? String(g.groupKey ?? '—'),
        requests: Number(g.requests?.upstream ?? 0),
        tokens,
        cost,
        // #876: the group carries its own pricingStatus / unpriced (the API has sent
        // them since #766); only this table dropped them, while the hero card above —
        // the same figure — honoured them.
        costCaveat: costGapNote(g) ?? '',
        successRate: decided > 0 ? (succeeded / decided) * 100 : null,
        avgLatencyMs: g.outcomes?.avgDurationMs ?? null,
        share: shareBase > 0 ? ((useCost ? cost : tokens) / shareBase) * 100 : 0,
      };
    })
    .sort((a, b) => b.cost - a.cost || b.tokens - a.tokens);
});

const breakdownColumns = computed(() => [
  // The header names the ACTIVE dimension (供应商统计 / 模型统计 preset it), not
  // merely the breakdown selector's value.
  { key: 'label', title: GROUP_LABELS[breakdownGroupBy.value] ?? '分组', minWidth: '180px' },
  { key: 'requests', title: '请求', width: '100px', align: 'right' as const, sortable: true },
  { key: 'tokens', title: 'Token', width: '120px', align: 'right' as const, sortable: true },
  { key: 'cost', title: '成本 ¥', width: '120px', align: 'right' as const, sortable: true },
  { key: 'successRate', title: '成功率', width: '90px', align: 'right' as const, sortable: true },
  {
    key: 'avgLatencyMs',
    title: '平均延迟',
    width: '100px',
    align: 'right' as const,
    sortable: true,
  },
  { key: 'share', title: '占比', width: '160px' },
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
  // #1114: every other export in the console goes through csvCell (RFC 4180
  // quoting + the #430 formula-injection guard). This one was the last plain
  // join(',') — and its first column is a label people type (project names,
  // usernames, model ids), so a comma shifted columns and a leading '=' executed
  // in the reader's spreadsheet.
  const header = ['分组', '请求', 'Token', '成本(CNY)', '占比(%)'].map(csvCell).join(',');
  const body = rows.map((r) =>
    [r.label, r.requests, r.tokens, r.cost.toFixed(4), r.share.toFixed(2)].map(csvCell).join(','),
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
  { value: 'product', label: '供应商产品' },
  { value: 'cache_level', label: '缓存层级' },
  { value: 'day', label: '日' },
  { value: 'month', label: '月' },
];

// #773: the token columns report the *net* counts (observed + adjustments), so
// their cells add up to the summary printed above them. The observed counts
// stay reachable through the 调整 chip's bubble.
const columns = [
  { key: 'occurredAt', title: '时间', width: '150px' },
  { key: 'providerProductName', title: '供应商', minWidth: '150px' },
  { key: 'modelId', title: '模型', minWidth: '170px' },
  { key: 'attribution', title: '归属', width: '150px' },
  { key: 'inputTokens', title: '输入', minWidth: '150px', align: 'right' as const },
  { key: 'outputTokens', title: '输出', width: '100px', align: 'right' as const },
  { key: 'adjust', title: '调整', width: '100px' },
  { key: 'cost', title: '成本', width: '110px', align: 'right' as const },
  { key: 'latencyMs', title: '用时 / 首字', width: '130px', align: 'right' as const },
  { key: 'upstreamStatusCode', title: '状态码', width: '90px', align: 'right' as const },
  { key: 'wireProtocol', title: '协议', width: '120px' },
  { key: 'cacheLevel', title: '缓存层级', width: '110px' },
  { key: 'usageMissing', title: '用量上报', width: '90px' },
  { key: 'clientIp', title: '来源 IP', width: '140px' },
  { key: 'gatewayRequestId', title: '请求 ID', minWidth: '230px' },
];

/**
 * #773: the 调整 column says what the net counts were derived from, so it earns
 * its 100px only while the rows on screen actually carry an adjustment. With
 * none in sight the whole column is dashes, which is the extra column and the
 * visual noise the acceptance criterion rules out — same treatment as the
 * single-project column in NextKeysView.
 */
const visibleColumns = computed(() =>
  (records.value?.items ?? []).some((row) => row.adjusted === true)
    ? columns
    : columns.filter((column) => column.key !== 'adjust'),
);

/** 用时/首字 cell: "4.9s / 2.1s"; sub-second values stay in ms. */
function fmtDuration(ms?: number | null): string {
  if (ms === null || ms === undefined) return '—';
  return ms >= 1_000 ? `${(ms / 1_000).toFixed(1)}s` : `${ms}ms`;
}

/** Inbound protocol families the gateway records (ProtocolFamily names). */
const protocolLabel: Record<string, string> = {
  ANTHROPIC_MESSAGES: 'Anthropic',
  OPENAI_RESPONSES: 'OAI Responses',
  OPENAI_CHAT_COMPLETIONS: 'OAI Chat',
};

function protocolOf(value?: string | null): string {
  if (!value) return '—';
  return protocolLabel[value] ?? value;
}

/** Success-rate cell: over decided calls (succeeded + failed); '—' when undecided. */
function rateText(row: BreakdownRow): string {
  return row.successRate === null ? '—' : row.successRate.toFixed(1) + '%';
}

/** Terminal lifecycle statuses recorded by the gateway (RequestStatus names). */
const lifecycleLabel: Record<string, string> = {
  SUCCEEDED: '成功',
  UPSTREAM_REJECTED: '上游拒绝',
  UPSTREAM_UNAVAILABLE: '上游不可达',
  CLIENT_CANCELLED: '客户端取消',
  TIMEOUT_BEFORE_FIRST_BYTE: '首字节超时',
  STREAM_INTERRUPTED: '流中断',
  AUTH_REJECTED: '鉴权拒绝',
  MODEL_NOT_ALLOWED: '模型未授权',
  USAGE_PARSE_FAILED: '用量解析失败',
  IN_FLIGHT: '进行中',
};

function statusHintOf(row: UsageRecord): string {
  const status = row.requestStatus ?? '';
  const label = lifecycleLabel[status] ?? status;
  return `终态：${label}${row.isComplete ? '' : '（未完成）'}`;
}

// #440: request-sequence guard — rapid filter/window/page changes must not
// let an older summary+records pair land after a newer one.
let loadRequestSeq = 0;

// #PH55: the trend chart has two writers — load() fetches it alongside the
// summary and records, loadSeries() re-fetches it alone when the bucket
// dimension changes — so it carries a sequence of its own. Guarding it with
// loadRequestSeq instead is not equivalent: a 按日/按月 click would then
// abandon an in-flight 查询 or page turn (summary, records and all, since
// load() returns early on a stale seq) and leave summaryLoading/recordsLoading
// stuck true, because load()'s finally clears them under the same condition.
let seriesRequestSeq = 0;

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
  const seriesSeq = ++seriesRequestSeq;
  summaryLoading.value = true;
  recordsLoading.value = true;
  summaryError.value = '';
  const summaryFiltersNow = summaryFilters();
  const range = rangeParams();
  try {
    const [summaryResult, seriesResult, recordsResult] = await Promise.all([
      api.adminUsageSummary({ groupBy: breakdownGroupBy.value, ...summaryFiltersNow, ...range }),
      api.adminUsageSummary({
        groupBy: seriesDim.value,
        ...summaryFiltersNow,
        ...range,
        tzOffsetMinutes: localTzOffsetMinutes(),
      }),
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
    records.value = recordsResult;
    // The series is dropped on its own if a 按日/按月 click overtook this load;
    // summary and records still belong to the user's own request.
    if (seriesSeq === seriesRequestSeq) {
      series.value = seriesResult;
    }
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
  const seq = ++seriesRequestSeq;
  try {
    const result = await api.adminUsageSummary({
      groupBy: seriesDim.value,
      ...summaryFilters(),
      ...rangeParams(),
      tzOffsetMinutes: localTzOffsetMinutes(),
    });
    if (seq === seriesRequestSeq) {
      series.value = result;
    }
  } catch {
    // the trend chart keeps its previous data on failure
  }
}

// #758: switching report tabs only re-runs the aggregation behind the tab.
let breakdownRequestSeq = 0;

async function loadBreakdown() {
  const seq = ++breakdownRequestSeq;
  summaryLoading.value = true;
  summaryError.value = '';
  try {
    const result = await api.adminUsageSummary({
      groupBy: breakdownGroupBy.value,
      ...summaryFilters(),
      ...rangeParams(),
    });
    if (seq !== breakdownRequestSeq) {
      return; // a newer request won — this response is stale
    }
    summary.value = result;
  } catch (error) {
    if (seq === breakdownRequestSeq && error instanceof ApiError) {
      summaryError.value = error.message;
      summaryRequestId.value = error.requestId ?? '';
    }
  } finally {
    if (seq === breakdownRequestSeq) {
      summaryLoading.value = false;
    }
  }
}

// #758: page-jump input beside prev/next (same pattern as #643 on 我的用量).
const pageInput = ref('');
const totalPages = computed(() =>
  Math.max(1, Math.ceil((records.value?.total ?? 0) / pageSize.value)),
);

function jumpToPage() {
  const wanted = Number.parseInt(pageInput.value, 10);
  if (Number.isNaN(wanted)) {
    pageInput.value = '';
    return;
  }
  pageInput.value = '';
  gotoPage(Math.min(Math.max(wanted, 1), totalPages.value));
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

function fmtNum(value: number | null | undefined): string {
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

// ---------------------------------------------------------------------------
// #707: per-call timeline drawer, opened from the 请求 ID column.
//
// The model side carries far more state than the MCP log does, so the drawer is
// read in three tiers instead of one flat list:
//   1. terminal badge + total duration + "where it stopped" (one glance);
//   2. TTFB / retries / partial response / HTTP status (on follow-up);
//   3. attribution chain, identifiers and the token split (rarely).
// Metadata only — the endpoint never returns prompt or response content.
// ---------------------------------------------------------------------------

/** The three milestones the gateway records, in order. A call that never got
 * past 受理 simply has fewer phases — that absence is the diagnosis, so the
 * drawer renders every slot and marks the unobserved ones as missing rather
 * than inventing a placeholder timestamp. */
const PHASE_SLOTS = [
  { key: 'ACCEPTED', label: '受理' },
  { key: 'FIRST_BYTE', label: '上游首字节' },
  { key: 'COMPLETED', label: '完成' },
] as const;

type BadgeTone = 'success' | 'warning' | 'danger' | 'neutral' | 'info';

/** All 7 lifecycle terminals plus the un-finalized IN_FLIGHT row (#705 keeps
 * stale rows visible instead of hiding them). `stalled` answers "卡在哪一段"
 * in the operator's words. */
const TERMINAL_META: Record<string, { label: string; tone: BadgeTone; stalled: string }> = {
  SUCCEEDED: { label: '成功', tone: 'success', stalled: '三个阶段齐备，调用完整走完。' },
  CLIENT_CANCELLED: {
    label: '客户端取消',
    tone: 'warning',
    stalled: '调用方在响应写完前断开——阶段越少，说明断开得越早。',
  },
  STREAM_INTERRUPTED: {
    label: '流中断',
    tone: 'danger',
    stalled: '上游首字节已返回，流在写完之前中断。',
  },
  TIMEOUT_BEFORE_FIRST_BYTE: {
    label: '首字节前超时',
    tone: 'danger',
    stalled: '卡在「受理」之后、「上游首字节」之前——上游超时未回包。',
  },
  UPSTREAM_REJECTED: {
    label: '上游拒绝',
    tone: 'danger',
    stalled: '调用走完，但上游返回非 2xx（正文原样回给调用方）。',
  },
  UPSTREAM_UNAVAILABLE: {
    label: '上游不可用',
    tone: 'danger',
    stalled: '未拿到上游响应——上游不可达，或凭证无法路由。',
  },
  IN_FLIGHT: {
    label: '未结算',
    tone: 'warning',
    stalled: '记录未结算：网关重启后的滞留行，或调用仍在进行。',
  },
  AUTH_REJECTED: { label: '鉴权被拒', tone: 'danger', stalled: '请求在虚拟密钥鉴权阶段被拒。' },
  MODEL_NOT_ALLOWED: {
    label: '模型未准入',
    tone: 'danger',
    stalled: '请求的模型不在该密钥的准入集合内。',
  },
  USAGE_PARSE_FAILED: {
    label: '用量解析失败',
    tone: 'warning',
    stalled: '上游响应完整，但用量解析失败。',
  },
};

const timelineOpen = ref(false);
const timelineLoading = ref(false);
const timeline = ref<ModelCallTimeline | null>(null);
const timelineGatewayRequestId = ref('');
const timelineError = ref('');
/** 404 is not a failure: it means the id has no lifecycle row at all. */
const timelineMissing = ref(false);

async function openTimeline(gatewayRequestId?: string) {
  if (!gatewayRequestId) return;
  timelineGatewayRequestId.value = gatewayRequestId;
  timelineOpen.value = true;
  timeline.value = null;
  timelineError.value = '';
  timelineMissing.value = false;
  timelineLoading.value = true;
  try {
    timeline.value = await api.adminUsageTimeline(gatewayRequestId);
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) {
      timelineMissing.value = true;
    } else if (error instanceof ApiError) {
      timelineError.value = error.message;
    } else {
      timelineError.value = '加载调用时间线失败，请稍后重试。';
    }
  } finally {
    timelineLoading.value = false;
  }
}

const timelineStatus = computed(() => String(timeline.value?.status ?? ''));
const timelineStatusMeta = computed(
  () =>
    TERMINAL_META[timelineStatus.value] ?? {
      label: timelineStatus.value || '未知终态',
      tone: 'neutral' as BadgeTone,
      stalled: '',
    },
);

function phaseOf(key: string): ModelCallTimelinePhase | undefined {
  return (timeline.value?.phases ?? []).find((phase) => phase.key === key);
}

interface PhaseSlot {
  key: string;
  label: string;
  at?: string;
  elapsedMs?: number | null;
  present: boolean;
}

/** All three canonical slots, each marked present or missing — never inventing
 * a timestamp for a milestone the gateway did not record. */
const phaseRows = computed<PhaseSlot[]>(() =>
  PHASE_SLOTS.map((slot) => {
    const phase = phaseOf(slot.key);
    return {
      key: slot.key,
      label: phase?.label ?? slot.label,
      at: phase?.at,
      elapsedMs: phase?.elapsedMs,
      present: Boolean(phase),
    };
  }),
);

/** The last milestone actually observed — the drawer's headline conclusion. */
const furthestPhaseKey = computed(() => {
  const present = new Set((timeline.value?.phases ?? []).map((phase) => phase.key));
  return [...PHASE_SLOTS].reverse().find((slot) => present.has(slot.key))?.key ?? '';
});

const stalledText = computed(() => {
  const known = timelineStatusMeta.value.stalled;
  if (known) return known;
  switch (furthestPhaseKey.value) {
    case 'COMPLETED':
      return '三个阶段齐备，调用完整走完。';
    case 'FIRST_BYTE':
      return '停在「上游首字节」与「完成」之间——上游已回包，响应在写回过程中中断。';
    case 'ACCEPTED':
      return '只记录到「受理」——网关未收到上游首字节。';
    default:
      return '没有可回放的阶段。';
  }
});

/** ms with unit; long calls switch to seconds so the headline stays readable. */
function formatDuration(ms?: number | null): string {
  if (ms === null || ms === undefined) return '—';
  if (ms >= 1000) return `${(ms / 1000).toFixed(2)} s`;
  return `${ms} ms`;
}

function formatNumber(value?: number | null): string {
  if (value === null || value === undefined) return '—';
  return value.toLocaleString();
}

function formatInstant(iso?: string): string {
  if (!iso) return '—';
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(
    d.getMinutes(),
  )}:${pad(d.getSeconds())}`;
}

/** Short form for the attribution chain; the full UUID stays in the tooltip. */
function shortId(id?: string): string {
  return id ? id.slice(0, 8) : '—';
}

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
    </section>

    <!-- Hero 总览卡（#758，cc-switch 解剖：一个大数 + 总请求 / 总成本 + 子指标行） -->
    <section
      v-if="summary && !summaryLoading"
      class="ui-panel next-admin-usage__hero"
      data-testid="usage-summary"
    >
      <div class="next-admin-usage__hero-main">
        <span class="next-admin-usage__hero-chip" aria-hidden="true"><ChartBarIcon /></span>
        <div class="next-admin-usage__hero-number">
          <span class="next-admin-usage__hero-label">真实消耗 Tokens</span>
          <span class="next-admin-usage__hero-value ui-num">{{ fmtNum(totalTokensAll) }}</span>
          <span class="next-admin-usage__hero-cn">≈ {{ totalTokensCn }}</span>
        </div>
        <div class="next-admin-usage__hero-aside">
          <div class="next-admin-usage__hero-mini">
            <span class="next-admin-usage__hero-mini-label">总请求数</span>
            <span class="next-admin-usage__hero-mini-value ui-num">{{
              fmtNum(totalRequests)
            }}</span>
            <span class="next-admin-usage__hero-mini-sub"
              >转发 {{ fmtNum(upstreamRequests) }} · 合并 {{ fmtNum(coalescedRequests) }} · 缓存命中
              {{ fmtNum(hitRequests) }}（{{ hitRatePct }}）</span
            >
          </div>
          <div class="next-admin-usage__hero-mini">
            <span class="next-admin-usage__hero-mini-label">总成本</span>
            <span class="next-admin-usage__hero-mini-value ui-num"
              >¥{{ fmtMoney(totals?.cost?.upstreamPaid) }}</span
            >
            <span class="next-admin-usage__hero-mini-sub">按官方价目估算</span>
            <UiTooltip v-if="costCaveat" :text="costCaveat">
              <span class="next-admin-usage__unpriced" data-testid="cost-unpriced">未定价</span>
            </UiTooltip>
          </div>
        </div>
      </div>
      <div class="next-admin-usage__hero-subs">
        <div v-for="item in heroSub" :key="item.key" class="next-admin-usage__hero-sub">
          <span class="next-admin-usage__hero-sub-label">{{ item.label }}</span>
          <span class="next-admin-usage__hero-sub-value ui-num">{{ fmtNum(item.value) }}</span>
        </div>
        <div class="next-admin-usage__hero-sub next-admin-usage__hero-sub--rate">
          <span class="next-admin-usage__hero-sub-label">
            <UiTooltip text="缓存命中率（Token 口径）= 缓存读取 ÷（缓存读取 + 新增输入）">
              <span>缓存命中率</span>
            </UiTooltip>
          </span>
          <span class="next-admin-usage__hero-sub-value ui-num">{{ tokenHitRatePct }}</span>
          <span class="next-admin-usage__hero-bar" aria-hidden="true">
            <span
              class="next-admin-usage__hero-bar-fill"
              :style="{ width: `${Math.min(100, tokenHitRate ?? 0).toFixed(1)}%` }"
            />
          </span>
        </div>
        <div class="next-admin-usage__hero-sub">
          <span class="next-admin-usage__hero-sub-label">网关缓存节省</span>
          <span class="next-admin-usage__hero-sub-value ui-num"
            >¥{{ fmtMoney(totals?.cost?.savedByGatewayCache) }}</span
          >
          <UiTooltip v-if="savingsBound" :text="savingsBound">
            <span class="next-admin-usage__unpriced" data-testid="savings-unpriced">下界</span>
          </UiTooltip>
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
        <UiTrendChart
          :series="trendSeries"
          :empty-text="summaryError ? '趋势加载失败' : undefined"
          data-testid="usage-trend-chart"
        />
      </div>
    </section>

    <!-- #758: 报表页签 —— 请求日志 / 供应商统计 / 模型统计 / 维度分解 -->
    <section class="ui-panel next-admin-usage__report" data-testid="usage-report">
      <div class="ui-panel-head next-admin-usage__report-head">
        <div class="next-admin-usage__tabs" role="tablist" aria-label="用量视图">
          <button
            v-for="tab in tabOptions"
            :key="tab.value"
            type="button"
            role="tab"
            class="next-admin-usage__tab"
            :class="{ 'next-admin-usage__tab--on': activeTab === tab.value }"
            :aria-selected="activeTab === tab.value"
            :title="tab.hint"
            :data-testid="`usage-tab-${tab.value}`"
            @click="toggleTab(tab.value)"
          >
            {{ tab.label }}
          </button>
        </div>
        <div class="next-admin-usage__report-actions">
          <UiSelect
            v-if="activeTab === 'breakdown'"
            v-model="groupBy"
            :options="groupOptions"
            width="150px"
            data-testid="usage-group-by"
            @change="loadBreakdown"
          />
          <span v-if="activeTab === 'breakdown' && drillable" class="next-admin-usage__hint"
            >点击行可下钻到明细</span
          >
          <UiButton
            v-if="activeTab === 'breakdown'"
            variant="secondary"
            data-testid="usage-breakdown-export"
            @click="exportBreakdownCsv"
          >
            导出 CSV
          </UiButton>
        </div>
      </div>

      <!-- 请求日志 -->
      <UiTable
        v-if="activeTab === 'records'"
        :columns="visibleColumns"
        :data="records?.items ?? []"
        :loading="recordsLoading"
        row-key="gatewayRequestId"
        empty-title="没有用量记录"
        :error="summaryError"
        data-testid="usage-records-table"
        @retry="load"
      >
        <template #occurredAt="{ row }">{{ formatTime((row as UsageRecord).occurredAt) }}</template>
        <template #providerProductName="{ row }">
          <span class="next-admin-usage__provider">{{
            (row as UsageRecord).providerProductName || '—'
          }}</span>
        </template>
        <template #modelId="{ row }">
          <span class="ui-mono">{{ (row as UsageRecord).modelId }}</span>
        </template>
        <template #inputTokens="{ row }">
          <span class="ui-num">{{ fmtNum(netTokens(row as UsageRecord).input) }}</span>
          <span
            v-if="
              netTokens(row as UsageRecord).cacheRead || netTokens(row as UsageRecord).cacheCreation
            "
            class="next-admin-usage__cell-sub"
            >读 {{ fmtNum(netTokens(row as UsageRecord).cacheRead) }} · 写
            {{ fmtNum(netTokens(row as UsageRecord).cacheCreation) }}</span
          >
        </template>
        <template #outputTokens="{ row }">
          <span class="ui-num">{{ fmtNum(netTokens(row as UsageRecord).output) }}</span>
        </template>
        <template #adjust="{ row }">
          <UsageAdjustChip :record="row as UsageRecord" />
        </template>
        <template #attribution="{ row }">
          <AttributionChip
            :resolution-status="(row as UsageRecord).resolutionStatus"
            :claim-source="(row as UsageRecord).claimSource"
            :claim-confidence="(row as UsageRecord).claimConfidence"
          />
        </template>
        <template #cost="{ row }">
          <span v-if="(row as UsageRecord).priced !== false" class="ui-num"
            >¥{{ fmtMoney((row as UsageRecord).cost) }}</span
          >
          <UiTooltip v-else text="该模型尚无价目快照——在「定价」页录入单价后自动入账">
            <span class="next-admin-usage__unpriced">未定价</span>
          </UiTooltip>
        </template>
        <template #latencyMs="{ row }">
          <span class="ui-num"
            >{{ fmtDuration((row as UsageRecord).latencyMs) }} /
            {{ fmtDuration((row as UsageRecord).ttfbMs) }}</span
          >
        </template>
        <template #upstreamStatusCode="{ row }">
          <UiTooltip
            v-if="(row as UsageRecord).requestStatus"
            :text="statusHintOf(row as UsageRecord)"
          >
            <span class="ui-num">{{ (row as UsageRecord).upstreamStatusCode ?? '—' }}</span>
          </UiTooltip>
          <span v-else class="ui-num">{{ (row as UsageRecord).upstreamStatusCode ?? '—' }}</span>
        </template>
        <template #wireProtocol="{ row }">
          <span class="next-admin-usage__protocol">{{
            protocolOf((row as UsageRecord).wireProtocol)
          }}</span>
        </template>
        <template #cacheLevel="{ row }">
          <UiStatusBadge
            :label="
              cacheLabel[(row as UsageRecord).cacheLevel ?? ''] ?? (row as UsageRecord).cacheLevel
            "
          />
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
          <button
            v-if="(row as UsageRecord).gatewayRequestId"
            type="button"
            class="ui-link-action next-admin-usage__reqid"
            :title="(row as UsageRecord).gatewayRequestId"
            :data-testid="`usage-timeline-${(row as UsageRecord).gatewayRequestId}`"
            @click="openTimeline((row as UsageRecord).gatewayRequestId)"
          >
            {{ (row as UsageRecord).gatewayRequestId }}
          </button>
          <span v-else class="ui-muted">—</span>
        </template>
      </UiTable>

      <!-- 供应商统计 / 模型统计 / 维度分解（同表，维度随页签） -->
      <UiTable
        v-else
        :columns="breakdownColumns"
        :data="breakdownRows"
        row-key="key"
        :loading="summaryLoading"
        empty-title="该窗口没有用量"
        :error="summaryError"
        data-testid="usage-breakdown-table"
        @row-click="onBreakdownRow"
        @retry="loadBreakdown"
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
          <UiTooltip
            v-if="(row as unknown as BreakdownRow).costCaveat"
            :text="(row as unknown as BreakdownRow).costCaveat"
          >
            <span class="next-admin-usage__unpriced" data-testid="breakdown-cost-unpriced"
              >未定价</span
            >
          </UiTooltip>
        </template>
        <template #successRate="{ row }">
          <span class="ui-num">{{ rateText(row as unknown as BreakdownRow) }}</span>
        </template>
        <template #avgLatencyMs="{ row }">
          <span class="ui-num">{{
            fmtDuration((row as unknown as BreakdownRow).avgLatencyMs)
          }}</span>
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

      <div v-if="activeTab === 'records'" class="next-admin-usage__pager" data-testid="usage-pager">
        <span class="next-admin-usage__pager-text"
          >共 {{ summaryError ? '—' : (records?.total ?? 0) }} 条</span
        >
        <div class="next-admin-usage__pager-jump">
          <span>跳至</span>
          <input
            v-model="pageInput"
            class="next-admin-usage__page-input"
            type="text"
            inputmode="numeric"
            :placeholder="String(page)"
            data-testid="usage-page-input"
            @keydown.enter="jumpToPage"
          />
          <span>页</span>
          <UiButton variant="secondary" size="sm" data-testid="usage-page-go" @click="jumpToPage">
            跳转
          </UiButton>
        </div>
        <UiButton
          variant="secondary"
          :disabled="page <= 1"
          data-testid="usage-prev"
          @click="gotoPage(page - 1)"
        >
          上一页
        </UiButton>
        <span class="next-admin-usage__pager-text next-admin-usage__pager-current"
          >第 {{ page }} 页 / {{ totalPages }}</span
        >
        <UiButton
          variant="secondary"
          :disabled="page >= totalPages"
          data-testid="usage-next"
          @click="gotoPage(page + 1)"
        >
          下一页
        </UiButton>
      </div>
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
        :error="hourlyError"
        data-testid="usage-hourly-table"
        @retry="loadHourly"
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

    <!-- #707: per-call timeline, opened from the 请求 ID column -->
    <UiDrawer
      :open="timelineOpen"
      :title="timeline?.modelId ? `调用时间线 · ${timeline.modelId}` : '调用时间线'"
      width="620px"
      data-testid="usage-timeline-drawer"
      @update:open="timelineOpen = false"
    >
      <div class="next-admin-usage__tl">
        <p class="next-admin-usage__tl-reqid ui-mono" data-testid="usage-timeline-reqid">
          {{ timelineGatewayRequestId }}
        </p>

        <p v-if="timelineLoading" class="next-admin-usage__tl-loading">正在读取调用留痕…</p>

        <!-- 404 is an expected answer, not a failure: nothing was ever written
             for calls that never reached upstream. Explain, do not alarm. -->
        <div
          v-else-if="timelineMissing"
          class="next-admin-usage__tl-note"
          data-testid="usage-timeline-missing"
        >
          <p class="next-admin-usage__tl-note-title">这条请求没有可回放的调用留痕</p>
          <p class="next-admin-usage__tl-note-body">
            调用时间线只覆盖<strong>实际转发到上游</strong>的请求。网关缓存命中与合并（coalesced）请求不写入该表，
            因此查不到属于预期，并不代表这次调用失败了。
          </p>
        </div>

        <div
          v-else-if="timelineError"
          class="ui-alert ui-alert--error"
          data-testid="usage-timeline-error"
        >
          {{ timelineError }}
        </div>

        <template v-else-if="timeline">
          <!-- 第一层：一眼看懂 —— 终态 / 总耗时 / 卡在哪一段 -->
          <section class="next-admin-usage__tl-hero" data-testid="usage-timeline-hero">
            <div class="next-admin-usage__tl-hero-top">
              <span data-testid="usage-timeline-status">
                <UiStatusBadge :tone="timelineStatusMeta.tone" :label="timelineStatusMeta.label" />
              </span>
              <span
                class="next-admin-usage__tl-duration ui-num"
                data-testid="usage-timeline-duration"
                >{{ formatDuration(timeline.durationMs) }}</span
              >
              <span class="next-admin-usage__tl-duration-label">总耗时</span>
            </div>
            <p class="next-admin-usage__tl-stalled" data-testid="usage-timeline-stalled">
              {{ stalledText }}
            </p>
          </section>

          <ol class="next-admin-usage__tl-phases" data-testid="usage-timeline-phases">
            <li
              v-for="slot in phaseRows"
              :key="slot.key"
              class="next-admin-usage__tl-phase"
              :class="{ 'next-admin-usage__tl-phase--missing': !slot.present }"
              :data-testid="`usage-timeline-phase-${slot.key}`"
            >
              <span class="next-admin-usage__tl-dot" />
              <span class="next-admin-usage__tl-phase-label">{{ slot.label }}</span>
              <template v-if="slot.present">
                <span class="ui-mono">{{ formatInstant(slot.at) }}</span>
                <span class="ui-num next-admin-usage__tl-delta">{{
                  slot.elapsedMs ? `+${formatDuration(slot.elapsedMs)}` : '起点'
                }}</span>
              </template>
              <span v-else class="next-admin-usage__tl-absent">缺失 · 未记录</span>
            </li>
          </ol>
          <p class="next-admin-usage__tl-hint">耗时均为距「受理」的增量；缺失的阶段不补零。</p>

          <!-- 第二层：追问才看 —— TTFB / 重试 / 部分响应 / HTTP -->
          <dl class="next-admin-usage__tl-metrics" data-testid="usage-timeline-metrics">
            <div class="next-admin-usage__tl-metric">
              <dt>上游首字节</dt>
              <dd class="ui-num" data-testid="usage-timeline-ttfb">
                {{ formatDuration(timeline.timeToFirstByteMs) }}
              </dd>
            </div>
            <div class="next-admin-usage__tl-metric">
              <dt>重试次数</dt>
              <dd class="ui-num" data-testid="usage-timeline-retries">
                {{ timeline.retryCount ?? 0 }} 次
              </dd>
            </div>
            <div class="next-admin-usage__tl-metric">
              <dt>部分响应</dt>
              <dd data-testid="usage-timeline-partial">
                <UiStatusBadge
                  :tone="timeline.partialResponse ? 'warning' : 'success'"
                  :label="timeline.partialResponse ? '是' : '否'"
                  :title="
                    timeline.partialResponse
                      ? '上游已交付部分内容，流未读完'
                      : '未出现截断的部分响应'
                  "
                />
              </dd>
            </div>
            <div class="next-admin-usage__tl-metric">
              <dt>HTTP 状态</dt>
              <dd class="ui-num" data-testid="usage-timeline-http">
                {{ timeline.httpStatus ?? '—' }}
              </dd>
            </div>
          </dl>

          <!-- 第三层：很少看 —— 归属链、标识与 Token 明细 -->
          <details class="next-admin-usage__tl-more" data-testid="usage-timeline-details">
            <summary>归属链与令牌明细</summary>
            <div class="next-admin-usage__tl-more-body">
              <h4 class="next-admin-usage__tl-sub">标识</h4>
              <dl class="next-admin-usage__tl-meta">
                <div class="next-admin-usage__tl-meta-row">
                  <dt>上游请求 ID</dt>
                  <dd class="ui-mono">{{ timeline.upstreamRequestId ?? '—' }}</dd>
                </div>
                <div class="next-admin-usage__tl-meta-row">
                  <dt>线协议</dt>
                  <dd class="ui-mono">{{ timeline.wireProtocol ?? '—' }}</dd>
                </div>
                <div class="next-admin-usage__tl-meta-row">
                  <dt>传输</dt>
                  <dd>{{ timeline.streaming ? '流式' : '非流式' }}</dd>
                </div>
              </dl>

              <h4 class="next-admin-usage__tl-sub">归属链</h4>
              <dl class="next-admin-usage__tl-meta">
                <div class="next-admin-usage__tl-meta-row">
                  <dt>用户</dt>
                  <dd class="ui-mono" :title="timeline.attribution?.userId">
                    {{ shortId(timeline.attribution?.userId) }}
                  </dd>
                </div>
                <div class="next-admin-usage__tl-meta-row">
                  <dt>项目</dt>
                  <dd class="ui-mono" :title="timeline.attribution?.projectId">
                    {{ shortId(timeline.attribution?.projectId) }}
                  </dd>
                </div>
                <div class="next-admin-usage__tl-meta-row">
                  <dt>虚拟密钥</dt>
                  <dd class="ui-mono" :title="timeline.attribution?.virtualKeyId">
                    {{ shortId(timeline.attribution?.virtualKeyId) }}
                  </dd>
                </div>
                <div class="next-admin-usage__tl-meta-row">
                  <dt>供应商</dt>
                  <dd class="ui-mono" :title="timeline.attribution?.providerId">
                    {{ shortId(timeline.attribution?.providerId) }}
                  </dd>
                </div>
                <div class="next-admin-usage__tl-meta-row">
                  <dt>供应商产品</dt>
                  <dd class="ui-mono" :title="timeline.attribution?.providerProductId">
                    {{ shortId(timeline.attribution?.providerProductId) }}
                  </dd>
                </div>
                <div class="next-admin-usage__tl-meta-row">
                  <dt>凭证</dt>
                  <dd class="ui-mono" :title="timeline.attribution?.credentialId">
                    {{ shortId(timeline.attribution?.credentialId) }}
                  </dd>
                </div>
              </dl>

              <h4 class="next-admin-usage__tl-sub">Token</h4>
              <dl class="next-admin-usage__tl-meta">
                <div class="next-admin-usage__tl-meta-row">
                  <dt>输入</dt>
                  <dd class="ui-num">{{ formatNumber(timeline.tokens?.input) }}</dd>
                </div>
                <div class="next-admin-usage__tl-meta-row">
                  <dt>输出</dt>
                  <dd class="ui-num">{{ formatNumber(timeline.tokens?.output) }}</dd>
                </div>
                <div class="next-admin-usage__tl-meta-row">
                  <dt>缓存读</dt>
                  <dd class="ui-num">{{ formatNumber(timeline.tokens?.cacheRead) }}</dd>
                </div>
                <div class="next-admin-usage__tl-meta-row">
                  <dt>缓存写</dt>
                  <dd class="ui-num">{{ formatNumber(timeline.tokens?.cacheCreation) }}</dd>
                </div>
              </dl>
            </div>
          </details>

          <p class="next-admin-usage__tl-footnote">仅元数据——请求与响应正文永不落库。</p>
        </template>
      </div>
    </UiDrawer>
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

/* ---- Hero 总览卡 (#758) ---- */
.next-admin-usage__hero {
  margin-bottom: var(--ui-space-5);
}

.next-admin-usage__hero-main {
  display: flex;
  align-items: center;
  gap: var(--ui-space-4);
  padding: var(--ui-space-5) var(--ui-space-6) var(--ui-space-4);
}

.next-admin-usage__hero-chip {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 48px;
  height: 48px;
  border-radius: var(--ui-radius-panel);
  background: var(--ui-info-bg);
  color: var(--ui-info-fg);
}

.next-admin-usage__hero-chip svg {
  width: 22px;
  height: 22px;
}

.next-admin-usage__hero-number {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.next-admin-usage__hero-label {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
}

.next-admin-usage__hero-value {
  font-size: 32px;
  font-weight: var(--ui-weight-semibold);
  letter-spacing: -0.02em;
  line-height: var(--ui-line-height-lg);
  color: var(--ui-foreground);
  white-space: nowrap;
}

.next-admin-usage__hero-cn {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-faint);
}

.next-admin-usage__hero-aside {
  display: flex;
  align-items: center;
  gap: var(--ui-space-8);
  margin-left: auto;
}

.next-admin-usage__hero-mini {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.next-admin-usage__hero-mini-label {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
}

.next-admin-usage__hero-mini-value {
  font-size: var(--ui-font-size-xl);
  font-weight: var(--ui-weight-semibold);
  color: var(--ui-foreground);
}

.next-admin-usage__hero-mini-sub {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-faint);
  white-space: nowrap;
}

.next-admin-usage__hero-subs {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--ui-space-8);
  padding: var(--ui-space-3) var(--ui-space-6) var(--ui-space-4);
  border-top: 1px solid var(--ui-border-muted);
}

.next-admin-usage__hero-sub {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.next-admin-usage__hero-sub-label {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
}

.next-admin-usage__hero-sub-value {
  font-size: var(--ui-font-size-lg);
  font-weight: var(--ui-weight-medium);
  color: var(--ui-foreground);
}

.next-admin-usage__hero-sub--rate {
  min-width: 180px;
}

.next-admin-usage__hero-bar {
  margin-top: 2px;
  height: 6px;
  border-radius: var(--ui-radius-pill);
  background: var(--ui-muted);
  overflow: hidden;
}

.next-admin-usage__hero-bar-fill {
  display: block;
  height: 100%;
  border-radius: var(--ui-radius-pill);
  background: var(--ui-success-fg);
}

/* ---- report tabs (#758) ---- */
.next-admin-usage__report {
  margin-bottom: var(--ui-space-5);
}

.next-admin-usage__report-head {
  gap: var(--ui-space-4);
}

.next-admin-usage__tabs {
  display: flex;
  align-items: center;
  gap: var(--ui-space-5);
}

.next-admin-usage__tab {
  position: relative;
  padding: 4px 0;
  border: none;
  background: none;
  font: inherit;
  font-size: var(--ui-font-size-base);
  color: var(--ui-foreground-secondary);
  cursor: pointer;
}

.next-admin-usage__tab:hover {
  color: var(--ui-foreground);
}

.next-admin-usage__tab--on {
  color: var(--ui-primary-text);
  font-weight: var(--ui-weight-semibold);
}

.next-admin-usage__tab--on::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  bottom: -8px;
  height: 2px;
  border-radius: 1px;
  background: var(--ui-primary);
}

.next-admin-usage__tab:focus-visible {
  outline: none;
  border-radius: var(--ui-radius-control);
  box-shadow: var(--ui-shadow-focus);
}

.next-admin-usage__report-actions {
  display: flex;
  align-items: center;
  gap: var(--ui-space-3);
}

/* ---- records cells (#758) ---- */
.next-admin-usage__provider {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.next-admin-usage__cell-sub {
  display: block;
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-faint);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.next-admin-usage__protocol {
  color: var(--ui-foreground-secondary);
}

.next-admin-usage__unpriced {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-warning-fg);
  cursor: help;
}

.next-admin-usage__pager-jump {
  display: inline-flex;
  align-items: center;
  gap: var(--ui-space-2);
  font-size: var(--ui-font-size-sm);
  color: var(--ui-foreground-secondary);
}

.next-admin-usage__page-input {
  width: 56px;
  height: var(--ui-control-height);
  padding: 0 var(--ui-space-2);
  border: 1px solid var(--ui-input-border);
  border-radius: var(--ui-radius-control);
  font: inherit;
  font-size: var(--ui-font-size-sm);
  text-align: center;
  color: var(--ui-foreground);
  background: var(--ui-card);
}

.next-admin-usage__page-input:focus-visible {
  outline: none;
  border-color: var(--ui-primary);
  box-shadow: var(--ui-shadow-focus);
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

/* ---- #707 per-call timeline drawer (three information tiers) ---- */

.next-admin-usage__tl {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-4);
}

.next-admin-usage__tl-reqid {
  margin: 0;
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-faint);
  word-break: break-all;
}

.next-admin-usage__tl-loading {
  margin: 0;
  color: var(--ui-foreground-secondary);
}

.next-admin-usage__tl-note {
  padding: var(--ui-space-4);
  border: 1px solid var(--ui-border);
  border-radius: var(--ui-radius-panel);
  background: var(--ui-muted);
}

.next-admin-usage__tl-note-title {
  margin: 0;
  font-weight: var(--ui-weight-semibold);
}

.next-admin-usage__tl-note-body {
  margin: var(--ui-space-2) 0 0;
  font-size: var(--ui-font-size-sm);
  color: var(--ui-foreground-secondary);
  line-height: var(--ui-line-height-base);
}

/* tier 1 — terminal state, total duration, where it stopped */
.next-admin-usage__tl-hero {
  padding: var(--ui-space-4);
  border: 1px solid var(--ui-border);
  border-radius: var(--ui-radius-panel);
  background: var(--ui-muted);
}

.next-admin-usage__tl-hero-top {
  display: flex;
  align-items: baseline;
  gap: var(--ui-space-3);
  flex-wrap: wrap;
}

.next-admin-usage__tl-duration {
  font-size: 24px;
  font-weight: var(--ui-weight-semibold);
  color: var(--ui-foreground);
  letter-spacing: -0.01em;
}

.next-admin-usage__tl-duration-label {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
}

.next-admin-usage__tl-stalled {
  margin: var(--ui-space-3) 0 0;
  font-size: var(--ui-font-size-sm);
  color: var(--ui-foreground-secondary);
}

.next-admin-usage__tl-phases {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-3);
}

.next-admin-usage__tl-phase {
  display: flex;
  align-items: center;
  gap: var(--ui-space-3);
  font-size: var(--ui-font-size-sm);
}

.next-admin-usage__tl-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--ui-primary);
  flex-shrink: 0;
}

/* a milestone the gateway never recorded — dashed hollow dot, no timestamp */
.next-admin-usage__tl-phase--missing .next-admin-usage__tl-dot {
  background: transparent;
  border: 1px dashed var(--ui-border);
}

.next-admin-usage__tl-phase-label {
  width: 84px;
  color: var(--ui-foreground-secondary);
}

.next-admin-usage__tl-phase--missing .next-admin-usage__tl-phase-label {
  color: var(--ui-foreground-faint);
}

.next-admin-usage__tl-delta {
  color: var(--ui-foreground-secondary);
  font-size: var(--ui-font-size-xs);
}

.next-admin-usage__tl-absent {
  color: var(--ui-foreground-faint);
  font-size: var(--ui-font-size-xs);
}

.next-admin-usage__tl-hint {
  margin: calc(-1 * var(--ui-space-2)) 0 0;
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-faint);
}

/* tier 2 — TTFB, retries, partial response, HTTP status */
.next-admin-usage__tl-metrics {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--ui-space-3) var(--ui-space-4);
  margin: 0;
  padding: var(--ui-space-4);
  border: 1px solid var(--ui-border);
  border-radius: var(--ui-radius-panel);
}

.next-admin-usage__tl-metric {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--ui-space-3);
}

.next-admin-usage__tl-metric dt {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
}

.next-admin-usage__tl-metric dd {
  margin: 0;
  font-weight: var(--ui-weight-medium);
}

/* tier 3 — collapsed by default: attribution chain, ids, token split */
.next-admin-usage__tl-more {
  border: 1px solid var(--ui-border);
  border-radius: var(--ui-radius-panel);
}

.next-admin-usage__tl-more > summary {
  padding: var(--ui-space-3) var(--ui-space-4);
  font-size: var(--ui-font-size-sm);
  color: var(--ui-foreground-secondary);
  cursor: pointer;
}

.next-admin-usage__tl-more-body {
  padding: 0 var(--ui-space-4) var(--ui-space-4);
}

.next-admin-usage__tl-sub {
  margin: var(--ui-space-3) 0 var(--ui-space-2);
  font-size: var(--ui-font-size-xs);
  font-weight: var(--ui-weight-semibold);
  color: var(--ui-foreground-faint);
}

.next-admin-usage__tl-meta {
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-2);
}

.next-admin-usage__tl-meta-row {
  display: flex;
  gap: var(--ui-space-3);
  font-size: var(--ui-font-size-sm);
}

.next-admin-usage__tl-meta-row dt {
  width: 96px;
  flex-shrink: 0;
  color: var(--ui-foreground-secondary);
}

.next-admin-usage__tl-meta-row dd {
  margin: 0;
  word-break: break-all;
}

.next-admin-usage__tl-footnote {
  margin: 0;
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-faint);
}
</style>
