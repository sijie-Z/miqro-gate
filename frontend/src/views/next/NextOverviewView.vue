<script setup lang="ts">
/**
 * NextOverviewView — /app-new/overview pilot page (UI U0/U1, PostHog language).
 * Behaviour parity with the legacy OverviewView: stat band, token usage bars,
 * recent keys, cost-by-project list and (admin only) the subscription quota
 * ledger. APIs untouched; rendering only.
 */
import { computed, onMounted, ref } from 'vue';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import { useAuthStore } from '@/stores/auth';
import { UiButton, UiStatusBadge } from '@/ui';
import type { SubscriptionView, UsageGroup, VirtualKeyView } from '@/types/api';

const auth = useAuthStore();

const keys = ref<VirtualKeyView[]>([]);
const usageGroups = ref<UsageGroup[]>([]);
const subscriptions = ref<SubscriptionView[]>([]);
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');

const isAdmin = computed(() => auth.user?.role === 'SYSTEM_ADMIN');

const stats = computed(() => {
  const active = keys.value.filter((k) => k.status === 'ACTIVE').length;
  const totalTokens = usageGroups.value.reduce(
    (sum, g) => sum + (g.tokens?.input ?? 0) + (g.tokens?.output ?? 0),
    0,
  );
  const totalRequests = usageGroups.value.reduce((sum, g) => sum + (g.requests?.upstream ?? 0), 0);
  const totalCost = usageGroups.value.reduce(
    (sum, g) => sum + Number(g.cost?.upstreamPaid ?? 0),
    0,
  );
  return [
    { label: 'Virtual Key', value: String(keys.value.length), hint: `${active} 个可用` },
    { label: '本月请求', value: formatCount(totalRequests), hint: '经网关的请求数' },
    { label: '本月 Tokens', value: formatCount(totalTokens), hint: '输入 + 输出' },
    { label: '本月成本', value: `¥${Number(totalCost).toFixed(2)}`, hint: '按价格快照估算' },
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
    .filter((g) => g.cost > 0)
    .sort((a, b) => b.cost - a.cost)
    .slice(0, 8),
);

const costTotal = computed(() => costGroups.value.reduce((sum, g) => sum + g.cost, 0));

const recentKeys = computed(() => keys.value.slice(0, 5));

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
  <div class="ui-page next-overview">
    <header class="ui-page-header">
      <div>
        <h1 class="ui-page-title">{{ auth.user?.displayName ?? auth.user?.username }}，欢迎回来</h1>
        <p class="ui-page-desc">
          内部凭证治理控制台 · 单租户部署 · {{ new Date().getFullYear() }} 年
        </p>
      </div>
      <div class="ui-page-actions">
        <UiButton
          variant="primary"
          data-testid="overview-create-key"
          @click="$router.push('/app-new/keys')"
        >
          创建 Virtual Key
        </UiButton>
      </div>
    </header>

    <div v-if="loadError" class="ui-alert ui-alert--error" data-testid="overview-load-error">
      {{ loadError
      }}<span v-if="loadRequestId" class="ui-request-id"> requestId: {{ loadRequestId }}</span>
    </div>

    <template v-if="loading">
      <div class="ui-panel next-overview__panel">
        <div class="next-overview__stat-band">
          <div v-for="n in 4" :key="n" class="ui-skeleton next-overview__stat-skeleton" />
        </div>
      </div>
    </template>

    <template v-else>
      <!-- Stat band -->
      <section class="ui-panel next-overview__panel" data-testid="overview-stats">
        <div class="next-overview__stat-band">
          <div v-for="card in stats" :key="card.label" class="next-overview__stat">
            <span class="next-overview__stat-label">{{ card.label }}</span>
            <span class="next-overview__stat-value ui-num">{{ card.value }}</span>
            <span class="next-overview__stat-hint">{{ card.hint }}</span>
          </div>
        </div>
      </section>

      <!-- Usage bars + recent keys -->
      <div class="next-overview__grid">
        <section class="ui-panel" data-testid="overview-usage">
          <div class="ui-panel-head">
            <h2 class="ui-panel-title">用量分布（按项目）</h2>
            <router-link to="/app-new/usage" class="next-overview__link">查看明细</router-link>
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
            <p v-else class="next-overview__empty">
              还没有用量记录。创建 Key 并开始调用后，这里会出现用量分布。
            </p>

            <template v-if="costGroups.length">
              <div class="next-overview__cost-head">
                <h3 class="next-overview__sub-title">成本分布</h3>
                <span class="ui-panel-sub">上游实付 · ¥{{ costTotal.toFixed(2) }}</span>
              </div>
              <div class="next-overview__cost-grid">
                <div v-for="group in costGroups" :key="group.label" class="next-overview__cost-row">
                  <span class="next-overview__cost-label" :title="group.label">{{
                    group.label
                  }}</span>
                  <div class="next-overview__cost-track">
                    <div
                      class="next-overview__cost-fill"
                      :style="{ width: `${Math.max(2, (group.cost / (costTotal || 1)) * 100)}%` }"
                    />
                  </div>
                  <span class="next-overview__cost-value ui-num"
                    >¥{{ group.cost.toFixed(2) }} ·
                    {{ ((group.cost / (costTotal || 1)) * 100).toFixed(0) }}%</span
                  >
                </div>
              </div>
            </template>
          </div>
        </section>

        <section class="ui-panel" data-testid="overview-keys">
          <div class="ui-panel-head">
            <h2 class="ui-panel-title">最近创建的 Key</h2>
            <router-link to="/app-new/keys" class="next-overview__link">全部 Key</router-link>
          </div>
          <div v-if="recentKeys.length" class="next-overview__recent">
            <div v-for="key in recentKeys" :key="key.id" class="next-overview__key-row">
              <div class="next-overview__key-meta">
                <span class="next-overview__key-name">{{ key.name }}</span>
                <span class="ui-mono next-overview__key-mask">{{ key.display }}</span>
              </div>
              <UiStatusBadge
                :tone="
                  key.status === 'ACTIVE'
                    ? 'success'
                    : key.status === 'REVOKED'
                      ? 'danger'
                      : key.status === 'ROTATING'
                        ? 'warning'
                        : 'neutral'
                "
                :label="
                  key.status === 'ACTIVE'
                    ? '可用'
                    : key.status === 'REVOKED'
                      ? '已吊销'
                      : key.status === 'ROTATING'
                        ? '轮换中'
                        : '停用'
                "
              />
            </div>
          </div>
          <div v-else class="next-overview__recent-empty">
            <p class="next-overview__empty">还没有 Virtual Key。</p>
            <router-link to="/app-new/keys" class="next-overview__link">创建一个</router-link>
          </div>
        </section>
      </div>

      <!-- Admin quota ledger -->
      <section v-if="isAdmin" class="ui-panel next-overview__panel" data-testid="overview-ledger">
        <div class="ui-panel-head">
          <div>
            <h2 class="ui-panel-title">额度账本</h2>
            <span class="ui-panel-sub">5 小时 / 周 / 月滚动窗口；配额数据来自订阅配置</span>
          </div>
        </div>
        <div v-if="quotaLedger.length" class="next-overview__ledger">
          <div v-for="row in quotaLedger" :key="row.id" class="next-overview__ledger-row">
            <div class="next-overview__ledger-plan">
              <span class="next-overview__key-name">{{ row.name }}</span>
              <span class="ui-panel-sub">{{ row.productName }} · {{ row.planScope }}</span>
            </div>
            <div class="next-overview__ledger-band">
              <div v-for="seg in row.segments" :key="seg.label" class="next-overview__ledger-seg">
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
            </div>
            <span class="next-overview__ledger-quota ui-num">{{
              row.quotaTotal ? `${formatCount(row.quotaTotal)} ${row.quotaUnit}` : '—'
            }}</span>
          </div>
        </div>
        <p v-else class="next-overview__empty">
          还没有订阅。到「订阅」录入套餐后，这里会显示每套方案的滚动额度。
        </p>
      </section>
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

.next-overview__panel {
  margin-bottom: var(--ui-space-5);
}

.next-overview__stat-band {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--ui-space-2);
  padding: var(--ui-space-4) var(--ui-space-5);
}

.next-overview__stat {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-1);
  padding: var(--ui-space-1) var(--ui-space-2);
  border-right: 1px solid var(--ui-border-muted);
}

