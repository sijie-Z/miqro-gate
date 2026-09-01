<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import type { PrimaryTableCol } from 'tdesign-vue-next';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import PageHeader from '@/components/PageHeader.vue';
import { confirmDialog } from '@/utils/confirm';
import type { BudgetView, Project, UsageGroup, UsageSummary } from '@/types/api';

const WINDOWS = [
  { label: '近 7 天', days: 7 },
  { label: '近 30 天', days: 30 },
  { label: '近 93 天', days: 93 },
] as const;

const windowDays = ref(30);
const mode = ref<'project' | 'day'>('project');
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');

const projectSummary = ref<UsageSummary | null>(null);
const daySummary = ref<UsageSummary | null>(null);

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
const cacheHits = computed(
  () =>
    (projectSummary.value?.totals.requests.l1Hit ?? 0) +
    (projectSummary.value?.totals.requests.l2Hit ?? 0),
);

function fromIso(days: number): string {
  const d = new Date();
  d.setDate(d.getDate() - days);
  return d.toISOString();
}

const projectColumns: PrimaryTableCol[] = [
  { colKey: 'label', title: '项目', minWidth: 200 },
  { colKey: 'requests', title: '请求', width: 100, align: 'right' },
  { colKey: 'tokens', title: 'Tokens', width: 140, align: 'right' },
  { colKey: 'cost', title: '分摊成本', width: 140, align: 'right' },
  { colKey: 'share', title: '占比', width: 220 },
];

const dayColumns: PrimaryTableCol[] = [
  { colKey: 'label', title: '日期', minWidth: 140 },
  { colKey: 'requests', title: '请求', width: 100, align: 'right' },
  { colKey: 'tokens', title: 'Tokens', width: 140, align: 'right' },
  { colKey: 'cost', title: '分摊成本', width: 140, align: 'right' },
];

function costNumber(value: string | undefined): number {
  return Number(value ?? '0');
}

function costOf(group: UsageGroup): number {
  return costNumber(group.cost.projectAllocated);
}

function tokensOf(group: UsageGroup): number {
  return (group.tokens.input ?? 0) + (group.tokens.output ?? 0);
}

function shareOf(group: UsageGroup): number {
  const total = costNumber(totalCost.value);
  return total > 0 ? costOf(group) / total : 0;
}

function formatCost(value: string | number): string {
  return `¥${Number(value).toFixed(4)}`;
}

function formatCount(value: number): string {
  if (value >= 1_000_000) {
    return `${(value / 1_000_000).toFixed(1)}M`;
  }
  if (value >= 1_000) {
    return `${(value / 1_000).toFixed(1)}k`;
  }
  return String(value);
}

async function load() {
  loading.value = true;
  loadError.value = '';
  const from = fromIso(windowDays.value);
  try {
    const [projects, days] = await Promise.all([
      api.adminUsageSummary({ groupBy: 'project', from }),
      api.adminUsageSummary({ groupBy: 'day', from }),
    ]);
    projectSummary.value = projects;
    daySummary.value = days;
  } catch (error) {
    if (error instanceof ApiError) {
      loadError.value = error.message;
      loadRequestId.value = error.requestId ?? '';
    } else {
      loadError.value = '加载成本报表失败。';
    }
  } finally {
    loading.value = false;
  }
}

function exportCsv() {
  const groups = mode.value === 'project' ? projectGroups.value : dayGroups.value;
  if (!groups.length) {
    MessagePlugin.warning('当前筛选下没有数据可导出');
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
  a.download = `cost-report-${mode.value}-${fromIso(windowDays.value).slice(0, 10)}.csv`;
  a.click();
  URL.revokeObjectURL(url);
}

function switchWindow(days: number) {
  windowDays.value = days;
  void load();
}

// ---- monthly budget (G8.2) ----

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
  budgets.value.reduce((sum, b) => sum + Number(b.amount), 0),
);
const budgetTotalSpent = computed(() => budgets.value.reduce((sum, b) => sum + Number(b.spent), 0));
const budgetOverallPct = computed(() =>
  budgetTotalAmount.value > 0 ? (budgetTotalSpent.value / budgetTotalAmount.value) * 100 : 0,
);

const budgetLevelLabel: Record<string, string> = {
  NORMAL: '正常',
  WARNING: '预警',
  EXCEEDED: '超限',
};

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
    MessagePlugin.success('预算已保存');
    await loadBudgets();
  } catch (error) {
    budgetFormError.value = error instanceof ApiError ? error.message : '保存失败，请稍后重试。';
  } finally {
    budgetSaving.value = false;
  }
}

