<script setup lang="ts">
/**
 * NextAdminAlertRulesView — /app/alert-rules v2 admin page (U2 ops batch).
 * Behaviour parity with the legacy alert-rules page: create threshold rules
 * (metric/budget/quota watermarks) and event-driven model-approval rules
 * (no threshold — fired by the workflow itself), toggle and gated delete.
 */
import { computed, onMounted, ref } from 'vue';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import { UiButton, UiDialog, UiInput, UiSelect, UiStatusBadge, UiTable, toast } from '@/ui';
import type { AlertRule, Project, QuotaRuleView, WebhookEndpointView } from '@/types/api';

const rules = ref<AlertRule[]>([]);
const webhooks = ref<WebhookEndpointView[]>([]);
const projects = ref<Project[]>([]);
const quotaRules = ref<QuotaRuleView[]>([]);
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');

const ruleColumns = [
  { key: 'name', title: '名称', minWidth: '180px' },
  { key: 'type', title: '类型', minWidth: '200px' },
  { key: 'threshold', title: '阈值', width: '110px', align: 'right' as const },
  { key: 'dedupeMinutes', title: '去重（分）', width: '120px', align: 'right' as const },
  { key: 'status', title: '状态', width: '110px' },
  { key: 'createdAt', title: '创建时间', width: '170px' },
  { key: 'actions', title: '操作', width: '150px', align: 'center' as const },
];

const typeOptions = [
  { value: 'USAGE_MISSING_RATE', label: 'usage 缺失率' },
  { value: 'UPSTREAM_ERROR_RATE', label: '上游错误率' },
  { value: 'BALANCE_UNAVAILABLE', label: '余额不可用' },
  { value: 'USAGE_SURGE', label: '用量激增' },
  { value: 'BUDGET_THRESHOLD', label: '预算水位' },
  { value: 'QUOTA_THRESHOLD', label: '配额水位' },
  { value: 'MODEL_APPROVAL_SUBMITTED', label: '模型审批 · 提交' },
  { value: 'MODEL_APPROVAL_APPROVED', label: '模型审批 · 通过' },
  { value: 'MODEL_APPROVAL_REJECTED', label: '模型审批 · 驳回' },
];

const creating = ref(false);
const form = ref({
  name: '',
  type: 'USAGE_MISSING_RATE',
  threshold: '0.5',
  dedupeMinutes: '60',
  webhookEndpointId: '',
  projectId: '',
  quotaRuleId: '',
});
const formError = ref('');
const submitting = ref(false);

const confirmState = ref<{
  title: string;
  body: string;
  confirmLabel: string;
  tone: 'danger' | 'primary';
  run: () => Promise<void>;
} | null>(null);

const isBudgetType = computed(() => form.value.type === 'BUDGET_THRESHOLD');
const isQuotaType = computed(() => form.value.type === 'QUOTA_THRESHOLD');
const isWatermarkType = computed(() => isBudgetType.value || isQuotaType.value);
/** Event-driven rule types (F03): fired by the workflow itself, no threshold. */
const isApprovalType = computed(() => form.value.type.startsWith('MODEL_APPROVAL_'));

const metricText: Record<string, string> = { TOKENS: 'Token', REQUESTS: '请求' };
const periodText: Record<string, string> = { DAILY: '日', WEEKLY: '周', MONTHLY: '月' };

const projectOptions = computed(() =>
  projects.value.map((p) => ({ value: p.id, label: `${p.name}（${p.code}）` })),
);

const quotaOptions = computed(() =>
  quotaRules.value.map((q) => ({
    value: q.id,
    label: `${q.scopeName ?? q.scopeId}（${metricText[q.metric] ?? q.metric}·${periodText[q.period] ?? q.period}）`,
  })),
);

const webhookOptions = computed(() => webhooks.value.map((w) => ({ value: w.id, label: w.name })));

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    const [ruleList, webhookList, quotaList] = await Promise.all([
      api.listAlertRules(),
      api.listWebhooks(),
      api.listQuotaRules(),
    ]);
    rules.value = ruleList;
    webhooks.value = webhookList;
    quotaRules.value = quotaList;
  } catch (error) {
    if (error instanceof ApiError) {
      loadError.value = error.message;
      loadRequestId.value = error.requestId ?? '';
    }
  } finally {
    loading.value = false;
  }
}

function resetForm() {
  form.value = {
    name: '',
    type: 'USAGE_MISSING_RATE',
    threshold: '0.5',
    dedupeMinutes: '60',
    webhookEndpointId: '',
    projectId: '',
    quotaRuleId: '',
  };
}

