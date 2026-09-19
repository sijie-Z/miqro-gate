<script setup lang="ts">
/**
 * NextRoiView — /app/roi v2 admin page (U2 platform batch).
 * Behaviour parity with the legacy cache-ROI report, aligned with the
 * Tencent AI-gateway "cache hit statistics" reference (#661): calendar-aware
 * windows with an explicit range readout, a stat strip whose cards carry
 * sub-metric lines, and a hit-composition panel fed by the RoiTotals
 * breakdown (upstream / coalesced / L1 / L2). Day table and CSV export kept.
 */
import { computed, onMounted, ref } from 'vue';
import * as api from '@/api';
import { UiButton, UiDonut, UiTable, UiTooltip } from '@/ui';
import { costGapNote } from '@/lib/usage-pricing';
import { csvCell } from '@/utils/csv';
import type { RoiReportView, UsageGroup } from '@/types/generated-api';

const report = ref<RoiReportView | null>(null);
const loading = ref(true);
const loadError = ref('');

type WindowKey = 'today' | 'week' | 'month' | '7' | '30' | '93';

const WINDOWS: { key: WindowKey; label: string }[] = [
  { key: 'today', label: '今天' },
  { key: 'week', label: '本周' },
  { key: 'month', label: '本月' },
  { key: '7', label: '近 7 天' },
  { key: '30', label: '近 30 天' },
  { key: '93', label: '近 93 天' },
];

const windowKey = ref<WindowKey>('30');

/** Calendar windows start at local midnight (week on Monday, month on the 1st). */
function windowRange(key: WindowKey): { from: Date; to: Date } {
  const to = new Date();
  if (key === 'today' || key === 'week' || key === 'month') {
    const from = new Date(to);
    from.setHours(0, 0, 0, 0);
    if (key === 'week') {
      from.setDate(from.getDate() - ((from.getDay() + 6) % 7));
    } else if (key === 'month') {
      from.setDate(1);
    }
    return { from, to };
  }
  return { from: new Date(to.getTime() - Number(key) * 24 * 3600 * 1000), to };
}

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

function pad(n: number): string {
  return String(n).padStart(2, '0');
}

function fmtTime(dt: Date): string {
  return `${dt.getFullYear()}-${pad(dt.getMonth() + 1)}-${pad(dt.getDate())} ${pad(dt.getHours())}:${pad(dt.getMinutes())}`;
}

/** Explicit query window shown next to the chips (Tencent-style readout). */
const rangeLabel = computed(() => {
  const from = report.value?.from ? new Date(report.value.from) : null;
  const to = report.value?.to ? new Date(report.value.to) : null;
  return from && to ? `${fmtTime(from)} ~ ${fmtTime(to)}` : '';
});

interface TotalNumbers {
  upstream: number;
  coalesced: number;
  l1: number;
  l2: number;
  served: number;
}

const totalsOf = computed<TotalNumbers>(() => {
  const t = report.value?.totals;
  const upstream = Number(t?.upstreamRequests ?? 0);
  const coalesced = Number(t?.coalescedRequests ?? 0);
  const l1 = Number(t?.l1Hits ?? 0);
  const l2 = Number(t?.l2Hits ?? 0);
  return { upstream, coalesced, l1, l2, served: upstream + coalesced + l1 + l2 };
});

/** Tencent-style stat strip: big value plus a sub-metric line per card. */
interface RoiCard {
  label: string;
  value: string;
  sub: { k: string; v: string }[];
  accent?: boolean;
  /** Set when a figure on this card is known to fall short of the whole (#801). */
  caveat?: string;
}

const cards = computed<RoiCard[]>(() => {
  const t = report.value?.totals;
  const { upstream, coalesced, l1, l2, served } = totalsOf.value;
  // The money below is short of the whole whenever a token dimension had no price.
  const costCaveat = costGapNote(t) ?? undefined;
  // A 0/0 discount is undefined, not zero: the API sends null for exactly this case.
  const discount = t?.savedPct == null ? '—' : pct(Number(t.savedPct));
  const rate = (n: number) => pct(served ? (n / served) * 100 : 0);
  return [
    {
      label: '总请求次数',
      value: String(served),
      sub: [
        { k: '上游', v: String(upstream) },
        { k: '合并', v: String(coalesced) },
      ],
    },
    { label: 'L1 命中', value: String(l1), sub: [{ k: '命中率', v: rate(l1) }] },
    { label: 'L2 命中', value: String(l2), sub: [{ k: '命中率', v: rate(l2) }] },
    {
      label: '网关缓存命中率',
      value: pct(Number(t?.hitRatePct ?? 0)),
      sub: [{ k: 'L1+L2 命中', v: String(l1 + l2) }],
    },
    {
      label: '缓存节省',
      value: money(Number(t?.savedCost ?? 0)),
      caveat: costCaveat,
      sub: [
        { k: '上游实付', v: money(Number(t?.paidCost ?? 0)) },
        { k: '等效折扣', v: discount },
      ],
      accent: true,
    },
  ];
});

