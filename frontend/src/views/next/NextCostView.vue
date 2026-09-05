<script setup lang="ts">
/**
 * NextCostView — /app/cost v2 admin page (U2 platform batch).
 * Behaviour parity with the legacy cost report: project/day cost tables,
 * five stat cards, the monthly budget panel (summary water band + per-project
 * rows with edit/delete gates) and CSV export. Rendering only; APIs untouched.
 */
import { computed, onMounted, ref } from 'vue';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import { UiButton, UiDialog, UiInput, UiSelect, UiStatusBadge, UiTable, toast } from '@/ui';
import type { UiSelectOption } from '@/ui';
import type {Project, UsageGroup} from '@/types/api';
import type { BudgetView } from '@/types/generated-api';

const WINDOWS = [
  { label: '近 7 天', days: 7 },
  { label: '近 30 天', days: 30 },
  { label: '近 93 天', days: 93 },
];

const windowDays = ref(30);
const mode = ref<'project' | 'day'>('project');
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');

const projectSummary = ref<Awaited<ReturnType<typeof api.adminUsageSummary>> | null>(null);
const daySummary = ref<Awaited<ReturnType<typeof api.adminUsageSummary>> | null>(null);

const projectGroups = computed(() => projectSummary.value?.groups ?? []);
const dayGroups = computed(() => daySummary.value?.groups ?? []);

const totalCost = computed(() => projectSummary.value?.totals.cost.projectAllocated ?? '0');
const upstreamCost = computed(() => projectSummary.value?.totals.cost.upstreamPaid ?? '0');
const totalRequests = computed(() => projectSummary.value?.totals.requests.upstream ?? 0);
const totalTokens = computed(
  () =>
    (projectSummary.value?.totals.tokens.input ?? 0) +
    (projectSummary.value?.totals.tokens.output ?? 0),
);
const cacheSaved = computed(() => projectSummary.value?.totals.cost.savedByGatewayCache ?? '0');
const cacheHits = computed(() => {
  const t = projectSummary.value?.totals;
  return t ? (t.requests.l1Hit ?? 0) + (t.requests.l2Hit ?? 0) : 0;
});

const projectColumns = [
  { key: 'label', title: '项目', minWidth: '200px' },
  { key: 'requests', title: '请求', width: '100px', align: 'right' as const },
  { key: 'tokens', title: 'Tokens', width: '140px', align: 'right' as const },
  { key: 'cost', title: '分摊成本', width: '150px', align: 'right' as const },
  { key: 'share', title: '占比', minWidth: '220px' },
];

const dayColumns = [
  { key: 'label', title: '日期', minWidth: '160px' },
  { key: 'requests', title: '请求', width: '100px', align: 'right' as const },
  { key: 'tokens', title: 'Tokens', width: '140px', align: 'right' as const },
  { key: 'cost', title: '分摊成本', width: '150px', align: 'right' as const },
];

function costNumber(value: string | undefined): number {
  return Number(value ?? 0);
}

function costOf(group: UsageGroup): number {
  return costNumber(group.cost?.projectAllocated ?? group.cost?.upstreamPaid);
}

function tokensOf(group: UsageGroup): number {
  return (group.tokens?.input ?? 0) + (group.tokens?.output ?? 0);
}

function shareOf(group: UsageGroup): number {
  const total = costNumber(totalCost.value);
  if (!total) return 0;
  return (costOf(group) / total) * 100;
}

function formatCost(value: string | number): string {
  return `¥${Number(value).toFixed(4)}`;
}

function formatCount(value: number): string {
  return value >= 1_000_000
    ? `${(value / 1_000_000).toFixed(1)}M`
    : value >= 1_000
      ? `${(value / 1_000).toFixed(1)}k`
      : String(value);
}