async function createRule() {
  if (!form.value.name.trim()) {
    formError.value = '规则名称必填。';
    return;
  }
  if (isBudgetType.value && !form.value.projectId) {
    formError.value = '预算水位规则必须选择项目。';
    return;
  }
  if (isQuotaType.value && !form.value.quotaRuleId) {
    formError.value = '配额水位规则必须选择配额规则。';
    return;
  }
  submitting.value = true;
  formError.value = '';
  try {
    await api.createAlertRule({
      name: form.value.name.trim(),
      type: form.value.type,
      threshold: isApprovalType.value ? 1 : Number(form.value.threshold),
      dedupeMinutes: Number(form.value.dedupeMinutes) || 60,
      webhookEndpointId: form.value.webhookEndpointId || undefined,
      scopeJson: isBudgetType.value
        ? JSON.stringify({ projectId: form.value.projectId })
        : isQuotaType.value
          ? JSON.stringify({ quotaRuleId: form.value.quotaRuleId })
          : undefined,
    });
    creating.value = false;
    resetForm();
    toast.success('告警规则已创建');
    await load();
  } catch (error) {
    formError.value = error instanceof ApiError ? error.message : '创建失败，请稍后重试。';
  } finally {
    submitting.value = false;
  }
}

async function toggle(rule: AlertRule) {
  try {
    await api.updateAlertRule(rule.id, { enabled: !rule.enabled });
    toast.success(rule.enabled ? '规则已停用' : '规则已启用');
    await load();
  } catch (error) {
    if (error instanceof ApiError) {
      toast.error(error.message);
    }
  }
}

