<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import PageHeader from '@/components/PageHeader.vue';
import type { WebhookDelivery, WebhookEndpointView } from '@/types/api';

const webhooks = ref<WebhookEndpointView[]>([]);
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');

const creating = ref(false);
const form = ref({ name: '', url: '', secret: '', timeoutMs: 5000 });
const formError = ref('');
const submitting = ref(false);

const deliveriesDrawer = ref(false);
const deliveries = ref<WebhookDelivery[]>([]);
const deliveriesLoading = ref(false);

async function load() {
  loading.value = true;
  try {
    webhooks.value = await api.listWebhooks();
  } catch (error) {
    if (error instanceof ApiError) {
      loadError.value = error.message;
      loadRequestId.value = error.requestId ?? '';
    }
  } finally {
    loading.value = false;
  }
}

async function createWebhook() {
  if (!form.value.name.trim() || !form.value.url.trim() || !form.value.secret.trim()) {
    formError.value = '名称、URL 与签名 Secret 必填。';
    return;
  }
  submitting.value = true;
  try {
    await api.createWebhook({ ...form.value, timeoutMs: Number(form.value.timeoutMs) || 5000 });
    creating.value = false;
    form.value = { name: '', url: '', secret: '', timeoutMs: 5000 };
    await load();
  } catch (error) {
    formError.value = error instanceof ApiError ? error.message : '创建失败，请稍后重试。';
  } finally {
    submitting.value = false;
  }
}

async function toggle(endpoint: WebhookEndpointView) {
  await api.updateWebhook(endpoint.id, { enabled: !endpoint.enabled });
  await load();
}

async function test(endpoint: WebhookEndpointView) {
  try {
    const result = await api.testWebhook(endpoint.id);
    if (result.httpStatus) {
      ElMessage.success(`测试投递成功（HTTP ${result.httpStatus}）`);
    } else {
      ElMessage.error(`测试投递失败：${result.errorMessage ?? '未知错误'}`);
    }
  } catch (error) {
    if (error instanceof ApiError) {
      ElMessage.error(error.message);
    }
  }
}

async function remove(endpoint: WebhookEndpointView) {
  try {
    await ElMessageBox.confirm(
      `删除 Webhook「${endpoint.name}」后告警将不再投递到该端点。`,
      '删除 Webhook',
      {
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        type: 'warning',
      },
    );
  } catch {
    return;
  }
  await api.deleteWebhook(endpoint.id);
  await load();
}

async function openDeliveries(endpoint: WebhookEndpointView) {
  deliveriesDrawer.value = true;
  deliveriesLoading.value = true;
  try {
    deliveries.value = await api.webhookDeliveries(endpoint.id);
  } finally {
    deliveriesLoading.value = false;
  }
}

function formatTime(iso?: string): string {
  return iso ? new Date(iso).toLocaleString() : '—';
}

onMounted(load);
</script>

<template>
  <div class="webhooks-page">
    <PageHeader title="Webhooks" description="告警投递端点：HMAC-SHA256 签名，失败指数退避重试。" />

    <el-alert v-if="loadError" type="error" :closable="false" class="block-alert">
      {{ loadError
      }}<span v-if="loadRequestId" class="mk-mono">requestId: {{ loadRequestId }}</span>
    </el-alert>

    <div class="mk-filter-bar">
      <el-button type="primary" data-testid="webhook-create-open" @click="creating = !creating">
        {{ creating ? '收起表单' : '创建 Webhook' }}
      </el-button>
    </div>

    <section v-if="creating" class="create-panel" data-testid="webhook-create-form">
      <h3 class="panel-title">创建 Webhook</h3>
      <el-form label-position="top" class="create-form">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" data-testid="webhook-create-name" />
        </el-form-item>
        <el-form-item label="URL" required>
          <el-input v-model="form.url" placeholder="https://…" data-testid="webhook-create-url" />
        </el-form-item>
        <el-form-item label="签名 Secret" required>
          <el-input
            v-model="form.secret"
            type="password"
            show-password
            data-testid="webhook-create-secret"
          />
        </el-form-item>
        <el-form-item label="超时（ms）">
          <el-input v-model.number="form.timeoutMs" type="number" />
        </el-form-item>
        <p v-if="formError" class="form-error">{{ formError }}</p>
        <el-button
          type="primary"
          :loading="submitting"
          data-testid="webhook-create-submit"
          @click="createWebhook"
        >
          创建 Webhook
        </el-button>
      </el-form>
    </section>

    <el-table v-loading="loading" :data="webhooks" data-testid="webhooks-table">
      <el-table-column prop="name" label="名称" min-width="150" />
      <el-table-column label="URL" min-width="240">
        <template #default="{ row }"
          ><span class="mk-mono">{{ row.url }}</span></template
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
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" data-testid="webhook-test" @click="test(row)"
            >测试</el-button
          >
          <el-button
            link
            type="primary"
            data-testid="webhook-deliveries"
            @click="openDeliveries(row)"
          >
            投递
          </el-button>
          <el-button link @click="toggle(row)">{{ row.enabled ? '停用' : '启用' }}</el-button>
          <el-button link type="danger" data-testid="webhook-delete" @click="remove(row)"
            >删除</el-button
          >
        </template>
      </el-table-column>
    </el-table>

    <el-drawer v-model="deliveriesDrawer" title="投递记录" size="520px">
      <el-table v-loading="deliveriesLoading" :data="deliveries" data-testid="deliveries-table">
        <el-table-column prop="attempt" label="次" width="60" />
        <el-table-column label="HTTP" width="90" align="right">
          <template #default="{ row }"
            ><span class="mk-num">{{ row.httpStatus ?? '—' }}</span></template
          >
        </el-table-column>
        <el-table-column label="下次重试" width="170">
          <template #default="{ row }">{{
            row.nextRetryAt ? formatTime(row.nextRetryAt) : '—'
          }}</template>
        </el-table-column>
        <el-table-column prop="errorMessage" label="错误" min-width="140">
          <template #default="{ row }">{{ row.errorMessage ?? '—' }}</template>
        </el-table-column>
      </el-table>
    </el-drawer>
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

.form-error {
  margin-bottom: 12px;
  color: var(--miqrokey-danger);
}
</style>