function fromIso(days: number): string {
  const d = new Date();
  d.setDate(d.getDate() - days);
  return d.toISOString();
}

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    const from = fromIso(windowDays.value);
    const [projects, days] = await Promise.all([
      api.adminUsageSummary({ groupBy: 'project', from, to: new Date().toISOString() }),
      api.adminUsageSummary({ groupBy: 'day', from, to: new Date().toISOString() }),
    ]);
    projectSummary.value = projects;
    daySummary.value = days;
  } catch (error) {
    if (error instanceof ApiError) {
      loadError.value = error.message;
      loadRequestId.value = error.requestId ?? '';
    }
  } finally {
    loading.value = false;
  }
}

function switchWindow(days: number) {
  windowDays.value = days;
  void load();
}

function exportCsv() {
  const groups = mode.value === 'project' ? projectGroups.value : dayGroups.value;
  if (!groups.length) {
    toast.info('当前筛选下没有可导出的数据');
    return;
  }
  const header = ['分组', '请求', 'Tokens', '分摊成本(CNY)'];
  const rows = groups.map((g) => [
    g.label,
    String(g.requests.upstream),
    String(tokensOf(g)),
    costOf(g).toFixed(4),
  ]);
  const csv = [header, ...rows]
    .map((row) => row.map((cell) => `"${String(cell).replace(/"/g, '""')}"`).join(','))
    .join('\n');
  const blob = new Blob(['﻿' + csv], { type: 'text/csv;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `cost-${mode.value}-${new Date().toISOString().slice(0, 10)}.csv`;
  a.click();
  URL.revokeObjectURL(url);
}

// ---- budgets (G8.2) ----
const budgets = ref<BudgetView[]>([]);
const budgetLoading = ref(false);
const projects = ref<Project[]>([]);
const budgetDialogVisible = ref(false);
const budgetSaving = ref(false);
const budgetFormError = ref('');
const budgetForm = ref({ projectId: '', amount: '', alertThresholdPct: '80' });
const editingBudget = ref<BudgetView | null>(null);

const budgetMonth = computed(() => {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
});

const budgetTotalAmount = computed(() =>
  budgets.value.reduce((sum, b) => sum + costNumber(b.amount), 0),
);
const budgetTotalSpent = computed(() =>
  budgets.value.reduce((sum, b) => sum + costNumber(b.spent), 0),
);
const budgetOverallPct = computed(() =>
  budgetTotalAmount.value ? (budgetTotalSpent.value / budgetTotalAmount.value) * 100 : 0,
);

const budgetLevelLabel: Record<string, string> = {
  NORMAL: '正常',
  WARNING: '预警',
  EXCEEDED: '超限',
};

const budgetLevelTone: Record<string, 'success' | 'warning' | 'danger' | 'neutral'> = {
  NORMAL: 'success',
  WARNING: 'warning',
  EXCEEDED: 'danger',
};

const budgetProjectOptions = computed<UiSelectOption[]>(() =>
  projects.value.map((p) => ({
    value: p.id,
    label: `${p.code} · ${p.name}`,
  })),
);

function levelFill(level: string): string {
  if (level === 'EXCEEDED') return 'var(--ui-danger-fg)';
  if (level === 'WARNING') return 'var(--ui-warning-fg)';
  return 'var(--ui-primary)';
}

async function loadBudgets() {
  budgetLoading.value = true;
  try {
    budgets.value = await api.adminBudgets(budgetMonth.value);
  } catch {
    budgets.value = [];
  } finally {
    budgetLoading.value = false;
  }
}

async function openBudgetDialog(budget: BudgetView | null) {
  editingBudget.value = budget;
  budgetFormError.value = '';
  if (budget) {
    budgetForm.value = {
      projectId: budget.projectId,
      amount: budget.amount,
      alertThresholdPct: budget.alertThresholdPct,
    };
  } else {
    if (!projects.value.length) {
      try {
        projects.value = await api.listProjects();
      } catch {
        projects.value = [];
      }
    }
    budgetForm.value = { projectId: '', amount: '', alertThresholdPct: '80' };
  }
  budgetDialogVisible.value = true;
}

async function saveBudget() {
  budgetFormError.value = '';
  const amount = Number(budgetForm.value.amount);
  const threshold = Number(budgetForm.value.alertThresholdPct);
  if (!budgetForm.value.projectId) {
    budgetFormError.value = '请选择项目。';
    return;
  }
  if (!Number.isFinite(amount) || amount <= 0) {
    budgetFormError.value = '预算金额必须大于 0。';
    return;
  }
  if (!Number.isFinite(threshold) || threshold <= 0 || threshold > 100) {
    budgetFormError.value = '预警阈值必须在 0–100 之间。';
    return;
  }
  budgetSaving.value = true;
  try {
    await api.putProjectBudget(budgetForm.value.projectId, {
      month: budgetMonth.value,
      amount,
      alertThresholdPct: threshold,
    });
    budgetDialogVisible.value = false;
    toast.success('预算已保存');
    await loadBudgets();
  } catch (error) {
    budgetFormError.value = error instanceof ApiError ? error.message : '保存失败，请稍后重试。';
  } finally {
    budgetSaving.value = false;
  }
}

const confirmState = ref<{
  title: string;
  body: string;
  confirmLabel: string;
  tone: 'danger' | 'primary';
  run: () => Promise<void>;
} | null>(null);

function requestRemoveBudget(budget: BudgetView) {
  confirmState.value = {
    title: `删除预算「${budget.projectName}」`,
    body: `删除后 ${budget.month} 的预算计划将被移除，用量与成本数据不受影响。`,
    confirmLabel: '删除',
    tone: 'danger',
    run: async () => {
      try {
        await api.deleteProjectBudget(budget.projectId, budget.month);
        toast.success('预算已删除');
        await loadBudgets();
      } catch (error) {
        if (error instanceof ApiError) {
          toast.error(`${error.message}（requestId: ${error.requestId ?? '-'}）`);
        }
      }
    },
  };
}

async function confirmAndRun() {
  const state = confirmState.value;
  if (!state) return;
  confirmState.value = null;
  await state.run();
}

onMounted(async () => {
  await load();
  await loadBudgets();
});
</script>

<template>
  <div class="ui-page next-cost">
    <header class="ui-page-header">
      <div>
        <h1 class="ui-page-title">成本报表</h1>
        <p class="ui-page-desc">成本分摊与月度预算水位；金额按价格快照估算。</p>
      </div>
      <div class="ui-page-actions">
        <UiButton variant="secondary" data-testid="cost-export" @click="exportCsv"
          >导出 CSV</UiButton
        >
      </div>
    </header>

    <div v-if="loadError" class="ui-alert ui-alert--error" data-testid="cost-load-error">
      {{ loadError
      }}<span v-if="loadRequestId" class="ui-request-id"> requestId: {{ loadRequestId }}</span>
    </div>

    <div class="next-cost__toolbar">
      <div class="next-cost__segmented" data-testid="cost-mode">
        <button
          type="button"
          class="next-cost__seg"
          :class="{ 'next-cost__seg--on': mode === 'project' }"
          data-testid="cost-mode-project"
          @click="
            mode = 'project';
            load();
          "
        >
          按项目
        </button>
        <button
          type="button"
          class="next-cost__seg"
          :class="{ 'next-cost__seg--on': mode === 'day' }"
          data-testid="cost-mode-day"
          @click="
            mode = 'day';
            load();
          "
        >
          按天
        </button>
      </div>
      <div class="next-cost__segmented" data-testid="cost-window">
        <button
          v-for="w in WINDOWS"
          :key="w.days"
          type="button"
          class="next-cost__seg"
          :class="{ 'next-cost__seg--on': windowDays === w.days }"
          @click="switchWindow(w.days)"
        >
          {{ w.label }}
        </button>
      </div>
    </div>

    <div class="next-cost__stats" data-testid="cost-stats">
      <div class="ui-panel next-cost__stat">
        <span class="next-cost__stat-label">分摊总成本</span>
        <span class="next-cost__stat-value ui-num">{{ formatCost(totalCost) }}</span>
        <span class="next-cost__stat-hint">按项目分摊口径</span>
      </div>
      <div class="ui-panel next-cost__stat">
        <span class="next-cost__stat-label">上游已付成本</span>
        <span class="next-cost__stat-value ui-num">{{ formatCost(upstreamCost) }}</span>
        <span class="next-cost__stat-hint">按最新单价估算</span>
      </div>
      <div class="ui-panel next-cost__stat">
        <span class="next-cost__stat-label">请求</span>
        <span class="next-cost__stat-value ui-num">{{ formatCount(totalRequests) }}</span>
        <span class="next-cost__stat-hint">到达上游的请求数</span>
      </div>
      <div class="ui-panel next-cost__stat">
        <span class="next-cost__stat-label">Tokens</span>
        <span class="next-cost__stat-value ui-num">{{ formatCount(totalTokens) }}</span>
        <span class="next-cost__stat-hint">输入 + 输出</span>
      </div>
      <div class="ui-panel next-cost__stat">
        <span class="next-cost__stat-label">缓存节省</span>
        <span class="next-cost__stat-value next-cost__stat-value--accent ui-num">{{
          formatCost(cacheSaved)
        }}</span>
        <span class="next-cost__stat-hint">命中 {{ formatCount(cacheHits) }} 次 · 未调用上游</span>
      </div>
    </div>

    <!-- Monthly budgets -->
    <section class="ui-panel next-cost__budget" data-testid="cost-budget-panel">
      <div class="ui-panel-head">
        <div>
          <h2 class="ui-panel-title">月度预算 · {{ budgetMonth }}</h2>
          <span class="ui-panel-sub">只告警不阻断；超限不影响请求。</span>
        </div>
        <UiButton
          variant="primary"
          size="sm"
          data-testid="budget-create-open"
          @click="openBudgetDialog(null)"
        >
          设置预算
        </UiButton>
      </div>
      <div class="ui-panel-body">
        <div v-if="budgets.length" class="next-cost__budget-summary" data-testid="budget-summary">
          <span class="next-cost__budget-sum-label"
            >月度总预算 <b class="ui-num">{{ formatCost(budgetTotalAmount) }}</b></span
          >
          <div class="next-cost__budget-track">
            <div
              class="next-cost__budget-fill"
              :style="{
                width: `${Math.min(100, budgetOverallPct)}%`,
                background: levelFill(
                  budgetOverallPct >= 100
                    ? 'EXCEEDED'
                    : budgetOverallPct >= 80
                      ? 'WARNING'
                      : 'NORMAL',
                ),
              }"
            />
          </div>
          <span class="next-cost__budget-sum-label ui-num"
            >已用 {{ formatCost(budgetTotalSpent) }}（{{ budgetOverallPct.toFixed(1) }}%）</span
          >
        </div>
        <div
          v-for="b in budgets"
          :key="b.projectId"
          class="next-cost__budget-row"
          data-testid="budget-row"
        >
          <div class="next-cost__budget-project">
            <span class="next-cost__budget-name">{{ b.projectName }}</span>
            <span class="ui-mono next-cost__budget-code">{{ b.projectCode }}</span>
          </div>
          <div class="next-cost__budget-figures">
            <span class="ui-num">{{ formatCost(b.spent) }} / {{ formatCost(b.amount) }}</span>
            <UiStatusBadge
              variant="pill"
              :tone="budgetLevelTone[b.level] ?? 'neutral'"
              :label="budgetLevelLabel[b.level] ?? b.level"
              :data-testid="`budget-level-${b.projectCode}`"
            />
          </div>
          <div class="next-cost__budget-track">
            <div
              class="next-cost__budget-fill"
              :style="{
                width: `${Math.min(100, Number(b.spentPct))}%`,
                background: levelFill(b.level),
              }"
            />
          </div>
          <span class="next-cost__budget-pct ui-num">{{ b.spentPct }}%</span>
          <div class="next-cost__budget-actions">
            <UiButton
              variant="ghost"
              size="sm"
              data-testid="budget-edit"
              @click="openBudgetDialog(b)"
              >编辑</UiButton
            >
            <UiButton
              variant="ghost"
              size="sm"
              class="next-cost__danger"
              data-testid="budget-delete"
              @click="requestRemoveBudget(b)"
              >删除</UiButton
            >
          </div>
        </div>
        <p v-if="!budgets.length" class="next-cost__budget-empty" data-testid="budget-empty">
          本月还没有预算计划。
        </p>
      </div>
    </section>

    <section class="ui-panel">
      <div class="ui-panel-toolbar">
        <span class="ui-panel-sub">{{ mode === 'project' ? '按项目分摊' : '按天成本' }}</span>
      </div>
      <UiTable
        v-if="mode === 'project'"
        :columns="projectColumns"
        :data="projectGroups"
        :loading="loading"
        row-key="groupKey"
        empty-title="该时间窗口内没有成本数据"
        data-testid="cost-table"
      >
        <template #requests="{ row }">
          <span class="ui-num">{{ formatCount((row as UsageGroup).requests.upstream) }}</span>
        </template>
        <template #tokens="{ row }">
          <span class="ui-num">{{ formatCount(tokensOf(row as UsageGroup)) }}</span>
        </template>
        <template #cost="{ row }">
          <span class="ui-num">{{ formatCost(costOf(row as UsageGroup)) }}</span>
        </template>
        <template #share="{ row }">
          <div class="next-cost__share">
            <div class="next-cost__share-track">
              <div
                class="next-cost__share-fill"
                :style="{ width: `${Math.min(100, shareOf(row as UsageGroup))}%` }"
              />
            </div>
            <span class="ui-num">{{ shareOf(row as UsageGroup).toFixed(1) }}%</span>
          </div>
        </template>
      </UiTable>
      <UiTable
        v-else
        :columns="dayColumns"
        :data="dayGroups"
        :loading="loading"
        row-key="groupKey"
        empty-title="该时间窗口内没有成本数据"
        data-testid="cost-day-table"
      >
        <template #requests="{ row }">
          <span class="ui-num">{{ formatCount((row as UsageGroup).requests.upstream) }}</span>
        </template>
        <template #tokens="{ row }">
          <span class="ui-num">{{ formatCount(tokensOf(row as UsageGroup)) }}</span>
        </template>
        <template #cost="{ row }">
          <span class="ui-num">{{ formatCost(costOf(row as UsageGroup)) }}</span>
        </template>
      </UiTable>
    </section>

    <!-- Budget dialog -->
    <UiDialog
      :open="budgetDialogVisible"
      :title="editingBudget ? `编辑预算「${editingBudget.projectName}」` : '设置预算'"
      width="440px"
      @update:open="budgetDialogVisible = false"
    >
      <div class="next-cost__dialog-form">
        <UiSelect
          v-if="!editingBudget"
          v-model="budgetForm.projectId"
          label="项目"
          required
          placeholder="选择项目"
          :options="budgetProjectOptions"
          width="100%"
          data-testid="budget-project"
        />
        <UiInput
          v-model="budgetForm.amount"
          label="预算金额（CNY）"
          required
          type="number"
          data-testid="budget-amount"
        />
        <UiInput
          v-model="budgetForm.alertThresholdPct"
          label="预警阈值（%）"
          type="number"
          data-testid="budget-threshold"
        />
        <p v-if="budgetFormError" class="ui-form-error" data-testid="budget-form-error">
          {{ budgetFormError }}
        </p>
      </div>
      <template #footer>
        <UiButton variant="ghost" @click="budgetDialogVisible = false">取消</UiButton>
        <UiButton
          variant="primary"
          :loading="budgetSaving"
          data-testid="budget-save"
          @click="saveBudget"
        >
          保存
        </UiButton>
      </template>
    </UiDialog>

    <UiDialog
      v-if="confirmState"
      :open="true"
      :title="confirmState.title"
      :description="confirmState.body"
      width="440px"
      @update:open="confirmState = null"
    >
      <template #footer>
        <UiButton variant="ghost" @click="confirmState = null">取消</UiButton>
        <UiButton
          :variant="confirmState.tone === 'danger' ? 'danger' : 'primary'"
          @click="confirmAndRun"
        >
          {{ confirmState.confirmLabel }}
        </UiButton>
      </template>
    </UiDialog>
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