/** Hit composition (the data-backed analogue of Tencent's similarity
 *  distribution): how every served request resolved, largest share first. */
const composition = computed(() => {
  const { upstream, coalesced, l1, l2, served } = totalsOf.value;
  const total = Math.max(1, served);
  return [
    { label: 'L1 命中', value: l1, color: '#389e0d' },
    { label: 'L2 命中', value: l2, color: '#0960bd' },
    { label: '合并命中', value: coalesced, color: '#d48806' },
    { label: '上游未命中', value: upstream, color: '#bfbfbf' },
  ]
    .map((row) => ({ ...row, pct: (row.value / total) * 100 }))
    .sort((a, b) => b.value - a.value);
});

/** Savings vs paid split for the selected window (donut centre = discount). */
const savingSegments = computed(() => {
  const saved = Number(report.value?.totals?.savedCost ?? 0);
  const paid = Number(report.value?.totals?.paidCost ?? 0);
  const rows = [];
  if (saved > 0) rows.push({ label: '缓存节省', value: saved, color: '#389e0d' });
  if (paid > 0) rows.push({ label: '上游实付', value: paid, color: '#0960bd' });
  return rows;
});

function asDay(row: unknown): NonNullable<RoiReportView['byDay']>[number] {
  return row as NonNullable<RoiReportView['byDay']>[number];
}

// ---- #863 配置概览 tab: per-Key cache activity (groupBy=VIRTUAL_KEY) --------
// Tencent splits cache into 配置 + 统计 pages; we keep one page with two tabs.
// The per-key numbers come from the existing admin usage summary — the whitelist
// has supported VIRTUAL_KEY grouping all along, so no new endpoint is needed.
type PageTab = 'stats' | 'config';

interface KeyRow {
  key: string;
  label: string;
  served: number;
  hits: number;
  hitRatePct: number;
  paidCost: number;
  savedCost: number;
}

const pageTab = ref<PageTab>('stats');
const keyRows = ref<UsageGroup[]>([]);
const keyError = ref('');

const keyColumns = [
  { key: 'label', title: '密钥', minWidth: '200px' },
  { key: 'served', title: '总请求', width: '110px', align: 'right' as const, sortable: true },
  { key: 'hits', title: '缓存命中', width: '120px', align: 'right' as const, sortable: true },
  { key: 'hitRatePct', title: '命中率', width: '110px', align: 'right' as const },
  { key: 'paidCost', title: '上游实付', width: '130px', align: 'right' as const, sortable: true },
  { key: 'savedCost', title: '缓存节省', width: '130px', align: 'right' as const, sortable: true },
];

/** Flatten the grouped summary into table rows; busiest keys first. */
const keyTableRows = computed<KeyRow[]>(() =>
  keyRows.value
    .map((g) => {
      const upstream = Number(g.requests?.upstream ?? 0);
      const coalesced = Number(g.requests?.coalesced ?? 0);
      const l1 = Number(g.requests?.l1Hit ?? 0);
      const l2 = Number(g.requests?.l2Hit ?? 0);
      const served = upstream + coalesced + l1 + l2;
      const hits = l1 + l2;
      return {
        key: g.groupKey ?? g.label ?? '',
        label: g.label ?? g.groupKey ?? '—',
        served,
        hits,
        hitRatePct: served ? (hits / served) * 100 : 0,
        paidCost: Number(g.cost?.upstreamPaid ?? 0),
        savedCost: Number(g.cost?.savedByGatewayCache ?? 0),
      };
    })
    .sort((a, b) => b.served - a.served),
);

