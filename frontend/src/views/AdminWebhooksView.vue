<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import { BrowseIcon, BrowseOffIcon } from 'tdesign-icons-vue-next';
import { confirmDialog } from '@/utils/confirm';
import type { PrimaryTableCol } from 'tdesign-vue-next';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import PageHeader from '@/components/PageHeader.vue';
import type { WebhookDelivery, WebhookEndpointView } from '@/types/api';

const webhooks = ref<WebhookEndpointView[]>([]);
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');

const webhookColumns: PrimaryTableCol[] = [
  { colKey: 'name', title: '名称', minWidth: 150 },
  { colKey: 'url', title: 'URL', minWidth: 240 },
  { colKey: 'status', title: '状态', width: 100 },
  { colKey: 'actions', title: '操作', width: 200, fixed: 'right' },
];

const deliveryColumns: PrimaryTableCol[] = [
  { colKey: 'attempt', title: '次', width: 60 },
  { colKey: 'httpStatus', title: 'HTTP', width: 90, align: 'right' },
  { colKey: 'nextRetry', title: '下次重试', width: 170 },
  { colKey: 'errorMessage', title: '错误', minWidth: 140 },
];

const creating = ref(false);
const form = ref({ name: '', url: '', secret: '', timeoutMs: 5000 });
const showSecret = ref(false);
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
      MessagePlugin.success(`测试投递成功（HTTP ${result.httpStatus}）`);
    } else {
      MessagePlugin.error(`测试投递失败：${result.errorMessage ?? '未知错误'}`);
    }
  } catch (error) {
    if (error instanceof ApiError) {
      MessagePlugin.error(error.message);
    }
  }
}

async function remove(endpoint: WebhookEndpointView) {
  try {
    await confirmDialog({
      header: '删除 Webhook',
      body: `删除 Webhook「${endpoint.name}」后告警将不再投递到该端点。`,
      confirmBtn: '删除',
      cancelBtn: '取消',
      theme: 'warning',
    });
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

    <t-alert v-if="loadError" theme="error" :close-btn="false" class="block-alert">
      {{ loadError
      }}<span v-if="loadRequestId" class="mk-mono">requestId: {{ loadRequestId }}</span>
    </t-alert>

    <div class="mk-filter-bar">
      <t-button theme="primary" data-testid="webhook-create-open" @click="creating = !creating">
        {{ creating ? '收起表单' : '创建 Webhook' }}
      </t-button>
    </div>

    <section v-if="creating" class="create-panel" data-testid="webhook-create-form">
      <h3 class="panel-title">创建 Webhook</h3>
      <t-form label-align="top" class="create-form">
        <t-form-item label="名称" required>
          <t-input v-model="form.name" data-testid="webhook-create-name" />
        </t-form-item>
        <t-form-item label="URL" required>
          <t-input v-model="form.url" placeholder="https://…" data-testid="webhook-create-url" />
        </t-form-item>
        <t-form-item label="签名 Secret" required>
          <t-input
            v-model="form.secret"
            :type="showSecret ? 'text' : 'password'"
            data-testid="webhook-create-secret"
          >
            <template #suffix-icon>
              <component :is="showSecret ? BrowseOffIcon : BrowseIcon"
                aria-label="切换 Secret 可见性"
                role="button"
                @click="showSecret = !showSecret"
              />
            </template>
          </t-input>
        </t-form-item>
        <t-form-item label="超时（ms）">
          <t-input v-model="form.timeoutMs" type="number" />
        </t-form-item>
        <p v-if="formError" class="form-error">{{ formError }}</p>
        <t-button
          theme="primary"
          :loading="submitting"
          data-testid="webhook-create-submit"
          @click="createWebhook"
        >
          创建 Webhook
        </t-button>
      </t-form>
    </section>

    <t-loading :loading="loading" size="small" show-overlay>
      <t-table
        :data="webhooks"
        :columns="webhookColumns"
        row-key="id"
        size="small"
        data-testid="webhooks-table"
      >
        <template #url="{ row }"
          ><span class="mk-mono">{{ row.url }}</span></template
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
          <t-button variant="text" theme="primary" data-testid="webhook-test" @click="test(row)"
            >测试</t-button
          >
          <t-button
            variant="text"
            theme="primary"
            data-testid="webhook-deliveries"
            @click="openDeliveries(row)"
          >
            投递
          </t-button>
          <t-button variant="text" @click="toggle(row)">{{
            row.enabled ? '停用' : '启用'
          }}</t-button>
          <t-button variant="text" theme="danger" data-testid="webhook-delete" @click="remove(row)"
            >删除</t-button
          >
        </template>
      </t-table>
    </t-loading>

    <t-drawer v-model:visible="deliveriesDrawer" header="投递记录" :footer="false" size="520px">
      <t-loading :loading="deliveriesLoading" size="small" show-overlay>
        <t-table
          :data="deliveries"
          :columns="deliveryColumns"
          row-key="id"
          size="small"
          data-testid="deliveries-table"
        >
          <template #httpStatus="{ row }"
            ><span class="mk-num">{{ row.httpStatus ?? '—' }}</span></template
          >
          <template #nextRetry="{ row }">{{
            row.nextRetryAt ? formatTime(row.nextRetryAt) : '—'
          }}</template>
          <template #errorMessage="{ row }">{{ row.errorMessage ?? '—' }}</template>
        </t-table>
      </t-loading>
    </t-drawer>
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
