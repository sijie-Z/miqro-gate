<script setup lang="ts">
/**
 * NextAdminWebhooksView — /app/webhooks v2 admin page (U2 ops batch).
 * Behaviour parity with the legacy webhooks page: register HMAC-signed alert
 * delivery endpoints, enable/disable, one-click signature test, gated delete
 * and a delivery-history drawer (recent 20 attempts per endpoint).
 */
import { onMounted, ref } from 'vue';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import { UiButton, UiDialog, UiDrawer, UiInput, UiStatusBadge, UiTable, toast } from '@/ui';
import type {WebhookDelivery} from '@/types/api';
import type { WebhookEndpointView } from '@/types/generated-api';

const webhooks = ref<WebhookEndpointView[]>([]);
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');

const columns = [
  { key: 'name', title: '名称', minWidth: '170px' },
  { key: 'url', title: 'URL', minWidth: '260px' },
  { key: 'status', title: '状态', width: '110px' },
  { key: 'createdAt', title: '创建时间', width: '170px' },
  { key: 'actions', title: '操作', width: '250px' },
];

const deliveryColumns = [
  { key: 'attempt', title: '次', width: '70px', align: 'right' as const },
  { key: 'httpStatus', title: 'HTTP', width: '90px', align: 'right' as const },
  { key: 'nextRetryAt', title: '下次重试', minWidth: '170px' },
  { key: 'errorMessage', title: '错误', minWidth: '180px' },
];

// Create form
const creating = ref(false);
const form = ref({ name: '', url: '', secret: '', timeoutMs: '5000' });
const showSecret = ref(false);
const formError = ref('');
const submitting = ref(false);

// Deliveries drawer
const deliveriesOpen = ref(false);
const deliveriesEndpoint = ref<WebhookEndpointView | null>(null);
const deliveries = ref<WebhookDelivery[]>([]);
const deliveriesLoading = ref(false);
const deliveriesError = ref('');

const confirmState = ref<{
  title: string;
  body: string;
  confirmLabel: string;
  tone: 'danger' | 'primary';
  run: () => Promise<void>;
} | null>(null);

async function load() {
  loading.value = true;
  loadError.value = '';
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
  formError.value = '';
  try {
    await api.createWebhook({
      name: form.value.name.trim(),
      url: form.value.url.trim(),
      secret: form.value.secret,
      timeoutMs: Number(form.value.timeoutMs) || 5000,
    });
    creating.value = false;
    form.value = { name: '', url: '', secret: '', timeoutMs: '5000' };
    toast.success('Webhook 已创建');
    await load();
  } catch (error) {
    formError.value = error instanceof ApiError ? error.message : '创建失败，请稍后重试。';
  } finally {
    submitting.value = false;
  }
}

async function toggle(endpoint: WebhookEndpointView) {
  try {
    await api.updateWebhook(endpoint.id, { enabled: !endpoint.enabled });
    toast.success(endpoint.enabled ? 'Webhook 已停用' : 'Webhook 已启用');
    await load();
  } catch (error) {
    if (error instanceof ApiError) {
      toast.error(error.message);
    }
  }
}

async function test(endpoint: WebhookEndpointView) {
  try {
    const result = await api.testWebhook(endpoint.id);
    if (result.httpStatus) {
      toast.success(`测试投递成功（HTTP ${result.httpStatus}）`);
    } else {
      toast.error(`测试投递失败：${result.errorMessage ?? '未知错误'}`);
    }
  } catch (error) {
    if (error instanceof ApiError) {
      toast.error(error.message);
    }
  }
}

