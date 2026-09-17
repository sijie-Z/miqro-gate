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
import { UiButton, UiDrawer, UiInput, UiSelect, UiStatusBadge, UiTable, UiTrendChart } from '@/ui';
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