function requestRemove(rule: AlertRule) {
  confirmState.value = {
    title: '删除规则',
    body: `删除告警规则「${rule.name}」。`,
    confirmLabel: '删除',
    tone: 'danger',
    run: async () => {
      try {
        await api.deleteAlertRule(rule.id);
        toast.success('告警规则已删除');
        await load();
      } catch (error) {
        if (error instanceof ApiError) {
          toast.error(error.message);
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

function typeLabel(type: string): string {
  return typeOptions.find((o) => o.value === type)?.label ?? type;
}

function scopeHint(rule: AlertRule): string {
  if (!rule.scopeJson) {
    return '';
  }
  try {
    if (rule.type === 'BUDGET_THRESHOLD') {
      const scope = JSON.parse(rule.scopeJson) as { projectId?: string };
      const project = projects.value.find((p) => p.id === scope.projectId);
      return project ? `${project.name}（${project.code}）` : '';
    }
    if (rule.type === 'QUOTA_THRESHOLD') {
      const scope = JSON.parse(rule.scopeJson) as { quotaRuleId?: string };
      const quota = quotaRules.value.find((q) => q.id === scope.quotaRuleId);
      if (!quota) {
        return '';
      }
      const dim = metricText[quota.metric] ?? quota.metric;
      const period = periodText[quota.period] ?? quota.period;
      return `${quota.scopeName ?? ''}（${dim}·${period}）`;
    }
    return '';
  } catch {
    return '';
  }
}

function formatTime(iso?: string): string {
  if (!iso) return '—';
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

onMounted(() => {
  void load();
  api
    .listProjects()
    .then((list) => {
      projects.value = list;
    })
    .catch(() => {
      projects.value = [];
    });
});
</script>

<template>
  <div class="ui-page next-alert-rules">
    <header class="ui-page-header">
      <div>
        <h1 class="ui-page-title">告警规则</h1>
        <p class="ui-page-desc">
          阈值告警命中后按去重窗口收敛；模型审批等事件型规则即时触发，均经 Webhook 签名投递。
        </p>
      </div>
      <div class="ui-page-actions">
        <UiButton variant="primary" data-testid="rule-create-open" @click="creating = !creating">
          {{ creating ? '收起表单' : '创建规则' }}
        </UiButton>
      </div>
    </header>

    <div v-if="loadError" class="ui-alert ui-alert--error">
      {{ loadError
      }}<span v-if="loadRequestId" class="ui-request-id"> requestId: {{ loadRequestId }}</span>
    </div>

    <section
      v-if="creating"
      class="ui-panel next-alert-rules__create"
      data-testid="rule-create-form"
    >
      <div class="ui-panel-head">
        <h2 class="ui-panel-title">创建告警规则</h2>
      </div>
      <div class="ui-panel-body">
        <div class="next-alert-rules__form">
          <UiInput
            v-model="form.name"
            label="名称"
            required
            placeholder="例如 usage-missing"
            data-testid="rule-create-name"
          />
          <UiSelect v-model="form.type" label="类型" :options="typeOptions" />
          <UiSelect
            v-if="isBudgetType"
            v-model="form.projectId"
            label="项目"
            required
            :options="projectOptions"
            placeholder="选择项目"
            data-testid="rule-project-select"
          />
          <UiSelect
            v-else-if="isQuotaType"
            v-model="form.quotaRuleId"
            label="配额规则"
            required
            :options="quotaOptions"
            placeholder="选择配额规则"
            data-testid="rule-quota-select"
          />
          <template v-if="!isApprovalType">
            <div class="next-alert-rules__row">
              <UiInput
                v-model="form.threshold"
                :label="isWatermarkType ? '阈值（水位 %）' : '阈值'"
                placeholder="例如 0.5"
                data-testid="rule-create-threshold"
              />
              <UiInput
                v-model="form.dedupeMinutes"
                label="去重窗口（分钟）"
                placeholder="60"
                data-testid="rule-create-dedupe"
              />
            </div>
          </template>
          <p v-else class="next-alert-rules__approval-hint" data-testid="rule-approval-hint">
            事件型规则：模型审批发生时立即通知（无阈值/去重窗口）。
          </p>
          <UiSelect
            v-model="form.webhookEndpointId"
            label="Webhook 端点"
            :options="webhookOptions"
            placeholder="不选则仅记录事件"
          />
          <p v-if="formError" class="ui-form-error">{{ formError }}</p>
          <div class="next-alert-rules__actions">
            <UiButton
              variant="primary"
              :loading="submitting"
              data-testid="rule-create-submit"
              @click="createRule"
              >创建规则</UiButton
            >
            <UiButton variant="ghost" @click="creating = false">取消</UiButton>
          </div>
        </div>
      </div>
    </section>

    <section class="ui-panel">
      <div class="ui-panel-toolbar">
        <span class="ui-panel-sub">共 {{ rules.length }} 条规则</span>
      </div>
      <UiTable
        :columns="ruleColumns"
        :data="rules"
        :loading="loading"
        row-key="id"
        empty-title="还没有告警规则"
        empty-description="创建阈值或事件型规则后，命中即经 Webhook 签名投递。"
        data-testid="rules-table"
      >
        <template #name="{ row }">
          <span class="next-alert-rules__name">{{ (row as AlertRule).name }}</span>
        </template>
        <template #type="{ row }">
          {{ typeLabel((row as AlertRule).type)
          }}<span v-if="scopeHint(row as AlertRule)" class="ui-mono next-alert-rules__scope"
            >· {{ scopeHint(row as AlertRule) }}</span
          >
        </template>
        <template #threshold="{ row }">
          <span class="ui-num">{{ (row as AlertRule).threshold }}</span>
        </template>
        <template #dedupeMinutes="{ row }">
          <span class="ui-num">{{ (row as AlertRule).dedupeMinutes }}</span>
        </template>
        <template #status="{ row }">
          <UiStatusBadge
            :tone="(row as AlertRule).enabled ? 'success' : 'neutral'"
            :label="(row as AlertRule).enabled ? '已启用' : '已停用'"
          />
        </template>
        <template #createdAt="{ row }">{{ formatTime((row as AlertRule).createdAt) }}</template>
        <template #actions="{ row }">
          <div class="next-alert-rules__actions">
            <UiButton
              variant="ghost"
              size="sm"
              data-testid="rule-toggle"
              @click="toggle(row as AlertRule)"
              >{{ (row as AlertRule).enabled ? '停用' : '启用' }}</UiButton
            >
            <UiButton
              variant="ghost"
              size="sm"
              class="next-alert-rules__danger"
              data-testid="rule-delete"
              @click="requestRemove(row as AlertRule)"
              >删除</UiButton
            >
          </div>
        </template>
      </UiTable>
    </section>

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

.next-alert-rules__create {
  margin-bottom: var(--ui-space-5);
  max-width: 720px;
}

.next-alert-rules__form {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-4);
  max-width: 560px;
}

.next-alert-rules__row {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--ui-space-4);
}

.next-alert-rules__approval-hint {
  margin: 0;
  padding: var(--ui-space-3) var(--ui-space-4);
  border-radius: var(--ui-radius-control);
  background: var(--ui-info-bg);
  color: var(--ui-info-fg);
  font-size: var(--ui-font-size-sm);
  line-height: var(--ui-line-height-base);
}

.next-alert-rules__actions {
  display: inline-flex;
  flex-wrap: wrap;
  gap: var(--ui-space-1);
}

.next-alert-rules__name {
  font-weight: var(--ui-weight-medium);
}

.next-alert-rules__scope {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-faint);
}

.next-alert-rules__danger {
  color: var(--ui-danger-fg);
}
</style>