function requestRemove(endpoint: WebhookEndpointView) {
  confirmState.value = {
    title: `删除 Webhook「${endpoint.name}」`,
    body: '删除后告警将不再投递到该端点。',
    confirmLabel: '删除',
    tone: 'danger',
    run: async () => {
      try {
        await api.deleteWebhook(endpoint.id);
        toast.success('Webhook 已删除');
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

async function openDeliveries(endpoint: WebhookEndpointView) {
  deliveriesEndpoint.value = endpoint;
  deliveries.value = [];
  deliveriesError.value = '';
  deliveriesOpen.value = true;
  deliveriesLoading.value = true;
  try {
    deliveries.value = await api.webhookDeliveries(endpoint.id);
  } catch (error) {
    deliveriesError.value = error instanceof ApiError ? error.message : '加载投递记录失败。';
  } finally {
    deliveriesLoading.value = false;
  }
}

function formatTime(iso?: string): string {
  if (!iso) return '—';
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

onMounted(load);
</script>

<template>
  <div class="ui-page next-webhooks">
    <header class="ui-page-header">
      <div>
        <h1 class="ui-page-title">Webhook 端点</h1>
        <p class="ui-page-desc">告警投递端点：HMAC-SHA256 签名，失败指数退避重试。</p>
      </div>
      <div class="ui-page-actions">
        <UiButton variant="primary" data-testid="webhook-create-open" @click="creating = !creating">
          {{ creating ? '收起表单' : '创建 Webhook' }}
        </UiButton>
      </div>
    </header>

    <div v-if="loadError" class="ui-alert ui-alert--error">
      {{ loadError
      }}<span v-if="loadRequestId" class="ui-request-id"> requestId: {{ loadRequestId }}</span>
    </div>

    <section
      v-if="creating"
      class="ui-panel next-webhooks__create"
      data-testid="webhook-create-form"
    >
      <div class="ui-panel-head">
        <h2 class="ui-panel-title">创建 Webhook</h2>
      </div>
      <div class="ui-panel-body">
        <div class="next-webhooks__grid">
          <UiInput
            v-model="form.name"
            label="名称"
            required
            placeholder="例如 ops-alerts"
            data-testid="webhook-create-name"
          />
          <UiInput
            v-model="form.url"
            label="URL"
            required
            placeholder="https://…"
            data-testid="webhook-create-url"
          />
          <UiInput
            v-model="form.secret"
            label="签名 Secret"
            required
            :type="showSecret ? 'text' : 'password'"
            placeholder="用于校验 X-Signature 的共享密钥"
            data-testid="webhook-create-secret"
          >
            <template #suffix>
              <button
                type="button"
                class="next-webhooks__reveal"
                :aria-label="showSecret ? '隐藏 Secret' : '显示 Secret'"
                data-testid="webhook-secret-toggle"
                @click="showSecret = !showSecret"
              >
                <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                  <path
                    v-if="!showSecret"
                    d="M1.5 8S3.8 4 8 4s6.5 4 6.5 4-2.3 4-6.5 4S1.5 8 1.5 8Z"
                    stroke="currentColor"
                    stroke-width="1.4"
                  />
                  <circle
                    v-if="!showSecret"
                    cx="8"
                    cy="8"
                    r="1.8"
                    stroke="currentColor"
                    stroke-width="1.4"
                  />
                  <path
                    v-else
                    d="M2 2 14 14M6.2 6.2a2.2 2.2 0 0 0 3.6 3.6M4.6 4.7C2.9 5.7 1.5 8 1.5 8s2.3 4 6.5 4c1.1 0 2.1-.3 3-.7M8.9 4.1c.2 0 .4 0 .6.1M12.2 6.1c1 1 1.8 1.9 1.8 1.9"
                    stroke="currentColor"
                    stroke-width="1.4"
                    stroke-linecap="round"
                  />
                </svg>
              </button>
            </template>
          </UiInput>
          <UiInput
            v-model="form.timeoutMs"
            label="超时（毫秒）"
            placeholder="5000"
            data-testid="webhook-create-timeout"
          />
          <p v-if="formError" class="ui-form-error">{{ formError }}</p>
          <div class="next-webhooks__actions">
            <UiButton
              variant="primary"
              :loading="submitting"
              data-testid="webhook-create-submit"
              @click="createWebhook"
              >创建 Webhook</UiButton
            >
            <UiButton variant="ghost" @click="creating = false">取消</UiButton>
          </div>
        </div>
      </div>
    </section>

    <section class="ui-panel">
      <div class="ui-panel-toolbar">
        <span class="ui-panel-sub">共 {{ webhooks.length }} 个端点</span>
      </div>
      <UiTable
        :columns="columns"
        :data="webhooks"
        :loading="loading"
        row-key="id"
        empty-title="还没有 Webhook 端点"
        empty-description="创建端点后，告警与审批通知将经其签名投递。"
        data-testid="webhooks-table"
      >
        <template #name="{ row }">
          <span class="next-webhooks__name">{{ (row as WebhookEndpointView).name }}</span>
        </template>
        <template #url="{ row }">
          <span class="ui-mono next-webhooks__url">{{ (row as WebhookEndpointView).url }}</span>
        </template>
        <template #status="{ row }">
          <UiStatusBadge
            :tone="(row as WebhookEndpointView).enabled ? 'success' : 'neutral'"
            :label="(row as WebhookEndpointView).enabled ? '已启用' : '已停用'"
          />
        </template>
        <template #createdAt="{ row }">{{
          formatTime((row as WebhookEndpointView).createdAt)
        }}</template>
        <template #actions="{ row }">
          <div class="next-webhooks__actions">
            <UiButton
              variant="ghost"
              size="sm"
              data-testid="webhook-test"
              @click="test(row as WebhookEndpointView)"
              >测试</UiButton
            >
            <UiButton
              variant="ghost"
              size="sm"
              data-testid="webhook-deliveries"
              @click="openDeliveries(row as WebhookEndpointView)"
              >投递</UiButton
            >
            <UiButton variant="ghost" size="sm" @click="toggle(row as WebhookEndpointView)">{{
              (row as WebhookEndpointView).enabled ? '停用' : '启用'
            }}</UiButton>
            <UiButton
              variant="ghost"
              size="sm"
              class="next-webhooks__danger"
              data-testid="webhook-delete"
              @click="requestRemove(row as WebhookEndpointView)"
              >删除</UiButton
            >
          </div>
        </template>
      </UiTable>
    </section>

    <!-- Delivery history for one endpoint -->
    <UiDrawer
      :open="deliveriesOpen"
      :title="deliveriesEndpoint ? `投递记录 · ${deliveriesEndpoint.name}` : '投递记录'"
      width="620px"
      @update:open="deliveriesOpen = false"
    >
      <div v-if="deliveriesError" class="ui-alert ui-alert--error">{{ deliveriesError }}</div>
      <UiTable
        :columns="deliveryColumns"
        :data="deliveries"
        :loading="deliveriesLoading"
        row-key="id"
        empty-title="暂无投递记录"
        empty-description="端点创建后的投递尝试会出现在这里。"
        data-testid="deliveries-table"
      >
        <template #attempt="{ row }">
          <span class="ui-num">{{ (row as WebhookDelivery).attempt }}</span>
        </template>
        <template #httpStatus="{ row }">
          <span class="ui-num">{{ (row as WebhookDelivery).httpStatus ?? '—' }}</span>
        </template>
        <template #nextRetryAt="{ row }">{{
          formatTime((row as WebhookDelivery).nextRetryAt)
        }}</template>
        <template #errorMessage="{ row }">{{
          (row as WebhookDelivery).errorMessage || '—'
        }}</template>
      </UiTable>
    </UiDrawer>

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

.next-webhooks__create {
  margin-bottom: var(--ui-space-5);
  max-width: 760px;
}

.next-webhooks__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--ui-space-4) var(--ui-space-6);
  max-width: 680px;
}

.next-webhooks__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ui-space-1);
  grid-column: 1 / -1;
}

.next-webhooks__name {
  font-weight: var(--ui-weight-medium);
}

.next-webhooks__url {
  font-size: var(--ui-font-size-xs);
  overflow-wrap: anywhere;
}

.next-webhooks__danger {
  color: var(--ui-danger-fg);
}

.next-webhooks__reveal {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border: none;
  border-radius: var(--ui-radius-control);
  background: transparent;
  color: var(--ui-foreground-faint);
  cursor: pointer;
}

.next-webhooks__reveal:hover {
  color: var(--ui-foreground);
}
</style>
