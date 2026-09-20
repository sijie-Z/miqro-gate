<script setup lang="ts">
/**
 * NextOverviewView — /app-new/overview pilot page (UI U0/U1).
 * Workbench-style layout modelled on v2.vben.pro /dashboard/workbench:
 * greeting header with inline stats, a 69/31 two-column body (key tiles +
 * usage bars on the left; quick-nav tile grid, cost donut and the admin
 * quota ledger on the right). Behaviour parity with the legacy OverviewView:
 * same APIs, same aggregates, rendering only.
 */
import { computed, onMounted, ref } from 'vue';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import { useAuthStore } from '@/stores/auth';
import {
  AppIcon,
  ChartBarIcon,
  FilePasteIcon,
  LayersIcon,
  LockOnIcon,
  MoneyIcon,
  SecuredIcon,
  NotificationIcon,
  ToolsIcon,
  UserIcon,
} from 'tdesign-icons-vue-next';
import { UiButton, UiDonut, UiStatusBadge, UiTooltip } from '@/ui';
import { CHART_OTHER_COLOR, CHART_PALETTE } from '@/lib/chart-palette';
import { costGapNote, type PricingGapFields } from '@/lib/usage-pricing';
import type {
  ModelApprovalView,
  SubscriptionView,
  UsageGroup,
  VirtualKeyView,
} from '@/types/generated-api';
import { actionLabel } from '@/utils/audit-labels';
import { localDayKey } from '@/utils/datetime';

const auth = useAuthStore();

const keys = ref<VirtualKeyView[]>([]);
const usageGroups = ref<UsageGroup[]>([]);
const subscriptions = ref<SubscriptionView[]>([]);
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');

const isAdmin = computed(() => auth.user?.role === 'SYSTEM_ADMIN');

const userName = computed(() => auth.user?.displayName || auth.user?.username || '');

const userInitial = computed(() => (auth.user?.username ?? '?').slice(0, 1).toUpperCase());

const dateLabel = computed(() =>
  new Date().toLocaleDateString('zh-CN', { month: 'long', day: 'numeric', weekday: 'long' }),
);

interface StatCard {
  label: string;
  value: string;
  prefix?: string;
  hint: string;
  icon: unknown;
  tone: string;
  /** Set when the displayed figure is known to fall short of the whole (#801). */
  caveat?: string;
}

/** The server's own aggregate, which carries the pricing status the UI must honour. */
type OverviewTotals = PricingGapFields & {
  cost?: { upstreamPaid?: number | string | null } | null;
};

// The server's totals, not a client-side sum: only they carry `pricingStatus`,
// and they are the authoritative figure the groups merely partition.
const totals = ref<OverviewTotals | null>(null);

const stats = computed<StatCard[]>(() => {
  const active = keys.value.filter((k) => k.status === 'ACTIVE').length;
  // #1104: a failed load knows no counts. "0 本月请求 / ¥0.00 本月成本" would be a
  // claim about the tenant, not about this request — and a money figure is the
  // one a reader believes first. The tables have answered "—" since #1065; the
  // cards answer the same.
  if (loadError.value) {
    return [
      { label: '虚拟密钥', value: '—', hint: '加载失败', icon: LockOnIcon, tone: 'blue' },
      { label: '本月请求', value: '—', hint: '加载失败', icon: ChartBarIcon, tone: 'green' },
      { label: '本月 Token', value: '—', hint: '加载失败', icon: LayersIcon, tone: 'cyan' },
      { label: '本月成本', value: '—', hint: '加载失败', icon: MoneyIcon, tone: 'gold' },
    ];
  }
  const totalTokens = usageGroups.value.reduce(
    (sum, g) => sum + (g.tokens?.input ?? 0) + (g.tokens?.output ?? 0),
    0,
  );
  const totalRequests = usageGroups.value.reduce((sum, g) => sum + (g.requests?.upstream ?? 0), 0);
  const costCaveat = costGapNote(totals.value);
  return [
    {
      label: '虚拟密钥',
      value: String(keys.value.length),
      hint: `${active} 个可用`,
      icon: LockOnIcon,
      tone: 'blue',
    },
    {
      label: '本月请求',
      value: formatCount(totalRequests),
      hint: '经网关的请求数',
      icon: ChartBarIcon,
      tone: 'green',
    },
    {
      label: '本月 Token',
      value: formatCount(totalTokens),
      hint: '输入+输出',
      icon: LayersIcon,
      tone: 'cyan',
    },
    {
      label: '本月成本',
      value: Number(totals.value?.cost?.upstreamPaid ?? 0).toFixed(2),
      prefix: '¥',
      // A figure that is missing unpriced usage is not a total; say so where it
      // is shown rather than letting it read as one (#801).
      caveat: costCaveat ?? undefined,
      hint: costCaveat ?? '按价格快照估算',
      icon: MoneyIcon,
      tone: 'gold',
    },
  ];
});

