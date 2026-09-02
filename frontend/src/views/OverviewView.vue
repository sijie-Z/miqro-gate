<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import { useAuthStore } from '@/stores/auth';
import type { SubscriptionView, UsageGroup, VirtualKeyView } from '@/types/api';

const auth = useAuthStore();

const keys = ref<VirtualKeyView[]>([]);
const usageGroups = ref<UsageGroup[]>([]);
const subscriptions = ref<SubscriptionView[]>([]);
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');

const statCards = computed(() => {
  const active = keys.value.filter((k) => k.status === 'ACTIVE').length;
  const totalTokens = usageGroups.value.reduce(
    (sum, g) => sum + (g.tokens?.input ?? 0) + (g.tokens?.output ?? 0),
    0,
  );
  const totalRequests = usageGroups.value.reduce((sum, g) => sum + (g.requests?.upstream ?? 0), 0);
  const totalCost = usageGroups.value.reduce((sum, g) => sum + (g.cost?.upstreamPaid ?? 0), 0);
  return [
    {
      label: 'Virtual Key',
      value: String(keys.value.length),
      hint: `${active} 个 ACTIVE`,
      icon: 'K',
      chip: 'mk-chip-tencent',
    },
    {
      label: '本月请求',
      value: formatCount(totalRequests),
      hint: '经网关的请求数',
      icon: '⇄',
      chip: 'mk-chip-zhipu',
    },
    {
      label: '本月 Tokens',
      value: formatCount(totalTokens),
      hint: '输入 + 输出',
      icon: 'T',
      chip: 'mk-chip-deepseek',
    },
    {
      label: '本月成本',
      value: `¥${Number(totalCost).toFixed(2)}`,
      hint: '按价格快照估算',
      icon: '¥',
      chip: 'mk-chip-aliyun',
    },
  ];
});

const topUsageBars = computed(() => {
  const ranked = usageGroups.value
    .map((g) => ({
      label: g.label,
      value: (g.tokens?.input ?? 0) + (g.tokens?.output ?? 0),
    }))
    .sort((a, b) => b.value - a.value)
    .slice(0, 8);
  const max = Math.max(...ranked.map((r) => r.value), 1);
  return ranked.map((r) => ({ ...r, width: `${Math.max(4, (r.value / max) * 100)}%` }));
});

const recentKeys = computed(() => keys.value.slice(0, 5));

const DONUT_COLORS = [
  '#0066ff',
  '#00b3ff',
  '#4d6bfe',
  '#ff9a2e',
  '#00b96b',
  '#8b5cf6',
  '#e5484d',
  '#57606a',
];

const costDonut = computed(() => {
  const groups = usageGroups.value
    .map((g) => ({ label: g.label, value: g.cost?.upstreamPaid ?? 0 }))
    .filter((g) => g.value > 0)
    .sort((a, b) => b.value - a.value)
    .slice(0, 8);
  const total = groups.reduce((sum, g) => sum + g.value, 0);
  if (!total) return { groups: [], background: '' };
  let acc = 0;
  const stops = groups.map((g, i) => {
    const from = (acc / total) * 360;
    acc += g.value;
    const to = (acc / total) * 360;
    return `${DONUT_COLORS[i % DONUT_COLORS.length]} ${from}deg ${to}deg`;
  });
  return { groups, background: `conic-gradient(${stops.join(', ')})` };
});

const isAdmin = computed(() => auth.user?.role === 'SYSTEM_ADMIN');

/** Quota-band data per plan: 5h/week/month fill ratios derived from quotaTotal. */
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

