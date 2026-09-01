<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { confirmDialog } from '@/utils/confirm';
import type { PrimaryTableCol } from 'tdesign-vue-next';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import PageHeader from '@/components/PageHeader.vue';
import type { AlertRule, Project, WebhookEndpointView } from '@/types/api';

const rules = ref<AlertRule[]>([]);
const webhooks = ref<WebhookEndpointView[]>([]);
const projects = ref<Project[]>([]);
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');

const ruleColumns: PrimaryTableCol[] = [
  { colKey: 'name', title: '名称', minWidth: 160 },
  { colKey: 'type', title: '类型', width: 140 },
  { colKey: 'threshold', title: '阈值', width: 110, align: 'right' },
  { colKey: 'dedupeMinutes', title: '去重（分）', width: 110, align: 'right' },
  { colKey: 'status', title: '状态', width: 100 },
  { colKey: 'actions', title: '操作', width: 140, fixed: 'right' },
];

const creating = ref(false);
const form = ref({
  name: '',
  type: 'USAGE_MISSING_RATE',
  threshold: 0.5,
  dedupeMinutes: 60,
  webhookEndpointId: '',
  projectId: '',
});
const formError = ref('');
const submitting = ref(false);

const isBudgetType = computed(() => form.value.type === 'BUDGET_THRESHOLD');