const usageBars = computed(() => {
  const ranked = usageGroups.value
    .map((g) => ({
      label: g.label,
      value: (g.tokens?.input ?? 0) + (g.tokens?.output ?? 0),
    }))
    .sort((a, b) => b.value - a.value)
    .slice(0, 8);
  const max = Math.max(...ranked.map((r) => r.value), 1);
  return ranked.map((r, index) => ({
    ...r,
    width: `${Math.max(4, (r.value / max) * 100)}%`,
    alpha: Math.max(0.35, 0.9 - index * 0.08),
  }));
});

const costGroups = computed(() =>
  usageGroups.value
    .map((g) => ({
      label: g.label,
      cost: Number(g.cost?.upstreamPaid ?? 0),
    }))
    // hide sub-cent noise so a ~¥0 total never renders a full-width bar
    .filter((g) => g.cost >= 0.005)
    .sort((a, b) => b.cost - a.cost)
    .slice(0, 8),
);

/**
 * PH43: the window's cost, from the server's totals — the same figure the 本月成本
 * card three panels up shows. `costGroups` above is a *drawing* truncation (top 8
 * after dropping sub-cent rows), so summing it redefined the total the moment a 9th
 * cost-bearing project existed: the same window reported ¥80 here and ¥100 there.
 */
const costTotal = computed(() => Number(totals.value?.cost?.upstreamPaid ?? 0));

// Palette comes from the shared categorical set (#1112): this file used to carry a
// fourth copy of the old list, which still had the unseparated #69c0ff slot.

const donutSegments = computed(() => {
  const total = costTotal.value;
  if (total <= 0) return [];
  const top = costGroups.value.slice(0, CHART_PALETTE.length);
  // PH43: everything the ring does not name — the ranks past the palette, the sub-cent rows the legend
  // hides, and any gap between the drawn groups and the server's total. Deriving it by
  // subtracting keeps the slices summing to the total instead of to the drawing.
  const restCost = Math.max(0, total - top.reduce((sum, g) => sum + g.cost, 0));
  const rows = top.map((g, i) => ({
    label: g.label ?? '—',
    cost: g.cost,
    pct: (g.cost / total) * 100,
    color: CHART_PALETTE[i] ?? CHART_OTHER_COLOR,
  }));
  if (restCost > 0)
    rows.push({
      label: '其他',
      cost: restCost,
      pct: (restCost / total) * 100,
      color: CHART_OTHER_COLOR,
    });
  return rows;
});

/** Quick actions (workbench quick-nav tile grid; admins get reporting entries). */
const quickNav = computed(() =>
  isAdmin.value
    ? [
        { label: '创建虚拟密钥', to: '/app/keys', icon: LockOnIcon, color: '#0960bd' },
        { label: '用量报表', to: '/app/admin-usage', icon: ChartBarIcon, color: '#13c2c2' },
        { label: '成本报表', to: '/app/cost', icon: MoneyIcon, color: '#fa8c16' },
        { label: '账单对账', to: '/app/reconciliations', icon: FilePasteIcon, color: '#8c8c8c' },
        { label: '审计日志', to: '/app/audit', icon: SecuredIcon, color: '#2f9e44' },
        { label: 'Webhook 端点', to: '/app/webhooks', icon: NotificationIcon, color: '#d48806' },
      ]
    : [
        { label: '创建虚拟密钥', to: '/app/keys', icon: LockOnIcon, color: '#0960bd' },
        { label: '我的用量', to: '/app/usage', icon: ChartBarIcon, color: '#13c2c2' },
        { label: '申请新模型', to: '/app/model-approvals', icon: LayersIcon, color: '#fa8c16' },
        { label: '技能库', to: '/app/skills', icon: AppIcon, color: '#2f9e44' },
        { label: '资料', to: '/app/profile', icon: UserIcon, color: '#8c8c8c' },
      ],
);

const recentKeys = computed(() => keys.value.slice(0, 6));

/** Purpose → icon chip tone, mirroring the Vben project-card tiles. */
const PURPOSE_META: Record<string, { icon: unknown; color: string }> = {
  // Bare colored marks (Vben project-tile style): each purpose gets a
  // distinct vivid logo color instead of a tinted chip.
  CLAUDE_CODE: { icon: LockOnIcon, color: '#d97757' },
  CLAUDE_DESKTOP: { icon: UserIcon, color: '#0960bd' },
  CODEX: { icon: ToolsIcon, color: '#10a37f' },
  CUSTOM: { icon: LayersIcon, color: '#8c8c8c' },
};

const PURPOSE_LABELS: Record<string, string> = {
  CLAUDE_CODE: 'Claude Code',
  CLAUDE_DESKTOP: 'Claude Desktop',
  CODEX: 'Codex',
  CUSTOM: '自定义',
};