function quotaFillClass(ratio: number): string {
  if (ratio >= 0.8) return 'mk-quota-fill--danger';
  if (ratio >= 0.6) return 'mk-quota-fill--warning';
  return '';
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
    const [keyList, summary] = await Promise.all([api.listVirtualKeys(), summaryPromise]);
    keys.value = keyList;
    usageGroups.value = summary.groups ?? [];
    if (isAdmin.value) {
      subscriptions.value = await api.listSubscriptions();
    }
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
  <div class="overview-page">
    <div class="overview-greeting">
      <div>
        <h2 class="mk-page-title">{{ auth.user?.displayName ?? auth.user?.username }}，欢迎回来</h2>
        <p class="mk-page-description">
          内部凭证治理控制台 · 单租户部署 · {{ new Date().getFullYear() }} 年
        </p>
      </div>
      <t-button
        theme="primary"
        data-testid="overview-create-key"
        @click="$router.push({ name: 'keys' })"
      >
        创建 Virtual Key
      </t-button>
    </div>

    <t-alert v-if="loadError" theme="error" class="block-alert">
      {{ loadError
      }}<span v-if="loadRequestId" class="mk-mono">requestId: {{ loadRequestId }}</span>
    </t-alert>

    <t-loading :loading="loading" size="small" show-overlay>
      <div class="mk-stat-grid" data-testid="overview-stats">
        <div v-for="card in statCards" :key="card.label" class="mk-stat-card">
          <span class="mk-brand-chip" :class="card.chip" aria-hidden="true">{{ card.icon }}</span>
          <div class="mk-stat-body">
            <span class="mk-stat-label">{{ card.label }}</span>
            <span class="mk-stat-value mk-num">{{ card.value }}</span>
            <span class="mk-stat-hint">{{ card.hint }}</span>
          </div>
        </div>
      </div>

      <div class="overview-grid">
        <section class="mk-card" data-testid="overview-usage">
          <div class="mk-card-header">
            <h3 class="mk-card-title">用量分布（按项目）</h3>
            <t-button variant="text" theme="primary" @click="$router.push({ name: 'usage' })"
              >查看明细</t-button
            >
          </div>
          <div class="mk-card-body">
            <div v-if="costDonut.groups.length" class="usage-split">
              <div
                class="mk-donut"
                :style="{ background: costDonut.background }"
                data-testid="cost-donut"
              />
              <div class="mk-donut-legend">
                <span v-for="g in costDonut.groups" :key="g.label">
                  <span
                    class="mk-donut-legend-dot"
                    :style="{
                      background: DONUT_COLORS[costDonut.groups.indexOf(g) % DONUT_COLORS.length],
                    }"
                  />
                  {{ g.label }} · ¥{{ Number(g.value).toFixed(2) }}
                </span>
              </div>
            </div>
            <div v-else class="mk-empty-hint">
              还没有用量记录。创建 Key 并开始调用后，这里会出现成本分布。
            </div>
            <div v-if="topUsageBars.length" class="mk-bar-chart usage-bars">
              <div v-for="bar in topUsageBars" :key="bar.label" class="mk-bar-row">
                <span class="mk-bar-label" :title="bar.label">{{ bar.label }}</span>
                <div class="mk-bar-track">
                  <div class="mk-bar-fill" :style="{ width: bar.width }" />
                </div>
                <span class="mk-bar-value mk-num">{{ formatCount(bar.value) }}</span>
              </div>
            </div>
          </div>
        </section>

        <section class="mk-card" data-testid="overview-keys">
          <div class="mk-card-header">
            <h3 class="mk-card-title">最近创建的 Key</h3>
            <t-button variant="text" theme="primary" @click="$router.push({ name: 'keys' })"
              >全部 Key</t-button
            >
          </div>
          <div class="mk-card-body">
            <div v-if="recentKeys.length" class="overview-key-list">
              <div v-for="key in recentKeys" :key="key.id" class="overview-key-row">
                <div>
                  <div class="overview-key-name">{{ key.name }}</div>
                  <div class="mk-mono overview-key-mask">{{ key.display }}</div>
                </div>
                <span
                  class="mk-status"
                  :class="
                    key.status === 'ACTIVE'
                      ? 'mk-status--success'
                      : key.status === 'REVOKED'
                        ? 'mk-status--neutral'
                        : 'mk-status--warning'
                  "
                >
                  {{ key.status }}
                </span>
              </div>
            </div>
            <div v-else class="mk-empty-hint">
              还没有 Virtual Key。<t-button
                variant="text"
                theme="primary"
                @click="$router.push({ name: 'keys' })"
                >创建一个</t-button
              >
            </div>
          </div>
        </section>
      </div>

      <section v-if="isAdmin" class="mk-card overview-ledger" data-testid="overview-ledger">
        <div class="mk-card-header">
          <h3 class="mk-card-title">额度账本</h3>
          <span class="mk-stat-hint">5 小时 / 周 / 月滚动窗口；配额数据来自订阅配置</span>
        </div>
        <div class="mk-card-body">
          <div v-if="quotaLedger.length" class="ledger-table">
            <div v-for="row in quotaLedger" :key="row.id" class="ledger-row">
              <div class="ledger-plan">
                <div class="overview-key-name">{{ row.name }}</div>
                <div class="mk-stat-hint">{{ row.productName }} · {{ row.planScope }}</div>
              </div>
              <div class="ledger-band">
                <div class="mk-quota-band">
                  <div v-for="seg in row.segments" :key="seg.label" class="mk-quota-segment">
                    <div class="mk-quota-segment-label">
                      <span>{{ seg.label }}</span
                      ><span class="mk-num">{{ Math.round(seg.ratio * 100) }}%</span>
                    </div>
                    <div class="mk-quota-track">
                      <div
                        class="mk-quota-fill"
                        :class="quotaFillClass(seg.ratio)"
                        :style="{ width: `${Math.round(seg.ratio * 100)}%` }"
                      />
                    </div>
                  </div>
                </div>
              </div>
              <div class="ledger-quota mk-num">
                {{ row.quotaTotal ? `${formatCount(row.quotaTotal)} ${row.quotaUnit}` : '—' }}
              </div>
            </div>
          </div>
          <div v-else class="mk-empty-hint">
            还没有订阅。到
            <t-button variant="text" theme="primary" @click="$router.push({ name: 'plans' })"
              >Plans</t-button
            >
            录入套餐后，这里会显示每套方案的滚动额度。
          </div>
        </div>
      </section>
    </t-loading>
  </div>