.next-cost__toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--ui-space-5);
}

.next-cost__segmented {
  display: inline-flex;
  gap: var(--ui-space-1);
  padding: var(--ui-space-1);
  background: var(--ui-muted);
  border: 1px solid var(--ui-border-muted);
  border-radius: var(--ui-radius-control);
}

.next-cost__seg {
  height: 30px;
  padding: 0 var(--ui-space-3);
  border: 0;
  border-radius: calc(var(--ui-radius-control) - 2px);
  background: transparent;
  color: var(--ui-foreground-secondary);
  font-size: var(--ui-font-size-sm);
  font-weight: var(--ui-weight-medium);
  cursor: pointer;
}

.next-cost__seg--on {
  background: var(--ui-card);
  border: 1px solid var(--ui-border);
  color: var(--ui-primary);
  font-weight: var(--ui-weight-semibold);
}

.next-cost__stats {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: var(--ui-space-4);
  margin-bottom: var(--ui-space-5);
}

@media (max-width: 1200px) {
  .next-cost__stats {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

.next-cost__stat {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-1);
  padding: var(--ui-space-4);
}

.next-cost__stat-label {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
}

.next-cost__stat-value {
  font-size: 20px;
  font-weight: var(--ui-weight-semibold);
  letter-spacing: -0.01em;
}

.next-cost__stat-value--accent {
  color: var(--ui-success-fg);
}

.next-cost__stat-hint {
  font-size: 11px;
  color: var(--ui-foreground-faint);
}

.next-cost__budget {
  margin-bottom: var(--ui-space-5);
}

.next-cost__budget-summary {
  display: flex;
  align-items: center;
  gap: var(--ui-space-4);
  margin-bottom: var(--ui-space-4);
}

.next-cost__budget-sum-label {
  flex-shrink: 0;
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
  white-space: nowrap;
}

.next-cost__budget-sum-label b {
  color: var(--ui-foreground);
}

.next-cost__budget-row {
  display: flex;
  align-items: center;
  gap: var(--ui-space-4);
  padding: var(--ui-space-3) 0;
  border-bottom: 1px solid var(--ui-border-muted);
}

.next-cost__budget-row:last-child {
  border-bottom: none;
}

.next-cost__budget-project {
  display: flex;
  flex-direction: column;
  min-width: 200px;
}

.next-cost__budget-name {
  font-weight: var(--ui-weight-medium);
}

.next-cost__budget-code {
  font-size: 11px;
  color: var(--ui-foreground-faint);
}

.next-cost__budget-figures {
  display: flex;
  align-items: center;
  gap: var(--ui-space-3);
  width: 260px;
  flex-shrink: 0;
  font-size: var(--ui-font-size-xs);
}

.next-cost__budget-track {
  flex: 1;
  height: 8px;
  border-radius: var(--ui-radius-pill);
  background: var(--ui-muted);
  overflow: hidden;
}

.next-cost__budget-fill {
  height: 100%;
  border-radius: var(--ui-radius-pill);
  background: var(--ui-primary);
}

.next-cost__budget-pct {
  width: 64px;
  text-align: right;
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
  flex-shrink: 0;
}

.next-cost__budget-actions {
  display: flex;
  gap: var(--ui-space-1);
  flex-shrink: 0;
}

.next-cost__danger {
  color: var(--ui-danger-fg);
}

.next-cost__budget-empty {
  margin: 0;
  font-size: var(--ui-font-size-sm);
  color: var(--ui-foreground-secondary);
}

.next-cost__share {
  display: flex;
  align-items: center;
  gap: var(--ui-space-3);
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
}

.next-cost__share-track {
  flex: 1;
  max-width: 160px;
  height: 6px;
  border-radius: var(--ui-radius-pill);
  background: var(--ui-muted);
  overflow: hidden;
}

.next-cost__share-fill {
  height: 100%;
  border-radius: var(--ui-radius-pill);
  background: var(--ui-primary);
  opacity: 0.7;
}

.next-cost__dialog-form {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-4);
}
</style>