const STATUS_META: Record<
  string,
  { tone: 'success' | 'warning' | 'danger' | 'neutral'; label: string }
> = {
  ACTIVE: { tone: 'success', label: '可用' },
  ROTATING: { tone: 'warning', label: '轮换中' },
  REVOKED: { tone: 'danger', label: '已吊销' },
  DISABLED: { tone: 'neutral', label: '停用' },
};

function keyStatusMeta(status?: string): {
  tone: 'success' | 'warning' | 'danger' | 'neutral';
  label: string;
} {
  return STATUS_META[status ?? ''] ?? { tone: 'neutral', label: status ?? '—' };
}

function purposeMeta(purpose?: string): { icon: unknown; color: string } {
  return PURPOSE_META[purpose ?? ''] ?? { icon: LayersIcon, color: '#0960bd' };
}

// ---- latest activity feed (Vben workbench 最新动态 parity) ----
interface FeedItem {
  text: string;
  time: string;
  tone: 'success' | 'info' | 'warning';
}

const feed = ref<FeedItem[]>([]);
const feedError = ref('');
const feedLoading = ref(false);

const APPROVAL_STATUS_LABELS: Record<string, string> = {
  PENDING: '待审批',
  APPROVED: '已通过',
  REJECTED: '已驳回',
};