function asKeyRow(row: unknown): KeyRow {
  return row as KeyRow;
}

// #440: request-sequence guard — a slow window load must not land after the
// user switched windows (numbers must match the highlighted range).
let loadRequestSeq = 0;

async function load() {
  const seq = ++loadRequestSeq;
  loading.value = true;
  loadError.value = '';
  keyError.value = '';
  const { from, to } = windowRange(windowKey.value);

  // The per-key aggregate is auxiliary: its failure must never take the stats
  // tab down with it (same degradation contract as the list-metadata batch).
  void api
    .adminUsageSummary({ groupBy: 'VIRTUAL_KEY', from: from.toISOString(), to: to.toISOString() })
    .then((summary) => {
      if (seq !== loadRequestSeq) return;
      keyRows.value = summary.groups ?? [];
    })
    .catch((err) => {
      if (seq === loadRequestSeq) {
        keyError.value = err instanceof Error ? err.message : '加载密钥维度失败';
      }
    });

  try {
    const result = await api.getRoiReport(from.toISOString(), to.toISOString());
    if (seq !== loadRequestSeq) {
      return; // a newer window won — this response is stale
    }
    report.value = result;
  } catch (err) {
    if (seq === loadRequestSeq) {
      loadError.value = err instanceof Error ? err.message : '加载失败';
    }
  } finally {
    if (seq === loadRequestSeq) {
      loading.value = false;
    }
  }
}

