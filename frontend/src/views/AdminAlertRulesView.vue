<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { ElMessageBox } from 'element-plus';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import PageHeader from '@/components/PageHeader.vue';
import type { AlertRule, WebhookEndpointView } from '@/types/api';

const rules = ref<AlertRule[]>([]);
const webhooks = ref<WebhookEndpointView[]>([]);
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');

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
    await ElMessageBox.confirm(`删除告警规则「${rule.name}」。`, '删除规则', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
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

    <el-alert v-if="loadError" type="error" :closable="false" class="block-alert">
      {{ loadError
      }}<span v-if="loadRequestId" class="mk-mono">requestId: {{ loadRequestId }}</span>
    </el-alert>

    <div class="mk-filter-bar">
      <el-button type="primary" data-testid="rule-create-open" @click="creating = !creating">
        {{ creating ? '收起表单' : '创建规则' }}
      </el-button>
    </div>

    <section v-if="creating" class="create-panel" data-testid="rule-create-form">
      <h3 class="panel-title">创建告警规则</h3>
      <el-form label-position="top" class="create-form">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" data-testid="rule-create-name" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.type">
            <el-option label="usage 缺失率" value="USAGE_MISSING_RATE" />
            <el-option label="上游错误率" value="UPSTREAM_ERROR_RATE" />
            <el-option label="余额不可用" value="BALANCE_UNAVAILABLE" />
            <el-option label="用量激增" value="USAGE_SURGE" />
          </el-select>
        </el-form-item>
        <div class="form-row">
          <el-form-item label="阈值">
            <el-input
              v-model.number="form.threshold"
              type="number"
              step="0.05"
              data-testid="rule-create-threshold"
            />
          </el-form-item>
          <el-form-item label="去重窗口（分钟）">
            <el-input v-model.number="form.dedupeMinutes" type="number" />
          </el-form-item>
        </div>
        <el-form-item label="Webhook 端点">
          <el-select v-model="form.webhookEndpointId" clearable placeholder="不选则仅记录事件">
            <el-option v-for="w in webhooks" :key="w.id" :label="w.name" :value="w.id" />
          </el-select>
        </el-form-item>
        <p v-if="formError" class="form-error">{{ formError }}</p>
        <el-button
          type="primary"
          :loading="submitting"
          data-testid="rule-create-submit"
          @click="createRule"
        >
          创建规则
        </el-button>
      </el-form>
    </section>

    <el-table v-loading="loading" :data="rules" data-testid="rules-table">
      <el-table-column prop="name" label="名称" min-width="160" />
      <el-table-column label="类型" width="140">
        <template #default="{ row }">{{ typeLabel(row.type) }}</template>
      </el-table-column>
      <el-table-column label="阈值" width="110" align="right">
        <template #default="{ row }"
          ><span class="mk-num">{{ row.threshold }}</span></template
        >
      </el-table-column>
      <el-table-column label="去重（分）" width="110" align="right">
        <template #default="{ row }"
          ><span class="mk-num">{{ row.dedupeMinutes }}</span></template
        >
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <span
            class="mk-status"
            :class="row.enabled ? 'mk-status--success' : 'mk-status--neutral'"
          >
            {{ row.enabled ? 'Enabled' : 'Disabled' }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button link @click="toggle(row)">{{ row.enabled ? '停用' : '启用' }}</el-button>
          <el-button link type="danger" data-testid="rule-delete" @click="remove(row)"
            >删除</el-button
          >
        </template>
      </el-table-column>
    </el-table>
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

.form-row .el-form-item {
  flex: 1;
}

.form-error {
  margin-bottom: 12px;
  color: var(--miqrokey-danger);
}
</style>