function relativeTime(iso?: string): string {
  if (!iso) return '';
  const diff = Date.now() - new Date(iso).getTime();
  const minutes = Math.floor(diff / 60000);
  if (minutes < 1) return '刚刚';
  if (minutes < 60) return `${minutes} 分钟前`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} 小时前`;
  const days = Math.floor(hours / 24);
  if (days < 30) return `${days} 天前`;
  // PH37: the absolute fallback has to read the same clock as the relative forms
  // above it — a UTC-sliced day dated evening entries a day early.
  return localDayKey(iso);
}

/**
 * Admin: recent audit events; regular users: own keys + model requests.
 *
 * Both inputs are handed in rather than fetched here (#1138). The cards already read
 * the key list, so asking for it again was a second request for one page's worth of
 * data.
 *
 * `approvalsPromise` arrives already started — that is what un-chains it from the
 * summary — but it is *awaited* here, inside this try/catch, on purpose: an approval
 * read that fails must fail this panel and nothing else. Awaiting it in `load()`'s
 * Promise.all instead would make it fatal to the whole page (#1138 review; the issue
 * asks for exactly this degradation).
 *
 * #1160 加载次序不变量：加载中 → 失败 → 空 → 有数据。「还没有动态记录。」只有在
 * 这次读取**成功且确实为空**时才允许出现；失败时面板显示错误与重试（`feedError`），
 * 重试请求在途时显示加载中（`feedLoading`）——清掉错误不等于已经读到空数据。
 */
async function loadFeed(
  keyList: VirtualKeyView[],
  approvalsPromise: Promise<ModelApprovalView[]> | null,
) {
  feedLoading.value = true;
  feedError.value = '';
  try {
    if (isAdmin.value) {
      const events = await api.auditEvents({});
      feed.value = events.slice(0, 6).map((e) => ({
        text: `${e.actorName || (e.actorId ? String(e.actorId).slice(0, 8) : '系统')} ${actionLabel(e.action)}${
          e.targetName ? ` · ${e.targetName}` : ''
        }`,
        time: relativeTime(e.createdAt),
        tone: 'info' as const,
      }));
    } else {
      const approvals = approvalsPromise ? await approvalsPromise : [];
      const items = [
        ...keyList.slice(0, 4).map((k) => ({
          ts: k.createdAt ?? '',
          text: `创建了虚拟密钥 ${k.name}`,
          tone: (k.status === 'ACTIVE' ? 'success' : 'info') as 'success' | 'info',
        })),
        ...approvals.slice(0, 4).map((a) => ({
          ts: a.createdAt ?? '',
          text: `申请模型 ${a.modelId ?? '—'}（${APPROVAL_STATUS_LABELS[a.status ?? ''] ?? a.status ?? '—'}）`,
          tone: (a.status === 'APPROVED'
            ? 'success'
            : a.status === 'REJECTED'
              ? 'warning'
              : 'info') as 'success' | 'info' | 'warning',
        })),
      ]
        .sort((x, y) => String(y.ts).localeCompare(String(x.ts)))
        .slice(0, 6);
      feed.value = items.map((it) => ({ text: it.text, time: relativeTime(it.ts), tone: it.tone }));
    }
  } catch (error) {
    feed.value = [];
    feedError.value = error instanceof ApiError ? error.message : '加载最新动态失败，请稍后重试。';
  } finally {
    feedLoading.value = false;
  }
}

/**
 * #1160: 重试入口复用同一个 loadFeed（admin 重读 auditEvents，普通用户重读自己的
 * 审批）；不重跑主加载——动态面板的失败不牵连已经渲染好的页面。
 */
function retryFeed() {
  return loadFeed(keys.value, isAdmin.value ? null : api.listMyModelApprovals());
}

function purposeLabel(purpose?: string): string {
  return PURPOSE_LABELS[purpose ?? ''] ?? (purpose || '—');
}

function createdLabel(iso?: string): string {
  // PH37: a key created 00:30 local time was labelled with the previous UTC day.
  return localDayKey(iso) || '—';
}

const PLAN_SCOPE_LABELS: Record<string, string> = {
  PERSONAL: '个人套餐',
  TEAM: '团队套餐',
  ENTERPRISE: '企业套餐',
};

function planScopeLabel(scope?: string): string {
  return scope ? (PLAN_SCOPE_LABELS[scope] ?? scope) : '—';
}

const QUOTA_UNIT_LABELS: Record<string, string> = {
  POINTS: '积分',
  TOKENS: 'Token',
  REQUESTS: '请求次数',
  CURRENCY: '金额',
};

function quotaUnitLabel(unit?: string): string {
  return unit ? (QUOTA_UNIT_LABELS[unit] ?? unit) : '—';
}

/** Admin: subscription quota ledger (5h/week/month rolling demo fill). */
const quotaLedger = computed(() =>
  subscriptions.value.map((s) => {
    const usedRatio = s.quotaTotal ? 0.34 : 0; // demo fill until official usage API lands
    return {
      id: s.id,
      name: s.name,
      productName: s.productName,
      planScope: s.planScope,
      status: s.status,
      quotaTotal: s.quotaTotal,
      quotaUnit: s.quotaUnit ?? '—',
      segments: [
        { label: '5 小时', ratio: usedRatio },
        { label: '本周', ratio: usedRatio * 0.8 },
        { label: '本月', ratio: usedRatio * 0.6 },
      ],
    };
  }),
);

function formatCount(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}k`;
  return String(n);
}

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    // Admin home shows the tenant-wide usage; regular users see their own.
    // usageSummary takes positional args, adminUsageSummary takes an object —
    // passing an object to usageSummary broke groupBy parsing on the backend.
    const summaryPromise = isAdmin.value
      ? api.adminUsageSummary({ groupBy: 'project' })
      : api.usageSummary('project');
    // #1138: started here so it rides the same wave as the summary instead of waiting
    // behind it — but awaited in loadFeed, not in the Promise.all below: an approval
    // read that fails may empty that panel and nothing else. Admins do not read
    // approvals at all.
    const approvalsPromise = isAdmin.value ? null : api.listMyModelApprovals();
    // On the paths where load() throws before loadFeed runs, nothing would await this;
    // mark it handled so a doomed request cannot surface as an unhandled rejection.
    // (Awaiting it later still sees the rejection — this only attaches a handler.)
    approvalsPromise?.catch(() => undefined);
    const [keyList, summary] = await Promise.all([api.listVirtualKeys(), summaryPromise]);
    keys.value = keyList;
    // adminUsageSummary groups are the optional-field hub GroupSummary rows;
    // the stats helpers below read the legacy UsageGroup shape — narrow here.
    usageGroups.value = (summary.groups ?? []) as unknown as UsageGroup[];
    totals.value = (summary.totals ?? null) as unknown as OverviewTotals | null;
    if (isAdmin.value) {
      subscriptions.value = await api.listSubscriptions();
    }
    await loadFeed(keyList, approvalsPromise);
  } catch (error) {
    if (error instanceof ApiError) {
      loadError.value = error.message;
      loadRequestId.value = error.requestId ?? '';
    }
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div class="ui-page next-overview">
    <!-- Greeting header with inline stats (Vben workbench page-header) -->
    <section class="ui-panel next-overview__greeting" data-testid="overview-greeting">
      <div class="next-overview__greeting-left">
        <span class="next-overview__greeting-avatar" aria-hidden="true">{{ userInitial }}</span>
        <div class="next-overview__greeting-text">
          <h1 class="next-overview__greeting-title">你好，{{ userName }}，欢迎回来！</h1>
          <p class="next-overview__greeting-sub">{{ dateLabel }} · 今天也要高效工作。</p>
        </div>
      </div>
      <div class="next-overview__greeting-stats" data-testid="overview-stats">
        <div v-for="card in stats" :key="card.label" class="next-overview__stat-chip">
          <span class="next-overview__stat-chip-value ui-num"
            ><i v-if="card.prefix" class="next-overview__stat-currency">{{ card.prefix }}</i
            >{{ card.value
            }}<UiTooltip v-if="card.caveat" :text="card.caveat"
              ><span class="next-overview__stat-caveat" data-testid="overview-cost-caveat"
                >未定价</span
              ></UiTooltip
            ></span
          >
          <span class="next-overview__stat-chip-label" :title="card.hint">{{ card.label }}</span>
        </div>
      </div>
      <UiButton
        variant="primary"
        data-testid="overview-create-key"
        @click="$router.push('/app/keys')"
      >
        创建虚拟密钥
      </UiButton>
    </section>

    <div v-if="loadError" class="ui-alert ui-alert--error" data-testid="overview-load-error">
      {{ loadError
      }}<span v-if="loadRequestId" class="ui-request-id"> requestId: {{ loadRequestId }}</span>
    </div>

    <template v-if="loading">
      <div class="next-overview__workbench">
        <div class="ui-skeleton next-overview__skeleton-block" />
        <div class="ui-skeleton next-overview__skeleton-block" />
      </div>
    </template>

    <template v-else>
      <div class="next-overview__workbench">
        <!-- LEFT column: key tiles + usage distribution -->
        <div class="next-overview__col-main">
          <section class="ui-panel next-overview__panel" data-testid="overview-keys">
            <div class="ui-panel-head">
              <h2 class="ui-panel-title">密钥速览</h2>
              <router-link to="/app/keys" class="next-overview__link">全部密钥</router-link>
            </div>
            <div v-if="recentKeys.length" class="next-overview__key-grid">
              <router-link
                v-for="key in recentKeys"
                :key="key.id"
                to="/app/keys"
                class="next-overview__key-tile"
              >
                <div class="next-overview__key-tile-top">
                  <span
                    class="next-overview__key-logo"
                    :style="{ color: purposeMeta(key.purpose).color }"
                    aria-hidden="true"
                  >
                    <component :is="purposeMeta(key.purpose).icon" size="28px" />
                  </span>
                  <UiStatusBadge
                    :tone="keyStatusMeta(key.status).tone"
                    :label="keyStatusMeta(key.status).label"
                  />
                </div>
                <span class="next-overview__key-name" :title="key.name">{{ key.name }}</span>
                <span class="next-overview__key-desc">
                  {{ purposeLabel(key.purpose)
                  }}<template v-if="(key.modelIds ?? []).length">
                    · {{ (key.modelIds ?? []).length }} 个模型</template
                  >
                </span>
                <span class="ui-mono next-overview__key-mask">{{ key.display }}</span>
                <div class="next-overview__key-foot">
                  <span>{{ key.projectTag || '—' }}</span>
                  <span class="ui-num">{{ createdLabel(key.createdAt) }}</span>
                </div>
              </router-link>
            </div>
            <div v-else class="next-overview__recent-empty">
              <p class="next-overview__empty">还没有虚拟密钥。</p>
              <router-link to="/app/keys" class="next-overview__link">创建一个</router-link>
            </div>
          </section>

          <section class="ui-panel next-overview__panel" data-testid="overview-feed">
            <div class="ui-panel-head">
              <h2 class="ui-panel-title">最新动态</h2>
              <router-link :to="isAdmin ? '/app/audit' : '/app/usage'" class="next-overview__link"
                >查看全部</router-link
              >
            </div>
            <div v-if="feed.length" class="next-overview__feed">
              <div v-for="(item, i) in feed" :key="i" class="next-overview__feed-row">
                <span
                  class="next-overview__feed-dot"
                  :class="`next-overview__feed-dot--${item.tone}`"
                  aria-hidden="true"
                />
                <span class="next-overview__feed-text">{{ item.text }}</span>
                <span class="next-overview__feed-time">{{ item.time }}</span>
              </div>
            </div>
            <div
              v-else-if="feedError"
              style="padding: 0 24px 16px"
              data-testid="overview-feed-error"
            >
              <p class="ui-form-error">{{ feedError }}</p>
              <UiButton
                variant="ghost"
                size="sm"
                data-testid="overview-feed-retry"
                @click="retryFeed"
                >重试</UiButton
              >
            </div>
            <p v-else-if="feedLoading" class="next-overview__empty" style="padding: 0 24px 16px">
              加载中…
            </p>
            <p v-else class="next-overview__empty" style="padding: 0 24px 16px">还没有动态记录。</p>
          </section>

          <section class="ui-panel next-overview__panel" data-testid="overview-usage">
            <div class="ui-panel-head">
              <h2 class="ui-panel-title">用量分布（按项目）</h2>
              <router-link to="/app/usage" class="next-overview__link">查看明细</router-link>
            </div>
            <div class="ui-panel-body">
              <div v-if="usageBars.length" class="next-overview__bars">
                <div v-for="bar in usageBars" :key="bar.label" class="next-overview__bar-row">
                  <span class="next-overview__bar-label" :title="bar.label">{{ bar.label }}</span>
                  <div class="next-overview__bar-track">
                    <div
                      class="next-overview__bar-fill"
                      :style="{ width: bar.width, opacity: bar.alpha }"
                    />
                  </div>
                  <span class="next-overview__bar-value ui-num">{{ formatCount(bar.value) }}</span>
                </div>
              </div>
              <div v-else class="next-overview__usage-empty">
                <p class="next-overview__empty">
                  还没有用量记录。创建虚拟密钥并开始调用后，这里会出现用量分布。
                </p>
              </div>
            </div>
          </section>
        </div>

        <!-- RIGHT column: quick nav, cost donut, (admin) quota ledger -->
        <div class="next-overview__col-side">
          <section class="ui-panel next-overview__panel" data-testid="overview-quicknav">
            <div class="ui-panel-head">
              <h2 class="ui-panel-title">快捷导航</h2>
            </div>
            <nav class="next-overview__quick-grid" aria-label="快捷入口">
              <router-link
                v-for="item in quickNav"
                :key="item.to"
                :to="item.to"
                class="next-overview__quick-tile"
              >
                <span
                  class="next-overview__quick-icon"
                  :style="{ color: item.color }"
                  aria-hidden="true"
                >
                  <component :is="item.icon" size="20px" />
                </span>
                <span class="next-overview__quick-label">{{ item.label }}</span>
              </router-link>
            </nav>
          </section>

          <section
            v-if="costGroups.length"
            class="ui-panel next-overview__panel"
            data-testid="overview-cost"
          >
            <div class="ui-panel-head">
              <h2 class="ui-panel-title">成本分布</h2>
              <span class="ui-panel-sub">合计 ¥{{ costTotal.toFixed(2) }}</span>
            </div>
            <div v-if="costTotal > 0" class="ui-panel-body next-overview__cost-layout">
              <div class="next-overview__donut-wrap">
                <UiDonut
                  :segments="
                    donutSegments.map((s) => ({ label: s.label, value: s.cost, color: s.color }))
                  "
                  :center-text="`¥${costTotal.toFixed(2)}`"
                  data-testid="overview-cost-donut"
                />
              </div>
              <div class="ui-legend">
                <div v-for="seg in donutSegments" :key="seg.label" class="ui-legend-row">
                  <span class="ui-legend-dot" :style="{ background: seg.color }" />
                  <span class="ui-legend-label" :title="seg.label">{{ seg.label }}</span>
                  <span class="ui-legend-pct ui-num">{{ seg.pct.toFixed(0) }}%</span>
                  <span class="ui-legend-value ui-num">¥{{ seg.cost.toFixed(2) }}</span>
                </div>
              </div>
            </div>
            <div v-else class="ui-panel-body">
              <p class="next-overview__empty">暂无成本记录。</p>
            </div>
          </section>

          <section
            v-if="isAdmin"
            class="ui-panel next-overview__panel"
            data-testid="overview-ledger"
          >
            <div class="ui-panel-head">
              <div>
                <h2 class="ui-panel-title">额度账本</h2>
                <span class="ui-panel-sub">5 小时 / 周 / 月滚动窗口</span>
              </div>
            </div>
            <div v-if="quotaLedger.length" class="next-overview__ledger">
              <div v-for="row in quotaLedger" :key="row.id" class="next-overview__ledger-row">
                <div class="next-overview__ledger-plan">
                  <span class="next-overview__key-name">{{ row.name }}</span>
                  <span class="ui-panel-sub"
                    >{{ row.productName }} · {{ planScopeLabel(row.planScope) }}</span
                  >
                </div>
                <div class="next-overview__ledger-band">
                  <template v-if="row.quotaTotal">
                    <div
                      v-for="seg in row.segments"
                      :key="seg.label"
                      class="next-overview__ledger-seg"
                    >
                      <span class="next-overview__ledger-seg-label"
                        >{{ seg.label }} · {{ Math.round(seg.ratio * 100) }}%</span
                      >
                      <div class="next-overview__ledger-track">
                        <div
                          class="next-overview__ledger-fill"
                          :class="{
                            'next-overview__ledger-fill--warn': seg.ratio >= 0.6 && seg.ratio < 0.8,
                            'next-overview__ledger-fill--danger': seg.ratio >= 0.8,
                          }"
                          :style="{ width: `${Math.round(seg.ratio * 100)}%` }"
                        />
                      </div>
                    </div>
                  </template>
                  <span v-else class="next-overview__ledger-unset">未配置滚动额度</span>
                </div>
                <span class="next-overview__ledger-quota ui-num">{{
                  row.quotaTotal
                    ? `${formatCount(row.quotaTotal)} ${quotaUnitLabel(row.quotaUnit)}`
                    : '未配置'
                }}</span>
              </div>
            </div>
            <p v-else class="next-overview__empty">
              还没有订阅。到「订阅」录入套餐后，这里会显示每套方案的滚动额度。
            </p>
          </section>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.ui-alert {
  padding: var(--ui-space-3) var(--ui-space-4);
  margin-bottom: var(--ui-space-4);
  border-radius: var(--ui-radius-control);
  font-size: var(--ui-font-size-sm);
  line-height: var(--ui-line-height-base);
}

