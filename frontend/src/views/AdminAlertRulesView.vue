<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { DialogPlugin } from 'tdesign-vue-next';
import type { PrimaryTableCol } from 'tdesign-vue-next';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import PageHeader from '@/components/PageHeader.vue';
import type { AlertRule, WebhookEndpointView } from '@/types/api';

const rules = ref<AlertRule[]>([]);
const webhooks = ref<WebhookEndpointView[]>([]);
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
});
const formError = ref('');
const submitting = ref(false);

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

async function createRule() {
  if (!form.value.name.trim()) {
    formError.value = '规则名称必填。';
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
    });
    creating.value = false;
    form.value = {
      name: '',
      type: 'USAGE_MISSING_RATE',
      threshold: 0.5,
      dedupeMinutes: 60,
      webhookEndpointId: '',
    };
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
    await DialogPlugin.confirm({
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
    default:
      return type;
  }
}

onMounted(load);
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
          </t-select>
        </t-form-item>
        <div class="form-row">
          <t-form-item label="阈值">
            <t-input
              v-model="form.threshold"
              type="number"
              step="0.05"
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
        <template #type="{ row }">{{ typeLabel(row.type) }}</template>
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
</style>