async function load() {
  loading.value = true;
  try {
    const [ruleList, webhookList] = await Promise.all([api.listAlertRules(), api.listWebhooks()]);
    rules.value = ruleList;
    webhooks.value = webhookList;
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
    threshold: 0.5,
    dedupeMinutes: 60,
    webhookEndpointId: '',
    projectId: '',
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
  submitting.value = true;
  try {
    await api.createAlertRule({
      name: form.value.name.trim(),
      type: form.value.type,
      threshold: Number(form.value.threshold),
      dedupeMinutes: Number(form.value.dedupeMinutes) || 60,
      webhookEndpointId: form.value.webhookEndpointId || undefined,
      scopeJson: isBudgetType.value
        ? JSON.stringify({ projectId: form.value.projectId })
        : undefined,
    });
    creating.value = false;
    resetForm();
    await load();
  } catch (error) {
    formError.value = error instanceof ApiError ? error.message : '创建失败，请稍后重试。';
  } finally {
    submitting.value = false;
  }
}

async function toggle(rule: AlertRule) {
  await api.updateAlertRule(rule.id, { enabled: !rule.enabled });
  await load();
}

async function remove(rule: AlertRule) {
  try {
    await confirmDialog({
      header: '删除规则',
      body: `删除告警规则「${rule.name}」。`,
      confirmBtn: '删除',
      cancelBtn: '取消',
      theme: 'warning',
    });
  } catch {
    return;
  }
  await api.deleteAlertRule(rule.id);
  await load();
}

function typeLabel(type: string): string {
  switch (type) {
    case 'USAGE_MISSING_RATE':
      return 'usage 缺失率';
    case 'UPSTREAM_ERROR_RATE':
      return '上游错误率';
    case 'BALANCE_UNAVAILABLE':
      return '余额不可用';
    case 'USAGE_SURGE':
      return '用量激增';
    case 'BUDGET_THRESHOLD':
      return '预算水位';
    default:
      return type;
  }
}

function projectName(rule: AlertRule): string {
  if (!rule.scopeJson) {
    return '';
  }
  try {
    const scope = JSON.parse(rule.scopeJson) as { projectId?: string };
    const project = projects.value.find((p) => p.id === scope.projectId);
    return project ? `${project.name}（${project.code}）` : '';
  } catch {
    return '';
  }
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
  <div class="alert-rules-page">
    <PageHeader
      title="Alert Rules"
      description="指标阈值告警：命中后按小时去重，经 Webhook 签名投递。"
    />

    <t-alert v-if="loadError" theme="error" :close-btn="false" class="block-alert">
      {{ loadError
      }}<span v-if="loadRequestId" class="mk-mono">requestId: {{ loadRequestId }}</span>
    </t-alert>

    <div class="mk-filter-bar">
      <t-button theme="primary" data-testid="rule-create-open" @click="creating = !creating">
        {{ creating ? '收起表单' : '创建规则' }}
      </t-button>
    </div>

    <section v-if="creating" class="create-panel" data-testid="rule-create-form">
      <h3 class="panel-title">创建告警规则</h3>
      <t-form label-align="top" class="create-form">
        <t-form-item label="名称" required>
          <t-input v-model="form.name" data-testid="rule-create-name" />
        </t-form-item>
        <t-form-item label="类型">
          <t-select v-model="form.type">
            <t-option label="usage 缺失率" value="USAGE_MISSING_RATE" />
            <t-option label="上游错误率" value="UPSTREAM_ERROR_RATE" />
            <t-option label="余额不可用" value="BALANCE_UNAVAILABLE" />
            <t-option label="用量激增" value="USAGE_SURGE" />
            <t-option label="预算水位" value="BUDGET_THRESHOLD" />
          </t-select>
        </t-form-item>
        <t-form-item v-if="isBudgetType" label="项目" required>
          <t-select
            v-model="form.projectId"
            placeholder="选择项目"
            data-testid="rule-project-select"
          >
            <t-option
              v-for="p in projects"
              :key="p.id"
              :value="p.id"
              :label="`${p.name}（${p.code}）`"
            />
          </t-select>
        </t-form-item>
        <div class="form-row">
          <t-form-item :label="isBudgetType ? '阈值（水位 %）' : '阈值'">
            <t-input
              v-model="form.threshold"
              type="number"
              :step="isBudgetType ? 1 : 0.05"
              data-testid="rule-create-threshold"
            />
          </t-form-item>
          <t-form-item label="去重窗口（分钟）">
            <t-input v-model="form.dedupeMinutes" type="number" />
          </t-form-item>
        </div>
        <t-form-item label="Webhook 端点">
          <t-select v-model="form.webhookEndpointId" clearable placeholder="不选则仅记录事件">
            <t-option v-for="w in webhooks" :key="w.id" :label="w.name" :value="w.id" />
          </t-select>
        </t-form-item>
        <p v-if="formError" class="form-error">{{ formError }}</p>
        <t-button
          theme="primary"
          :loading="submitting"
          data-testid="rule-create-submit"
          @click="createRule"
        >
          创建规则
        </t-button>
      </t-form>
    </section>

    <t-loading :loading="loading" size="small" show-overlay>
      <t-table
        :data="rules"
        :columns="ruleColumns"
        row-key="id"
        size="small"
        data-testid="rules-table"
      >
        <template #type="{ row }">
          {{ typeLabel(row.type)
          }}<span
            v-if="row.type === 'BUDGET_THRESHOLD' && projectName(row)"
            class="mk-mono scope-hint"
            >· {{ projectName(row) }}</span
          >
        </template>
        <template #threshold="{ row }"
          ><span class="mk-num">{{ row.threshold }}</span></template
        >
        <template #dedupeMinutes="{ row }"
          ><span class="mk-num">{{ row.dedupeMinutes }}</span></template
        >
        <template #status="{ row }">
          <span
            class="mk-status"
            :class="row.enabled ? 'mk-status--success' : 'mk-status--neutral'"
          >
            {{ row.enabled ? 'Enabled' : 'Disabled' }}
          </span>
        </template>
        <template #actions="{ row }">
          <t-button variant="text" @click="toggle(row)">{{
            row.enabled ? '停用' : '启用'
          }}</t-button>
          <t-button variant="text" theme="danger" data-testid="rule-delete" @click="remove(row)"
            >删除</t-button
          >
        </template>
      </t-table>
    </t-loading>
  </div>
</template>

<style scoped>
.block-alert {
  margin-bottom: 16px;
}

.create-panel {
  border: 1px solid var(--miqrokey-border-default);
  border-radius: var(--miqrokey-radius-panel);
  background: var(--miqrokey-bg-surface);
  padding: 16px 20px 20px;
  margin-bottom: 20px;
  max-width: var(--miqrokey-content-form-max);
}

.panel-title {
  margin: 0 0 16px;
  font-size: 15px;
  font-weight: 600;
}

.create-form {
  max-width: 520px;
}

.form-row {
  display: flex;
  gap: 12px;
}

.form-row .t-form__item {
  flex: 1;
}

.form-error {
  margin-bottom: 12px;
  color: var(--miqrokey-danger);
}

.scope-hint {
  font-size: 12px;
  color: var(--miqrokey-text-secondary);
}
</style>