.ui-alert--error {
  background: var(--ui-danger-bg);
  color: var(--ui-danger-fg);
}

/* ---- greeting header (Vben workbench page-header) ---- */
.next-overview__greeting {
  display: flex;
  align-items: center;
  gap: var(--ui-space-6);
  flex-wrap: wrap;
  padding: var(--ui-space-5) var(--ui-space-6);
  margin-bottom: var(--ui-space-4);
}

.next-overview__greeting-left {
  display: flex;
  align-items: center;
  gap: var(--ui-space-4);
  flex: 1;
  min-width: 260px;
}

.next-overview__greeting-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 48px;
  height: 48px;
  border-radius: 50%;
  background: var(--ui-primary);
  color: var(--ui-foreground-inverse);
  font-size: 20px;
  font-weight: var(--ui-weight-semibold);
  flex-shrink: 0;
}

.next-overview__greeting-title {
  margin: 0 0 9px; /* Vben workbench h1: 9px to the sub line */
  font-size: 18px;
  font-weight: var(--ui-weight-semibold);
  color: var(--ui-foreground);
  line-height: 28px;
}

.next-overview__greeting-sub {
  margin: 0;
  font-size: var(--ui-font-size-base);
  color: var(--ui-foreground-secondary);
  line-height: 22px;
}

