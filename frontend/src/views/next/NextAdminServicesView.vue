<script setup lang="ts">
/**
 * NextAdminServicesView — /app/services v2 admin page (U2 ops batch).
 * Behaviour parity with the legacy services page: register internal services
 * (platform components, MCP endpoints) that the gateway integrates with and
 * gated disable to remove a service from the usable registry.
 */
import { computed, onMounted, ref } from 'vue';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import { UiButton, UiDialog, UiInput, UiSelect, UiStatusBadge, UiTable, toast } from '@/ui';
import type { InternalServiceView } from '@/types/generated-api';

const services = ref<InternalServiceView[]>([]);
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');

const columns = [
  { key: 'name', title: '名称', minWidth: '170px' },
  { key: 'kind', title: '类型', width: '90px' },
  { key: 'description', title: '描述', minWidth: '180px' },
  { key: 'baseUrl', title: '服务地址', minWidth: '240px' },
  { key: 'status', title: '状态', width: '110px' },
  { key: 'createdAt', title: '创建时间', width: '170px' },
  { key: 'actions', title: '操作', width: '110px', align: 'center' as const },
];

const kindOptions = [
  { value: 'HTTP', label: 'HTTP' },
  { value: 'MCP', label: 'MCP' },
  { value: 'OTHER', label: 'Other' },
];

const creating = ref(false);
const form = ref({ name: '', kind: 'HTTP', description: '', baseUrl: '' });
const formError = ref('');
const submitting = ref(false);

const confirmState = ref<{
  title: string;
  body: string;
  confirmLabel: string;
  tone: 'danger' | 'primary';
  run: () => Promise<void>;
} | null>(null);

const canCreate = computed(
  () => form.value.name.trim().length > 0 && form.value.baseUrl.trim().length > 0,
);

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    services.value = await api.adminListServices();
  } catch (error) {
    if (error instanceof ApiError) {
      loadError.value = error.message;
      loadRequestId.value = error.requestId ?? '';
    } else {
      loadError.value = '加载服务列表失败。';
    }
  } finally {
    loading.value = false;
  }
}

async function createService() {
  if (!canCreate.value) {
    formError.value = '请填写名称与服务地址。';
    return;
  }
  submitting.value = true;
  formError.value = '';
  try {
    await api.adminCreateService({
      name: form.value.name.trim(),
      kind: form.value.kind,
      description: form.value.description.trim() || undefined,
      baseUrl: form.value.baseUrl.trim(),
    });
    creating.value = false;
    form.value = { name: '', kind: 'HTTP', description: '', baseUrl: '' };
    toast.success('服务已注册');
    await load();
  } catch (error) {
    formError.value = error instanceof ApiError ? error.message : '创建失败，请稍后重试。';
  } finally {
    submitting.value = false;
  }
}