.next-overview__stat:last-child {
  border-right: none;
}

.next-overview__stat-label {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
}

.next-overview__stat-value {
  font-size: 24px;
  font-weight: var(--ui-weight-semibold);
  letter-spacing: -0.01em;
}

.next-overview__stat-hint {
  font-size: 11px;
  color: var(--ui-foreground-faint);
}

.next-overview__stat-skeleton {
  height: 64px;
}

.next-overview__grid {
  display: grid;
  grid-template-columns: 3fr 2fr;
  gap: var(--ui-space-5);
  margin-bottom: var(--ui-space-5);
  align-items: start;
}

@media (max-width: 1100px) {
  .next-overview__grid {
    grid-template-columns: 1fr;
  }
}

.next-overview__link {
  font-size: var(--ui-font-size-xs);
  font-weight: var(--ui-weight-medium);
  color: var(--ui-primary);
  text-decoration: none;
}

.next-overview__link:hover {
  text-decoration: underline;
}

.next-overview__bars {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-3);
  margin-bottom: var(--ui-space-6);
}

.next-overview__bar-row {
  display: grid;
  grid-template-columns: 120px 1fr 64px;
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

.next-overview__cost-head {
  display: flex;
  align-items: baseline;
  gap: var(--ui-space-3);
  padding-top: var(--ui-space-4);
  border-top: 1px solid var(--ui-border-muted);
  margin-bottom: var(--ui-space-3);
}

.next-overview__sub-title {
  margin: 0;
  font-size: var(--ui-font-size-sm);
  font-weight: var(--ui-weight-semibold);
}

.next-overview__cost-grid {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-2);
}