.next-overview__stat-caveat {
  /* line-height:1 keeps the chip from stretching the value's line box, so the
     cost card stays exactly as tall as the three beside it. */
  font-size: var(--ui-font-size-xs);
  font-weight: var(--ui-weight-medium);
  line-height: 1;
  color: var(--ui-warning-fg);
  white-space: nowrap;
}

.next-overview__greeting-stats {
  display: flex;
  align-items: center;
  gap: var(--ui-space-6);
  flex-wrap: wrap;
}

.next-overview__stat-chip {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.next-overview__stat-chip-value {
  /* One line: the caveat rides alongside the figure rather than widening the card. */
  display: inline-flex;
  align-items: baseline;
  gap: var(--ui-space-1);
  font-size: 20px;
  font-weight: var(--ui-weight-semibold);
  color: var(--ui-foreground);
  line-height: 26px;
  white-space: nowrap;
}

.next-overview__stat-currency {
  font-style: normal;
  font-size: 0.78em;
  margin-right: 1px;
}

.next-overview__stat-chip-label {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
  white-space: nowrap;
}

/* ---- workbench two-column body (69 / 360px) ---- */
.next-overview__workbench {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 360px;
  gap: var(--ui-space-4);
  align-items: start;
}

@media (max-width: 1100px) {
  .next-overview__workbench {
    grid-template-columns: 1fr;
  }
}

.next-overview__col-main,
.next-overview__col-side {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-4);
  min-width: 0;
}