async function removeBudget(budget: BudgetView) {
  try {
    await confirmDialog({
      header: `删除预算「${budget.projectName}」`,
      body: `删除后 ${budget.month} 的预算计划将被移除，用量与成本数据不受影响。`,
      confirmBtn: '删除',
      cancelBtn: '取消',
      theme: 'warning',
    });
  } catch {
    return;
  }
  try {
    await api.deleteProjectBudget(budget.projectId, budget.month);
    MessagePlugin.success('预算已删除');
    await loadBudgets();
  } catch (error) {
    if (error instanceof ApiError) {
      MessagePlugin.error(error.message);
    }
  }
}

function levelClass(level: string): string {
  return level === 'EXCEEDED' ? 'danger' : level === 'WARNING' ? 'warning' : 'success';
}

function fillClass(level: string): string {
  return level === 'EXCEEDED' ? 'fill-danger' : level === 'WARNING' ? 'fill-warning' : '';
}

onMounted(() => {
  void load();
  void loadBudgets();
});
</script>

<template>
  <div class="cost-page">
    <PageHeader
      title="成本报表"
      description="按项目与时间维度的成本分摊视图；单价来自「定价」目录，分摊结果由成本计算任务生成。"
    >
      <template #actions>
        <t-button variant="outline" data-testid="cost-export" @click="exportCsv">导出 CSV</t-button>
      </template>
    </PageHeader>

    <t-alert v-if="loadError" theme="error" class="block-alert" data-testid="cost-load-error">
      {{ loadError
      }}<span v-if="loadRequestId" class="mk-mono">requestId: {{ loadRequestId }}</span>
    </t-alert>

    <div class="mk-filter-bar" data-testid="cost-filter-bar">
      <t-radio-group v-model="mode" variant="default-filled" data-testid="cost-mode" @change="load">
        <t-radio-button value="project">按项目</t-radio-button>
        <t-radio-button value="day">按天</t-radio-button>
      </t-radio-group>
      <t-radio-group
        :value="windowDays"
        variant="default-filled"
        data-testid="cost-window"
        @change="switchWindow"
      >
        <t-radio-button v-for="w in WINDOWS" :key="w.days" :value="w.days">
          {{ w.label }}
        </t-radio-button>
      </t-radio-group>
    </div>

    <div class="mk-stat-grid" data-testid="cost-stats">
      <div class="mk-stat-card">
        <span class="mk-stat-label">分摊总成本</span>
        <span class="mk-stat-value mk-num">{{ formatCost(totalCost) }}</span>
        <span class="mk-stat-hint">按项目分摊口径</span>
      </div>
      <div class="mk-stat-card">
        <span class="mk-stat-label">上游已付成本</span>
        <span class="mk-stat-value mk-num">{{ formatCost(upstreamCost) }}</span>
        <span class="mk-stat-hint">按最新单价估算</span>
      </div>
      <div class="mk-stat-card">
        <span class="mk-stat-label">请求</span>
        <span class="mk-stat-value mk-num">{{ formatCount(totalRequests) }}</span>
        <span class="mk-stat-hint">到达上游的请求数</span>
      </div>
      <div class="mk-stat-card">
        <span class="mk-stat-label">Tokens</span>
        <span class="mk-stat-value mk-num">{{ formatCount(totalTokens) }}</span>
        <span class="mk-stat-hint">输入 + 输出</span>
      </div>
      <div class="mk-stat-card">
        <span class="mk-stat-label">缓存节省</span>
        <span class="mk-stat-value mk-num">{{ formatCost(cacheSaved) }}</span>
        <span class="mk-stat-hint">命中 {{ formatCount(cacheHits) }} 次 · 未调用上游</span>
      </div>
    </div>

    <section class="budget-panel" data-testid="cost-budget-panel">
      <div class="budget-header">
        <div>
          <h3 class="panel-title">月度预算 · {{ budgetMonth }}</h3>
          <p class="hint">
            只预警不阻断；达到阈值进入预警，超过 100% 标记超限。水位按当月分摊成本实时计算。
          </p>
        </div>
        <t-button
          theme="primary"
          variant="outline"
          data-testid="budget-create-open"
          @click="openBudgetDialog(null)"
        >
          设置预算
        </t-button>
      </div>
      <t-loading :loading="budgetLoading" size="small">
        <div v-if="budgets.length" class="budget-summary-row" data-testid="budget-summary">
          <span class="budget-summary-label">
            总预算 <b class="mk-num">{{ formatCost(budgetTotalAmount) }}</b>
          </span>
          <span class="budget-summary-label">
            已花费 <b class="mk-num">{{ formatCost(budgetTotalSpent) }}</b>
          </span>
          <div class="share-track budget-track">
            <div
              class="share-fill"
              :class="
                budgetOverallPct >= 100
                  ? 'fill-danger'
                  : budgetOverallPct >= 80
                    ? 'fill-warning'
                    : ''
              "
              :style="{ width: `${Math.min(budgetOverallPct, 100)}%` }"
            />
          </div>
          <span class="mk-num">{{ budgetOverallPct.toFixed(1) }}%</span>
        </div>
        <div v-if="budgets.length" class="budget-rows">
          <div v-for="b in budgets" :key="b.projectId" class="budget-row" data-testid="budget-row">
            <div class="budget-project">
              <span class="budget-name">{{ b.projectName }}</span>
              <span class="mk-mono budget-code">{{ b.projectCode }}</span>
              <span
                class="mk-status"
                :class="`mk-status--${levelClass(b.level)}`"
                :data-testid="`budget-level-${b.projectCode}`"
              >
                {{ budgetLevelLabel[b.level] }}
              </span>
            </div>
            <div class="budget-metrics">
              <span class="mk-num budget-figures"
                >{{ formatCost(Number(b.spent)) }} / {{ formatCost(Number(b.amount)) }}</span
              >
              <div class="share-track budget-track">
                <div
                  class="share-fill"
                  :class="fillClass(b.level)"
                  :style="{ width: `${Math.min(Number(b.spentPct), 100)}%` }"
                />
              </div>
              <span class="mk-num budget-pct">{{ Number(b.spentPct).toFixed(1) }}%</span>
            </div>
            <div class="budget-actions">
              <t-button variant="text" data-testid="budget-edit" @click="openBudgetDialog(b)"
                >编辑</t-button
              >
              <t-button
                variant="text"
                theme="danger"
                data-testid="budget-delete"
                @click="removeBudget(b)"
              >
                删除
              </t-button>
            </div>
          </div>
        </div>
        <div v-else class="budget-empty">
          <span>本月还没有预算计划。</span>
          <t-link theme="primary" data-testid="budget-empty-set" @click="openBudgetDialog(null)"
            >立即设置</t-link
          >
        </div>
      </t-loading>
    </section>

    <t-dialog
      v-model:visible="budgetDialogVisible"
      :header="editingBudget ? `编辑预算「${editingBudget.projectName}」` : '设置月度预算'"
      width="480px"
      :close-on-overlay-click="false"
    >
      <t-form label-align="top">
        <t-form-item label="项目" required-mark>
          <t-select
            v-model="budgetForm.projectId"
            :disabled="!!editingBudget"
            placeholder="选择项目"
            data-testid="budget-project-select"
          >
            <t-option
              v-for="p in projects"
              :key="p.id"
              :value="p.id"
              :label="`${p.name}（${p.code}）`"
            />
          </t-select>
        </t-form-item>
        <t-form-item label="预算金额（CNY）" required-mark>
          <t-input
            v-model="budgetForm.amount"
            type="number"
            placeholder="例如 5000"
            data-testid="budget-amount"
          />
        </t-form-item>
        <t-form-item label="预警阈值（%）" required-mark>
          <t-input
            v-model="budgetForm.alertThresholdPct"
            type="number"
            data-testid="budget-threshold"
          />
        </t-form-item>
        <p v-if="budgetFormError" class="form-error">{{ budgetFormError }}</p>
      </t-form>
      <template #footer>
        <t-button
          theme="primary"
          :loading="budgetSaving"
          data-testid="budget-save"
          @click="saveBudget"
          >保存</t-button
        >
        <t-button @click="budgetDialogVisible = false">取消</t-button>
      </template>
    </t-dialog>

    <t-loading :loading="loading" size="small" show-overlay>
      <t-table
        v-if="mode === 'project'"
        row-key="groupKey"
        size="small"
        :columns="projectColumns"
        :data="projectGroups"
        class="cost-table"
        data-testid="cost-project-table"
      >
        <template #label="{ row }">
          <span class="cost-label">{{ row.label }}</span>
        </template>
        <template #requests="{ row }">
          <span class="mk-num">{{ formatCount(row.requests.upstream) }}</span>
        </template>
        <template #tokens="{ row }">
          <span class="mk-num">{{ formatCount(tokensOf(row)) }}</span>
        </template>
        <template #cost="{ row }">
          <span class="mk-num">{{ formatCost(costOf(row)) }}</span>
        </template>
        <template #share="{ row }">
          <div class="share-cell">
            <div class="share-track">
              <div class="share-fill" :style="{ width: `${Math.round(shareOf(row) * 100)}%` }" />
            </div>
            <span class="mk-num share-text">{{ Math.round(shareOf(row) * 100) }}%</span>
          </div>
        </template>
        <template #empty>
          <div class="table-empty">
            <p>该时间窗口内没有成本数据。</p>
            <p class="hint">在「定价」页录入单价后，成本才会被计算。</p>
          </div>
        </template>
      </t-table>

      <t-table
        v-else
        row-key="groupKey"
        size="small"
        :columns="dayColumns"
        :data="dayGroups"
        class="cost-table"
        data-testid="cost-day-table"
      >
        <template #label="{ row }">
          <span class="cost-label">{{ row.label }}</span>
        </template>
        <template #requests="{ row }">
          <span class="mk-num">{{ formatCount(row.requests.upstream) }}</span>
        </template>
        <template #tokens="{ row }">
          <span class="mk-num">{{ formatCount(tokensOf(row)) }}</span>
        </template>
        <template #cost="{ row }">
          <span class="mk-num">{{ formatCost(costOf(row)) }}</span>
        </template>
        <template #empty>
          <div class="table-empty">该时间窗口内没有成本数据。</div>
        </template>
      </t-table>
    </t-loading>
  </div>