function exportCsv() {
  if (!report.value) return;
  const rows = (report.value.byDay ?? []).map((d) =>
    [
      d.date,
      d.upstreamRequests,
      d.hitRequests,
      (d.hitRatePct ?? 0).toFixed(2),
      (d.paidCost ?? 0).toFixed(4),
      (d.savedCost ?? 0).toFixed(4),
    ]
      .map(csvCell)
      .join(','),
  );
  const header = 'date,upstreamRequests,hitRequests,hitRatePct,paidCost,savedCost';
  const blob = new Blob([`﻿${header}\n${rows.join('\n')}`], { type: 'text/csv;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `cache-roi-${(report.value.from ?? '').slice(0, 10)}_${(report.value.to ?? '').slice(0, 10)}.csv`;
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
          v-if="pageTab === 'stats'"
          variant="secondary"
          data-testid="roi-export"
          :disabled="!report"
          @click="exportCsv"
        >
          导出 CSV
        </UiButton>
      </div>
    </header>

    <div class="next-roi__tabs" role="tablist" aria-label="缓存视图">
      <button
        type="button"
        role="tab"
        class="next-roi__tab"
        :class="{ 'next-roi__tab--on': pageTab === 'stats' }"
        :aria-selected="pageTab === 'stats'"
        data-testid="roi-tab-stats"
        @click="pageTab = 'stats'"
      >
        统计
      </button>
      <button
        type="button"
        role="tab"
        class="next-roi__tab"
        :class="{ 'next-roi__tab--on': pageTab === 'config' }"
        :aria-selected="pageTab === 'config'"
        data-testid="roi-tab-config"
        @click="pageTab = 'config'"
      >
        配置概览
      </button>
    </div>

    <div class="next-roi__toolbar">
      <div class="next-roi__segmented" role="tablist" aria-label="窗口">
        <button
          v-for="w in WINDOWS"
          :key="w.key"
          type="button"
          class="next-roi__seg"
          :class="{ 'next-roi__seg--on': windowKey === w.key }"
          :data-testid="`roi-window-${w.key}`"
          @click="
            windowKey = w.key;
            load();
          "
        >
          {{ w.label }}
        </button>
      </div>
      <div class="next-roi__toolbar-right">
        <span v-if="rangeLabel" class="next-roi__range ui-num" data-testid="roi-range">
          {{ rangeLabel }}
        </span>
        <UiButton variant="ghost" size="sm" :loading="loading" @click="load">刷新</UiButton>
      </div>
    </div>

    <div v-if="loadError" class="ui-alert ui-alert--error">{{ loadError }}</div>

    <!-- #863 配置概览：按密钥的缓存表现（groupBy=VIRTUAL_KEY，现有端点） -->
    <section v-if="pageTab === 'config'" class="ui-panel" data-testid="roi-config">
      <div class="ui-panel-toolbar">
        <span class="ui-panel-sub">按虚拟密钥的缓存表现（总请求多的在前）· 当前窗口</span>
        <router-link class="ui-link-action" to="/app/keys">去「我的密钥」管理缓存开关</router-link>
      </div>
      <div v-if="keyError" class="ui-alert ui-alert--error" data-testid="roi-config-error">
        {{ keyError }}
      </div>
      <UiTable
        :columns="keyColumns"
        :data="keyTableRows"
        :loading="loading"
        row-key="key"
        empty-title="该窗口内没有按密钥的用量记录"
        empty-description="缓存为按 Key 显式开启：在「我的密钥」为 Key 打开缓存开关后，客户端请求再加 X-MiqroKey-Cacheable: 1 头即生效。"
        data-testid="roi-key-table"
      >
        <template #label="{ row }">
          <span class="next-roi__key-label" :title="asKeyRow(row).label">{{
            asKeyRow(row).label
          }}</span>
        </template>
        <template #hits="{ row }">{{ asKeyRow(row).hits }}</template>
        <template #hitRatePct="{ row }">{{ pct(asKeyRow(row).hitRatePct) }}</template>
        <template #paidCost="{ row }">{{ money(asKeyRow(row).paidCost) }}</template>
        <template #savedCost="{ row }">{{ money(asKeyRow(row).savedCost) }}</template>
      </UiTable>
    </section>

    <div v-if="pageTab === 'stats' && report" class="next-roi__cards" data-testid="roi-report">
      <div v-for="card in cards" :key="card.label" class="ui-panel next-roi__card">
        <span class="next-roi__label">{{ card.label }}</span>
        <span class="next-roi__value ui-num" :class="{ 'next-roi__value--accent': card.accent }"
          >{{ card.value
          }}<UiTooltip v-if="card.caveat" :text="card.caveat"
            ><span class="next-roi__caveat" data-testid="roi-cost-caveat">未定价</span></UiTooltip
          ></span
        >
        <span class="next-roi__sub">
          <template v-for="(part, i) in card.sub" :key="part.k">
            <span v-if="i > 0" class="next-roi__sub-sep" aria-hidden="true">·</span>
            <span>{{ part.k }}</span>
            <span class="ui-num">{{ part.v }}</span>
          </template>
        </span>
      </div>
    </div>

    <div v-if="pageTab === 'stats'" class="next-roi__panels">
      <section v-if="savingSegments.length" class="ui-panel" data-testid="roi-saving-dist">
        <div class="ui-panel-head">
          <div>
            <h2 class="ui-panel-title">缓存收益构成</h2>
            <span class="ui-panel-sub">缓存节省 vs 上游实付 · 当前窗口</span>
          </div>
        </div>
        <div class="ui-panel-body next-roi__summary-body">
          <UiDonut
            :segments="savingSegments"
            :center-text="pct(report?.totals?.savedPct ?? 0)"
            data-testid="roi-saving-donut"
          />
          <div class="ui-legend">
            <div v-for="seg in savingSegments" :key="seg.label" class="ui-legend-row">
              <span class="ui-legend-dot" :style="{ background: seg.color }" />
              <span class="ui-legend-label">{{ seg.label }}</span>
              <span class="ui-legend-pct ui-num"
                >{{
                  Math.round(
                    (seg.value /
                      Math.max(
                        0.0001,
                        savingSegments.reduce((x, y) => x + y.value, 0),
                      )) *
                      100,
                  )
                }}%</span
              >
              <span class="ui-legend-value ui-num">{{ money(seg.value) }}</span>
            </div>
          </div>
        </div>
      </section>

      <section v-if="report" class="ui-panel" data-testid="roi-composition">
        <div class="ui-panel-head">
          <div>
            <h2 class="ui-panel-title">缓存命中构成</h2>
            <span class="ui-panel-sub">按服务请求总数计算 · 当前窗口</span>
          </div>
        </div>
        <div class="ui-panel-body next-roi__comp">
          <div v-for="row in composition" :key="row.label" class="next-roi__comp-row">
            <span class="next-roi__comp-label">{{ row.label }}</span>
            <span class="next-roi__comp-track" aria-hidden="true">
              <span
                class="next-roi__comp-fill"
                :style="{ width: `${row.pct.toFixed(2)}%`, background: row.color }"
              />
            </span>
            <span class="next-roi__comp-count ui-num">{{ row.value }}</span>
            <span class="next-roi__comp-pct ui-num">{{ pct(row.pct) }}</span>
          </div>
        </div>
      </section>
    </div>

    <section v-if="pageTab === 'stats'" class="ui-panel">
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
          {{ asDay(row).upstreamRequests }} /
          {{ asDay(row).hitRequests }}
        </template>
        <template #hitRatePct="{ row }">{{ pct(asDay(row).hitRatePct ?? 0) }}</template>
        <template #paidCost="{ row }">{{ money(asDay(row).paidCost ?? 0) }}</template>
        <template #savedCost="{ row }">{{ money(asDay(row).savedCost ?? 0) }}</template>
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

/* Page tabs (统计 / 配置概览) — antd underline style, mirroring the Tencent
   cache page's 配置 / 统计 split (#863). */
.next-roi__tabs {
  display: flex;
  gap: var(--ui-space-6);
  border-bottom: 1px solid var(--ui-border);
  margin-bottom: var(--ui-space-4);
}

.next-roi__tab {
  position: relative;
  padding: 0 2px 10px;
  border: none;
  background: none;
  font: inherit;
  font-size: var(--ui-font-size-base);
  color: var(--ui-foreground-secondary);
  cursor: pointer;
  transition: color var(--ui-ease);
}

.next-roi__tab:hover {
  color: var(--ui-primary-text);
}

.next-roi__tab--on {
  color: var(--ui-primary-text);
  font-weight: var(--ui-weight-medium);
}

.next-roi__tab--on::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  bottom: -1px;
  height: 2px;
  border-radius: 2px 2px 0 0;
  background: var(--ui-primary);
}

.next-roi__key-label {
  display: inline-block;
  max-width: 320px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
}

.next-roi__toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ui-space-3);
  flex-wrap: wrap;
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
  transition:
    background-color var(--ui-ease),
    color var(--ui-ease);
}