.next-overview__panel {
  margin: 0;
}

.next-overview__skeleton-block {
  height: 260px;
  border-radius: var(--ui-radius-panel);
}

.next-overview__link {
  font-size: var(--ui-font-size-xs);
  font-weight: var(--ui-weight-medium);
  color: var(--ui-primary-text);
  text-decoration: none;
}

.next-overview__link:hover {
  text-decoration: underline;
}

/* ---- key tiles (Vben project-card grid) ---- */
.next-overview__key-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 1px;
  background: var(--ui-border);
  border-radius: 0 0 var(--ui-radius-panel) var(--ui-radius-panel);
  overflow: hidden;
}

.next-overview__key-tile {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-2);
  padding: var(--ui-space-5);
  background: var(--ui-card);
  color: inherit;
  text-decoration: none;
  transition: background-color var(--ui-ease);
}

.next-overview__key-tile:hover {
  background: var(--ui-muted);
}

.next-overview__key-tile-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ui-space-2);
  margin-bottom: var(--ui-space-1);
}

.next-overview__key-logo {
  display: inline-flex;
  align-items: center;
  flex-shrink: 0;
}

.next-overview__key-logo svg {
  width: 28px;
  height: 28px;
}

.next-overview__key-desc {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ---- latest activity feed (Vben 最新动态 rows) ---- */
.next-overview__feed {
  display: flex;
  flex-direction: column;
}

.next-overview__feed-row {
  display: flex;
  align-items: center;
  gap: var(--ui-space-3);
  padding: 10px var(--ui-space-6);
  border-bottom: 1px solid var(--ui-border-muted);
  font-size: var(--ui-font-size-sm);
}

.next-overview__feed-row:last-child {
  border-bottom: none;
}

.next-overview__feed-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}