function requestDisable(service: InternalServiceView) {
  confirmState.value = {
    title: `禁用服务「${service.name}」`,
    body: '禁用后该服务从可用注册表中移除，注册信息保留。',
    confirmLabel: '禁用',
    tone: 'danger',
    run: async () => {
      try {
        await api.adminDisableService(service.id);
        toast.success('服务已禁用');
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

function formatTime(iso: string): string {
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

onMounted(load);
</script>

<template>
  <div class="ui-page next-services">
    <header class="ui-page-header">
      <div>
        <h1 class="ui-page-title">服务管理</h1>
        <p class="ui-page-desc">
          内部服务注册表：平台组件、MCP 端点等经网关集成的服务；服务地址必须是 https。
        </p>
      </div>
      <div class="ui-page-actions">
        <UiButton variant="primary" data-testid="service-create-open" @click="creating = !creating">
          {{ creating ? '收起表单' : '注册服务' }}
        </UiButton>
      </div>
    </header>

    <div v-if="loadError" class="ui-alert ui-alert--error">
      {{ loadError
      }}<span v-if="loadRequestId" class="ui-request-id"> requestId: {{ loadRequestId }}</span>
    </div>

    <section
      v-if="creating"
      class="ui-panel next-services__create"
      data-testid="service-create-form"
    >
      <div class="ui-panel-head">
        <h2 class="ui-panel-title">注册内部服务</h2>
      </div>
      <div class="ui-panel-body">
        <p class="next-services__hint">服务地址必须为 https、不含用户信息、查询参数或片段。</p>
        <div class="next-services__form">
          <UiInput
            v-model="form.name"
            label="名称"
            required
            placeholder="例如 platform-api"
            data-testid="service-create-name"
          />
          <UiSelect v-model="form.kind" label="类型" :options="kindOptions" />
          <div class="ui-field">
            <span class="ui-field__label">描述</span>
            <textarea
              v-model="form.description"
              class="ui-textarea"
              rows="2"
              placeholder="用途说明（可选）"
              data-testid="service-create-desc"
            />
          </div>
          <UiInput
            v-model="form.baseUrl"
            label="服务地址"
            required
            placeholder="https://platform.internal.example"
            data-testid="service-create-url"
          />
          <p v-if="formError" class="ui-form-error">{{ formError }}</p>
          <div class="next-services__actions">
            <UiButton
              variant="primary"
              :disabled="!canCreate"
              :loading="submitting"
              data-testid="service-create-submit"
              @click="createService"
              >注册</UiButton
            >
            <UiButton variant="ghost" @click="creating = false">取消</UiButton>
          </div>
        </div>
      </div>
    </section>

    <section class="ui-panel">
      <div class="ui-panel-toolbar">
        <span class="ui-panel-sub">共 {{ services.length }} 个服务</span>
      </div>
      <UiTable
        :columns="columns"
        :data="services"
        :loading="loading"
        row-key="id"
        empty-title="还没有注册的内部服务"
        empty-description="平台组件、MCP 端点等接入网关前先在此注册。"
        data-testid="services-table"
      >
        <template #name="{ row }">
          <span class="next-services__name">{{ (row as InternalServiceView).name }}</span>
        </template>
        <template #kind="{ row }">{{ (row as InternalServiceView).kind }}</template>
        <template #description="{ row }">{{
          (row as InternalServiceView).description || '—'
        }}</template>
        <template #baseUrl="{ row }">
          <span class="ui-mono next-services__url">{{ (row as InternalServiceView).baseUrl }}</span>
        </template>
        <template #status="{ row }">
          <UiStatusBadge
            :tone="(row as InternalServiceView).status === 'ACTIVE' ? 'success' : 'neutral'"
            :label="(row as InternalServiceView).status === 'ACTIVE' ? '正常' : '已禁用'"
          />
        </template>
        <template #createdAt="{ row }">{{
          formatTime((row as InternalServiceView).createdAt)
        }}</template>
        <template #actions="{ row }">
          <UiButton
            v-if="(row as InternalServiceView).status === 'ACTIVE'"
            variant="ghost"
            size="sm"
            class="next-services__danger"
            data-testid="service-disable"
            @click="requestDisable(row as InternalServiceView)"
            >禁用</UiButton
          >
          <span v-else>—</span>
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

.ui-field {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-1);
}

.ui-field__label {
  font-size: var(--ui-font-size-xs);
  font-weight: var(--ui-weight-medium);
  color: var(--ui-foreground);
  line-height: var(--ui-line-height-sm);
}

.ui-textarea {
  width: 100%;
  min-height: 56px;
  padding: var(--ui-space-2) var(--ui-space-3);
  border: 1px solid var(--ui-input-border);
  border-radius: var(--ui-radius-control);
  background: var(--ui-card);
  color: var(--ui-foreground);
  font-family: inherit;
  font-size: var(--ui-font-size-sm);
  line-height: var(--ui-line-height-base);
  resize: vertical;
}

.ui-textarea:focus {
  outline: none;
  border-color: var(--ui-primary);
  box-shadow: var(--ui-shadow-focus);
}

.next-services__create {
  margin-bottom: var(--ui-space-5);
  max-width: 720px;
}

.next-services__hint {
  margin: 0 0 var(--ui-space-4);
  font-size: var(--ui-font-size-sm);
  line-height: var(--ui-line-height-lg);
  color: var(--ui-foreground-secondary);
}

.next-services__form {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-4);
  max-width: 520px;
}

.next-services__actions {
  display: inline-flex;
  gap: var(--ui-space-1);
}

.next-services__name {
  font-weight: var(--ui-weight-medium);
}

.next-services__url {
  font-size: var(--ui-font-size-xs);
  overflow-wrap: anywhere;
}

.next-services__danger {
  color: var(--ui-danger-fg);
}
</style>