.next-roi__seg:hover {
  color: var(--ui-foreground);
}

.next-roi__seg--on {
  background: var(--ui-card);
  border: 1px solid var(--ui-border);
  color: var(--ui-primary-text);
  font-weight: var(--ui-weight-semibold);
}

.next-roi__toolbar-right {
  display: inline-flex;
  align-items: center;
  gap: var(--ui-space-3);
}

.next-roi__range {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-faint);
}

.next-roi__panels {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--ui-space-4);
  margin-bottom: var(--ui-space-5);
}

@media (max-width: 1100px) {
  .next-roi__panels {
    grid-template-columns: minmax(0, 1fr);
  }
}

.next-roi__summary-body {
  display: flex;
  align-items: center;
  gap: var(--ui-space-6);
  flex-wrap: wrap;
}

.next-roi__comp {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-4);
}

.next-roi__comp-row {
  display: grid;
  grid-template-columns: 88px minmax(0, 1fr) 56px 64px;
  align-items: center;
  gap: var(--ui-space-3);
  font-size: var(--ui-font-size-sm);
}

.next-roi__comp-label {
  color: var(--ui-foreground-secondary);
}

.next-roi__comp-track {
  height: 8px;
  border-radius: var(--ui-radius-pill);
  background: var(--ui-muted);
  overflow: hidden;
}

.next-roi__comp-fill {
  display: block;
  height: 100%;
  border-radius: var(--ui-radius-pill);
}

.next-roi__comp-count {
  text-align: right;
  color: var(--ui-foreground);
}

.next-roi__comp-pct {
  text-align: right;
  color: var(--ui-foreground-secondary);
}

.next-roi__cards {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: var(--ui-space-4);
  margin-bottom: var(--ui-space-5);
}

@media (max-width: 1280px) {
  .next-roi__cards {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 900px) {
  .next-roi__cards {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

.next-roi__card {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-1);
  padding: var(--ui-space-4) var(--ui-space-5);
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

.next-roi__caveat {
  font-size: var(--ui-font-size-xs);
  font-weight: var(--ui-weight-medium);
  color: var(--ui-warning-fg);
  white-space: nowrap;
  margin-left: var(--ui-space-1);
}

.next-roi__sub {
  display: inline-flex;
  align-items: baseline;
  gap: 4px;
  flex-wrap: wrap;
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-faint);
}

.next-roi__sub-sep {
  color: var(--ui-border);
}
</style>