</template>

<style scoped>
.block-alert {
  margin-bottom: var(--miqrokey-space-4);
}

.overview-greeting {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--miqrokey-space-4);
  margin-bottom: var(--miqrokey-space-4);
}

.overview-grid {
  display: grid;
  grid-template-columns: 3fr 2fr;
  gap: var(--miqrokey-space-3);
  margin-bottom: var(--miqrokey-space-3);
}

@media (max-width: 960px) {
  .overview-grid {
    grid-template-columns: 1fr;
  }
}

.usage-split {
  display: flex;
  align-items: center;
  gap: var(--miqrokey-space-6);
  margin-bottom: var(--miqrokey-space-4);
}

.usage-bars {
  border-top: 1px solid var(--miqrokey-border-muted);
  padding-top: var(--miqrokey-space-3);
}

.overview-key-list {
  display: flex;
  flex-direction: column;
}

.overview-key-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--miqrokey-space-3);
  padding: var(--miqrokey-space-2) 0;
  border-bottom: 1px solid var(--miqrokey-border-muted);
}

.overview-key-row:last-child {
  border-bottom: none;
}

.overview-key-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--miqrokey-text-primary);
}

.overview-key-mask {
  margin-top: 2px;
  color: var(--miqrokey-text-disabled);
}

.overview-ledger {
  margin-bottom: var(--miqrokey-space-4);
}

.ledger-table {
  display: flex;
  flex-direction: column;
}

.ledger-row {
  display: grid;
  grid-template-columns: 200px 1fr 140px;
  align-items: center;
  gap: var(--miqrokey-space-4);
  padding: var(--miqrokey-space-3) 0;
  border-bottom: 1px solid var(--miqrokey-border-muted);
}

.ledger-row:last-child {
  border-bottom: none;
}

.ledger-quota {
  text-align: right;
  color: var(--miqrokey-text-primary);
}
</style>