.next-overview__feed-dot--success {
  background: var(--ui-success-fg);
}

.next-overview__feed-dot--info {
  background: var(--ui-primary);
}

.next-overview__feed-dot--warning {
  background: var(--ui-warning-fg);
}

.next-overview__feed-text {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--ui-foreground);
}

.next-overview__feed-time {
  color: var(--ui-foreground-faint);
  font-size: var(--ui-font-size-xs);
  flex-shrink: 0;
}

.next-overview__key-name {
  font-size: var(--ui-font-size-base);
  font-weight: var(--ui-weight-semibold);
  color: var(--ui-foreground);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.next-overview__key-mask {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-faint);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.next-overview__key-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ui-space-2);
  margin-top: var(--ui-space-2);
  padding-top: var(--ui-space-3);
  border-top: 1px solid var(--ui-border-muted);
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
}

/* ---- quick-nav tile grid (Vben card-grid) ---- */
.next-overview__quick-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 1px;
  background: var(--ui-border);
  border-radius: 0 0 var(--ui-radius-panel) var(--ui-radius-panel);
  overflow: hidden;
}

.next-overview__quick-tile {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--ui-space-2);
  /* Vben ant-card-grid: 24px vertical padding -> 98px tiles at 3 columns.
     Horizontal stays 8px so 6-char CJK labels keep one line (Vben's own
     labels are <=4 chars and survive its 24px horizontal padding). */
  padding: 24px var(--ui-space-2);
  background: var(--ui-card);
  color: var(--ui-foreground);
  text-decoration: none;
  transition: background-color var(--ui-ease);
}

.next-overview__quick-tile:hover {
  background: var(--ui-muted);
}

.next-overview__quick-icon {
  display: inline-flex;
  align-items: center;
}

.next-overview__quick-label {
  font-size: var(--ui-font-size-base);
  line-height: 22px; /* Vben tile label metrics */
  color: var(--ui-foreground-secondary);
  text-align: center;
}

/* ---- usage bars ---- */
.next-overview__bars {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-3);
}

.next-overview__bar-row {
  display: grid;
  grid-template-columns: minmax(110px, 160px) 1fr 72px;
  align-items: center;
  gap: var(--ui-space-3);
  font-size: var(--ui-font-size-xs);
}

.next-overview__bar-label {
  color: var(--ui-foreground-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.next-overview__bar-track {
  height: 10px;
  border-radius: var(--ui-radius-pill);
  background: var(--ui-muted);
  overflow: hidden;
}

.next-overview__bar-fill {
  height: 100%;
  border-radius: var(--ui-radius-pill);
  background: var(--ui-primary);
}

.next-overview__bar-value {
  text-align: right;
  color: var(--ui-foreground);
}

/* ---- cost donut (compact side column) ---- */
.next-overview__cost-layout {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--ui-space-5);
}

.next-overview__donut-wrap {
  position: relative;
}

.next-overview__cost-layout .ui-legend {
  width: 100%;
}

/* ---- admin quota ledger (compact side column) ---- */
.next-overview__ledger {
  display: flex;
  flex-direction: column;
}

.next-overview__ledger-row {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-3);
  padding: var(--ui-space-4) var(--ui-space-5);
  border-bottom: 1px solid var(--ui-border-muted);
}

.next-overview__ledger-row:last-child {
  border-bottom: none;
}

.next-overview__ledger-plan {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.next-overview__ledger-band {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-2);
}

.next-overview__ledger-seg {
  display: flex;
  align-items: center;
  gap: var(--ui-space-3);
}

.next-overview__ledger-seg-label {
  width: 72px;
  flex-shrink: 0;
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
}

.next-overview__ledger-track {
  flex: 1;
  height: 6px;
  border-radius: var(--ui-radius-pill);
  background: var(--ui-muted);
  overflow: hidden;
}

.next-overview__ledger-fill {
  height: 100%;
  border-radius: var(--ui-radius-pill);
  background: var(--ui-primary);
}

.next-overview__ledger-fill--warn {
  background: var(--ui-warning-fg);
}

.next-overview__ledger-fill--danger {
  background: var(--ui-danger-fg);
}

.next-overview__ledger-quota {
  text-align: right;
  font-size: var(--ui-font-size-sm);
  color: var(--ui-foreground);
}

.next-overview__ledger-unset {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-faint);
}

.next-overview__recent-empty,
.next-overview__usage-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 26px 0;
  text-align: center;
}

.next-overview__recent-empty p,
.next-overview__usage-empty p {
  margin: 0;
}

.next-overview__empty {
  margin: 0;
  font-size: var(--ui-font-size-sm);
  color: var(--ui-foreground-secondary);
  padding: var(--ui-space-2) 0;
}
</style>