.next-overview__cost-row {
  display: grid;
  grid-template-columns: 120px 1fr 130px;
  align-items: center;
  gap: var(--ui-space-3);
  font-size: var(--ui-font-size-xs);
}

.next-overview__cost-label {
  color: var(--ui-foreground-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.next-overview__cost-track {
  height: 6px;
  border-radius: var(--ui-radius-pill);
  background: var(--ui-muted);
  overflow: hidden;
}

.next-overview__cost-fill {
  height: 100%;
  border-radius: var(--ui-radius-pill);
  background: var(--ui-primary);
  opacity: 0.75;
}

.next-overview__cost-value {
  text-align: right;
  color: var(--ui-foreground-secondary);
}

.next-overview__recent {
  display: flex;
  flex-direction: column;
}

.next-overview__key-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ui-space-3);
  padding: var(--ui-space-3) var(--ui-space-5);
  border-bottom: 1px solid var(--ui-border-muted);
}

.next-overview__key-row:last-child {
  border-bottom: none;
}

.next-overview__key-meta {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.next-overview__key-name {
  font-weight: var(--ui-weight-medium);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.next-overview__key-mask {
  font-size: 11px;
  color: var(--ui-foreground-faint);
  margin-top: 2px;
}

.next-overview__recent-empty {
  padding: var(--ui-space-8) var(--ui-space-5);
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--ui-space-2);
}

.next-overview__empty {
  margin: 0;
  font-size: var(--ui-font-size-sm);
  color: var(--ui-foreground-secondary);
  padding: var(--ui-space-2) 0;
}

.next-overview__ledger {
  display: flex;
  flex-direction: column;
}

.next-overview__ledger-row {
  display: grid;
  grid-template-columns: 220px 1fr 130px;
  align-items: center;
  gap: var(--ui-space-5);
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
</style>