</template>

<style scoped>
.block-alert {
  margin-bottom: 16px;
}

.budget-panel {
  border: 1px solid var(--miqrokey-border-default);
  border-radius: var(--miqrokey-radius-panel);
  background: var(--miqrokey-bg-surface);
  padding: 16px 20px 20px;
  margin-bottom: 20px;
}

.budget-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
}

.panel-title {
  margin: 0 0 4px;
  font-size: 15px;
  font-weight: 600;
}

.budget-summary-row {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 10px 12px;
  border: 1px solid var(--miqrokey-border-muted);
  border-radius: var(--miqrokey-radius-panel);
  background: var(--miqrokey-bg-subtle);
  margin-bottom: 12px;
}

.budget-summary-label {
  font-size: 13px;
  color: var(--miqrokey-text-secondary);
  white-space: nowrap;
}

.budget-summary-label b {
  color: var(--miqrokey-text-primary);
}

.budget-rows {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.budget-row {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 10px 12px;
  border: 1px solid var(--miqrokey-border-muted);
  border-radius: var(--miqrokey-radius-panel);
}

.budget-project {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 220px;
}

.budget-name {
  font-weight: 500;
}

.budget-code {
  font-size: 12px;
  color: var(--miqrokey-text-secondary);
}

.budget-metrics {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: 1;
}

.budget-figures {
  font-size: 13px;
  white-space: nowrap;
}

.budget-track {
  flex: 1;
  max-width: 320px;
}

.budget-pct {
  min-width: 52px;
  text-align: right;
  font-size: 12px;
}

.budget-actions {
  display: flex;
  gap: 4px;
}

.budget-empty {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 16px 0;
  color: var(--miqrokey-text-secondary);
  font-size: 13px;
}

.form-error {
  margin: 0 0 8px;
  color: var(--miqrokey-danger);
}

.share-fill.fill-warning {
  background: var(--miqrokey-warning);
}

.share-fill.fill-danger {
  background: var(--miqrokey-danger);
}

.cost-table {
  width: 100%;
  background: var(--miqrokey-bg-surface);
  border: 1px solid var(--miqrokey-border-default);
  border-radius: var(--miqrokey-radius-panel);
}

.cost-label {
  font-weight: 500;
}

.share-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}

.share-track {
  flex: 1;
  height: 6px;
  border-radius: 3px;
  background: var(--miqrokey-bg-subtle);
  overflow: hidden;
}

.share-fill {
  height: 100%;
  border-radius: 3px;
  background: var(--miqrokey-accent);
}

.share-text {
  min-width: 40px;
  text-align: right;
  font-size: 12px;
}

.table-empty {
  padding: 24px 0;
  color: var(--miqrokey-text-secondary);
}

.table-empty .hint {
  font-size: 13px;
  color: var(--miqrokey-text-disabled);
  margin: 4px 0 0;
}
</style>
